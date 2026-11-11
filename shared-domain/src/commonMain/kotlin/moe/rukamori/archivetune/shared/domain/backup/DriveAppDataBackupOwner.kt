package moe.rukamori.archivetune.shared.domain.backup

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock

/**
 * Google Drive appData backup owner shared by the settings host, scheduled and manual worker runs, and cloud
 * restore. Expected failures are recorded in [state] rather than thrown, so UI hosts only observe. Scheduled
 * runs return a [DriveAppDataJobResult] that the host maps onto its job system.
 *
 * Remote operations are single-flight. [cancelRunning] and [disconnect] cancel the in-flight operation without
 * waiting for it. [scope] must supervise its children (an application scope with a SupervisorJob), so one
 * failed operation never cancels the owner.
 */
class DriveAppDataBackupOwner(
    private val scope: CoroutineScope,
    private val transport: DriveAppDataTransport,
    private val authorization: DriveAppDataAuthorization,
    private val scheduler: DriveAppDataScheduler,
    private val settingsStore: DriveAppDataSettingsStore,
    private val archives: BackupArchiveGateway,
    private val restores: PreparedRestoreRegistry,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val mutableState = MutableStateFlow(DriveAppDataState())

    /** Current settings, activity, progress and the outcome of the last action. */
    val state: StateFlow<DriveAppDataState> = mutableState.asStateFlow()

    /** Serializes remote work: connect, refresh, scheduled or manual upload and cloud restore preparation. */
    private val operationMutex = Mutex()

    /** Serializes reads and writes of the persisted settings. */
    private val storeMutex = Mutex()

    private var loaded = false

    /** The in-flight remote operation. Cancelling it does not need [operationMutex]. */
    @Volatile
    private var running: Job? = null

    /** Loads the persisted settings once. Every operation loads them too, so hosts may skip this call. */
    suspend fun load() {
        ensureLoaded()
    }

    /** Connects [accountName] after the host chose it and Drive access was granted for it. */
    suspend fun connect(accountName: String) {
        operationMutex.withLock {
            if (!ensureLoaded()) return
            attempt(DriveAppDataActivity.CHECKING, persistFor = null) {
                val backup = withToken(accountName) { token -> transport.latest(token) }
                schedulerStep { scheduler.cancelAll() }
                mutateSettings { it.withAccount(accountName).withBackup(accountName, backup) }
                applySchedule()
            }
        }
    }

    /** Re-reads the newest backup for the connected account. */
    suspend fun refresh() {
        operationMutex.withLock {
            if (!ensureLoaded()) return
            val accountName = mutableState.value.settings.accountName ?: return
            attempt(DriveAppDataActivity.CHECKING, persistFor = null) {
                val backup = withToken(accountName) { token -> transport.latest(token) }
                mutateSettings { it.withBackup(accountName, backup) }
                applySchedule()
            }
        }
    }

    /**
     * Removes the connected account and its backup record, and stops the schedule. Stops an in-flight
     * operation first, so disconnect never waits for a long upload.
     */
    suspend fun disconnect() {
        val job = running
        job?.cancel()
        schedulerStep { scheduler.cancelAll() }
        operationMutex.withLock {
            if (!ensureLoaded()) return
            mutateSettings { it.withAccount(null) }
            applySchedule()
            mutableState.update { it.copy(activity = null, progressPercent = null, operationFailure = null) }
        }
    }

    /** Sets the schedule cadence. [DriveAppDataFrequency.MANUAL] keeps only manual uploads. */
    suspend fun setFrequency(frequency: DriveAppDataFrequency) {
        updateSchedule(frequency = frequency, wifiOnly = null)
    }

    /** Sets whether scheduled and manual uploads wait for an unmetered network. */
    suspend fun setWifiOnly(wifiOnly: Boolean) {
        updateSchedule(frequency = null, wifiOnly = wifiOnly)
    }

    /** Queues one manual upload through the scheduler. The host runs it with [runUpload]. */
    suspend fun backupNow() {
        if (!ensureLoaded()) return
        mutableState.update { it.copy(operationFailure = null) }
        val accountName = mutableState.value.settings.accountName
        if (accountName == null) {
            mutableState.update { it.copy(operationFailure = DriveAppDataBackupFailure.AUTHORIZATION) }
            return
        }
        schedulerStep { scheduler.enqueueNow(accountName) }
    }

    /**
     * Stops the in-flight operation and every queued upload, then restores the periodic schedule. The
     * owner cancels only its own work; the host ends any platform-owned restore it started.
     */
    suspend fun cancelRunning() {
        val job = running
        job?.cancel()
        schedulerStep { scheduler.cancelAll() }
        if (!ensureLoaded()) return
        applySchedule()
    }

    /**
     * One scheduled or manual upload run for [accountName]. [attemptNumber] is the host's 0-based attempt
     * count, so transient network failures retry only inside [DriveAppDataLimits.NETWORK_RETRY_ATTEMPTS].
     * Returns [DriveAppDataJobResult.Cancelled] when the account changed or the run was cancelled. Returns
     * [DriveAppDataJobResult.Failed] with UNKNOWN when Drive accepted the archive but its local record could not be
     * saved, so a run never reports success that the saved settings do not show.
     */
    suspend fun runUpload(accountName: String, attemptNumber: Int): DriveAppDataJobResult =
        operationMutex.withLock {
            if (!ensureLoaded()) {
                return@withLock DriveAppDataJobResult.Failed(DriveAppDataBackupFailure.UNKNOWN)
            }
            if (mutableState.value.settings.accountName != accountName) {
                return@withLock DriveAppDataJobResult.Cancelled
            }
            when (val result = attempt(DriveAppDataActivity.PREPARING, persistFor = accountName) {
                uploadArchive(accountName)
            }) {
                is Attempt.Done -> DriveAppDataJobResult.Succeeded
                is Attempt.Failed -> {
                    val transient = result.failure == DriveAppDataBackupFailure.NETWORK
                    if (transient && attemptNumber < DriveAppDataLimits.NETWORK_RETRY_ATTEMPTS) {
                        DriveAppDataJobResult.RetryLater
                    } else {
                        DriveAppDataJobResult.Failed(result.failure)
                    }
                }
                Attempt.Cancelled -> DriveAppDataJobResult.Cancelled
            }
        }

    /**
     * Downloads the newest backup and registers it for restore. The archive is validated before it is
     * registered; the caller applies it later through [PreparedRestoreRegistry.restore].
     */
    suspend fun prepareLatestRestore(): CloudRestorePreparation =
        operationMutex.withLock {
            if (!ensureLoaded()) {
                return@withLock CloudRestorePreparation.Failed(DriveAppDataBackupFailure.UNKNOWN)
            }
            val accountName = mutableState.value.settings.accountName
                ?: return@withLock CloudRestorePreparation.Failed(DriveAppDataBackupFailure.AUTHORIZATION)
            when (val result = attempt(DriveAppDataActivity.CHECKING, persistFor = null) {
                downloadForRestore(accountName)
            }) {
                is Attempt.Done -> when (val prepared = result.value) {
                    is PreparedArchive.Ready -> CloudRestorePreparation.Ready(prepared.handle, prepared.categories)
                    is PreparedArchive.Rejected -> CloudRestorePreparation.Rejected(prepared.problem)
                }
                is Attempt.Failed -> CloudRestorePreparation.Failed(result.failure)
                Attempt.Cancelled -> CloudRestorePreparation.Cancelled
            }
        }

    private suspend fun updateSchedule(frequency: DriveAppDataFrequency?, wifiOnly: Boolean?) {
        if (!ensureLoaded()) return
        mutableState.update { it.copy(operationFailure = null) }
        if (mutableState.value.settings.accountName == null) {
            mutableState.update { it.copy(operationFailure = DriveAppDataBackupFailure.AUTHORIZATION) }
            return
        }
        mutateSettings { current ->
            current.copy(
                frequency = frequency ?: current.frequency,
                wifiOnly = wifiOnly ?: current.wifiOnly,
            )
        }
        applySchedule()
    }

    private suspend fun uploadArchive(accountName: String) {
        val archive = createSyncBackup(archives)
        if (archive.isEmpty() || archive.size > DriveAppDataLimits.MAX_ARCHIVE_BYTES) {
            throw DriveAppDataBackupException(DriveAppDataBackupFailure.STORAGE)
        }
        setActivity(DriveAppDataActivity.UPLOADING)
        val uploaded = withToken(accountName) { token ->
            transport.upload(token, transport.latest(token), archive)
        }
        // This run cannot undo the archive Drive accepted. A record that fails to persist fails the run instead, and
        // the in-memory state keeps the confirmed metadata for this session.
        if (!mutateSettings { it.withBackup(accountName, uploaded) }) {
            throw DriveAppDataBackupException(DriveAppDataBackupFailure.UNKNOWN)
        }
    }

    private suspend fun downloadForRestore(accountName: String): PreparedArchive {
        val archive = withToken(accountName) { token ->
            val latest = transport.latest(token)
                ?: throw DriveAppDataBackupException(DriveAppDataBackupFailure.MISSING)
            mutateSettings { it.withBackup(accountName, latest) }
            setActivity(DriveAppDataActivity.DOWNLOADING)
            transport.download(token, latest) { percent -> setProgress(percent) }
        }
        setActivity(DriveAppDataActivity.PREPARING)
        return restores.prepare(archive)
    }

    /**
     * Runs [block] as the in-flight operation. Expected failures are classified and recorded; cancellation
     * from [cancelRunning] yields [Attempt.Cancelled], while cancellation of the caller is rethrown.
     */
    private suspend fun <T> attempt(
        activity: DriveAppDataActivity?,
        persistFor: String?,
        block: suspend () -> T,
    ): Attempt<T> {
        mutableState.update { it.copy(activity = activity, progressPercent = null, operationFailure = null) }
        try {
            val job = scope.async(start = CoroutineStart.LAZY) { block() }
            running = job
            job.start()
            try {
                return Attempt.Done(job.await())
            } catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive()
                return Attempt.Cancelled
            } finally {
                if (running === job) running = null
                job.cancel()
            }
        } catch (failure: DriveAppDataBackupException) {
            return fail(failure.failure, persistFor)
        } catch (archive: BackupArchiveException) {
            return fail(archive.problem.toDriveFailure(), persistFor)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (unexpected: Exception) {
            return fail(DriveAppDataBackupFailure.UNKNOWN, persistFor)
        } finally {
            mutableState.update { it.copy(activity = null, progressPercent = null) }
        }
    }

    private suspend fun <T> fail(failure: DriveAppDataBackupFailure, persistFor: String?): Attempt<T> {
        mutableState.update { it.copy(operationFailure = failure) }
        if (persistFor != null) {
            mutateSettings { it.withFailure(persistFor, failure, nowMillis()) }
        }
        return Attempt.Failed(failure)
    }

    /**
     * Runs [action] with a token for [accountName]. When Drive rejects the token, the token is dropped and
     * the action runs once more with a fresh one. Other failures are rethrown unchanged.
     */
    private suspend fun <T> withToken(accountName: String, action: suspend (String) -> T): T {
        val token = authorization.token(accountName)
        return try {
            action(token)
        } catch (rejected: DriveAppDataBackupException) {
            if (rejected.failure != DriveAppDataBackupFailure.AUTHORIZATION) throw rejected
            authorization.invalidate(token)
            action(authorization.token(accountName))
        }
    }

    private suspend fun applySchedule() {
        val schedule = mutableState.value.settings.schedule()
        schedulerStep { scheduler.apply(schedule) }
    }

    private suspend fun schedulerStep(step: suspend () -> Unit) {
        try {
            step()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (unexpected: Exception) {
            mutableState.update { it.copy(operationFailure = DriveAppDataBackupFailure.UNKNOWN) }
        }
    }

    private suspend fun ensureLoaded(): Boolean = storeMutex.withLock {
        if (loaded) return@withLock true
        try {
            val saved = settingsStore.read()
            mutableState.update { it.copy(settings = saved) }
            loaded = true
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (unexpected: Exception) {
            // Without the saved settings, writing anything would replace them, so nothing is mutated.
            mutableState.update { it.copy(operationFailure = DriveAppDataBackupFailure.UNKNOWN) }
            false
        }
    }

    /**
     * Applies [transform] in memory, then persists the settings. Returns false when the write failed; the in-memory
     * change stays and the failure is recorded as UNKNOWN.
     */
    private suspend fun mutateSettings(transform: (DriveAppDataSettings) -> DriveAppDataSettings): Boolean {
        mutableState.update { it.copy(settings = transform(it.settings)) }
        return try {
            storeMutex.withLock { settingsStore.write(mutableState.value.settings) }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (unexpected: Exception) {
            mutableState.update { it.copy(operationFailure = DriveAppDataBackupFailure.UNKNOWN) }
            false
        }
    }

    private fun setActivity(activity: DriveAppDataActivity?) {
        mutableState.update { it.copy(activity = activity, progressPercent = null) }
    }

    private fun setProgress(percent: Int) {
        mutableState.update { it.copy(progressPercent = percent) }
    }

    private sealed interface Attempt<out T> {
        class Done<out T>(val value: T) : Attempt<T>

        class Failed(val failure: DriveAppDataBackupFailure) : Attempt<Nothing>

        data object Cancelled : Attempt<Nothing>
    }
}

private fun DriveAppDataSettings.withAccount(accountName: String?): DriveAppDataSettings =
    if (accountName == this.accountName) this else DriveAppDataSettings(accountName = accountName)

private fun DriveAppDataSettings.withBackup(
    accountName: String,
    backup: DriveAppDataBackupMetadata?,
): DriveAppDataSettings =
    if (accountName != this.accountName) {
        this
    } else {
        copy(backup = backup, lastFailure = null, lastFailureAtMillis = 0L)
    }

private fun DriveAppDataSettings.withFailure(
    accountName: String,
    failure: DriveAppDataBackupFailure,
    atMillis: Long,
): DriveAppDataSettings =
    if (accountName != this.accountName) {
        this
    } else {
        copy(lastFailure = failure, lastFailureAtMillis = atMillis)
    }

private fun DriveAppDataSettings.schedule(): DriveAppDataSchedule =
    if (accountName == null || frequency == DriveAppDataFrequency.MANUAL) {
        DriveAppDataSchedule.Off
    } else {
        DriveAppDataSchedule.Periodic(
            days = frequency.days,
            network = if (wifiOnly) DriveAppDataNetwork.UNMETERED else DriveAppDataNetwork.CONNECTED,
        )
    }
