/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.ui.settings.drivebackup

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.backup.BackupArchiveException
import moe.rukamori.archivetune.shared.domain.backup.BackupArchiveProblem
import moe.rukamori.archivetune.shared.domain.backup.BackupDataCategory
import moe.rukamori.archivetune.shared.domain.backup.CloudRestorePreparation
import moe.rukamori.archivetune.shared.domain.backup.DriveAppDataActivity as OwnerActivity
import moe.rukamori.archivetune.shared.domain.backup.DriveAppDataBackupException
import moe.rukamori.archivetune.shared.domain.backup.DriveAppDataBackupFailure as OwnerFailure
import moe.rukamori.archivetune.shared.domain.backup.DriveAppDataBackupOwner
import moe.rukamori.archivetune.shared.domain.backup.DriveAppDataFrequency as OwnerFrequency
import moe.rukamori.archivetune.shared.domain.backup.DriveAppDataState
import moe.rukamori.archivetune.shared.domain.backup.PreparedRestoreRegistry
import moe.rukamori.archivetune.shared.domain.backup.toDriveFailure
import moe.rukamori.archivetune.shared.ui.settings.BackupRestoreCandidate
import moe.rukamori.archivetune.shared.ui.settings.BackupRestoreProblem
import moe.rukamori.archivetune.shared.ui.settings.BackupSelectionCategory
import moe.rukamori.archivetune.shared.ui.settings.DriveAppDataActivity
import moe.rukamori.archivetune.shared.ui.settings.DriveAppDataBackupHost
import moe.rukamori.archivetune.shared.ui.settings.DriveAppDataFailure
import moe.rukamori.archivetune.shared.ui.settings.DriveAppDataFrequency
import moe.rukamori.archivetune.shared.ui.settings.DriveAppDataLastBackup
import moe.rukamori.archivetune.shared.ui.settings.DriveAppDataRestorePreparation
import moe.rukamori.archivetune.shared.ui.settings.DriveAppDataSnapshot

/**
 * Locale-aware labels for the newest Drive backup. The host supplies them, so common code never formats times or
 * byte counts.
 */
interface DriveAppDataBackupFormatter {
    fun date(epochMillis: Long): String

    fun size(bytes: Long): String
}

/**
 * Platform account chooser with Drive consent. Returns an account that already has appData access, or null when the
 * user cancels. Failures are thrown as [DriveAppDataBackupException] so the page can show the reason.
 */
interface DriveAppDataAccountChooser {
    suspend fun chooseAuthorizedAccount(): String?
}

/**
 * Presents [DriveAppDataBackupOwner] as the page's [DriveAppDataBackupHost]. Cloud restores and manual file restores
 * share [restores], so both produce handles of one type and both apply through one transactional path.
 *
 * [scope] must supervise its children, as the owner requires. The owner records its expected failures in state.
 * Failures the owner cannot see (a chooser that fails to start, a restore without an account) are kept here and
 * cleared when the next action starts, so a stale failure never hides a newer result.
 */
class DriveAppDataBackupBinding(
    private val scope: CoroutineScope,
    private val owner: DriveAppDataBackupOwner,
    private val restores: PreparedRestoreRegistry,
    private val chooser: DriveAppDataAccountChooser,
    private val formatter: DriveAppDataBackupFormatter,
    private val onRestartRequired: () -> Unit,
) : DriveAppDataBackupHost {
    private val hostFailure = MutableStateFlow<OwnerFailure?>(null)
    private val loaded = MutableStateFlow(false)

    /** Null until the owner has loaded the persisted settings, so the page keeps its checking row meanwhile. */
    override val snapshot: StateFlow<DriveAppDataSnapshot?> =
        combine(owner.state, hostFailure, loaded) { state, pending, isLoaded ->
            if (isLoaded) state.toSnapshot(pending) else null
        }.stateIn(scope, SharingStarted.Eagerly, null)

    init {
        scope.launch {
            try {
                owner.load()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (unexpected: Exception) {
                // The owner reports its own load failures in state. This settles anything it did not expect, so the
                // page shows a failure row instead of the checking row forever.
                hostFailure.value = OwnerFailure.UNKNOWN
            }
            loaded.value = true
        }
    }

    override suspend fun chooseAccount() {
        hostFailure.value = null
        val account = try {
            chooser.chooseAuthorizedAccount()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (rejected: DriveAppDataBackupException) {
            hostFailure.value = rejected.failure
            return
        } catch (unexpected: Exception) {
            hostFailure.value = OwnerFailure.UNKNOWN
            return
        }
        if (account != null) owner.connect(account)
    }

    override suspend fun refresh() {
        hostFailure.value = null
        owner.refresh()
    }

    override suspend fun backupNow() {
        hostFailure.value = null
        owner.backupNow()
    }

    override suspend fun prepareLatestRestore(): DriveAppDataRestorePreparation {
        hostFailure.value = null
        val accountBefore = owner.state.value.settings.accountName
            ?: return failedPreparation(OwnerFailure.AUTHORIZATION)
        return when (val prepared = owner.prepareLatestRestore()) {
            is CloudRestorePreparation.Ready -> {
                if (owner.state.value.settings.accountName != accountBefore) {
                    // The account changed during the download, so this handle must not reach the restore dialog.
                    restores.discard(prepared.handle)
                    DriveAppDataRestorePreparation.Cancelled
                } else {
                    DriveAppDataRestorePreparation.Ready(
                        BackupRestoreCandidate(
                            handle = prepared.handle,
                            categories = prepared.categories.map { it.toSelection() }.toSet(),
                        ),
                    )
                }
            }
            CloudRestorePreparation.Cancelled -> DriveAppDataRestorePreparation.Cancelled
            is CloudRestorePreparation.Rejected -> when (prepared.problem) {
                BackupArchiveProblem.NOT_A_BACKUP ->
                    DriveAppDataRestorePreparation.Rejected(BackupRestoreProblem.NOT_A_BACKUP)
                BackupArchiveProblem.NO_RESTORABLE_DATA ->
                    DriveAppDataRestorePreparation.Rejected(BackupRestoreProblem.NO_RESTORABLE_DATA)
                BackupArchiveProblem.CORRUPTED ->
                    DriveAppDataRestorePreparation.Rejected(BackupRestoreProblem.CORRUPTED)
                // The page has no storage or unavailable reason. These are operational failures, not bad archives,
                // so they surface as failures rather than as a rejected backup.
                BackupArchiveProblem.INSUFFICIENT_STORAGE, BackupArchiveProblem.UNAVAILABLE ->
                    failedPreparation(prepared.problem.toDriveFailure())
            }
            is CloudRestorePreparation.Failed -> failedPreparation(prepared.failure)
        }
    }

    override suspend fun disconnect() {
        hostFailure.value = null
        owner.disconnect()
    }

    override suspend fun cancelRunning() {
        owner.cancelRunning()
    }

    override suspend fun setFrequency(frequency: DriveAppDataFrequency) {
        hostFailure.value = null
        owner.setFrequency(frequency.toOwner())
    }

    override suspend fun setWifiOnly(wifiOnly: Boolean) {
        hostFailure.value = null
        owner.setWifiOnly(wifiOnly)
    }

    /**
     * Applies the selected sections of a prepared archive, from either path. Wire it to the page's restore callback.
     * A failure is recorded as the Drive failure and then rethrown, so the page's restore wrapper announces the restore
     * as failed. Returning normally means the restore was applied. [onRestartRequired] runs only after a restore that
     * completed and asked for a restart.
     */
    suspend fun restorePrepared(handle: String, selected: Set<BackupSelectionCategory>) {
        hostFailure.value = null
        val outcome = try {
            restores.restore(handle, selected.map { it.toDataCategory() }.toSet())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (refused: BackupArchiveException) {
            // The registry keeps the handle when the restore fails, so the user can retry or discard it.
            hostFailure.value = refused.problem.toDriveFailure()
            throw refused
        } catch (unexpected: Exception) {
            // A rollback failure or an unexpected error. The gateway has rolled back or fenced further work, so no
            // restart is requested and the failure is never reported as a restore that was applied.
            hostFailure.value = OwnerFailure.UNKNOWN
            throw unexpected
        }
        if (outcome.restartRequired) onRestartRequired()
    }

    /** Releases a prepared archive without applying it. Wire it to the page's discard callback. */
    suspend fun discardPrepared(handle: String) {
        restores.discard(handle)
    }

    private fun failedPreparation(failure: OwnerFailure): DriveAppDataRestorePreparation {
        hostFailure.value = failure
        return DriveAppDataRestorePreparation.Failed
    }

    /**
     * Owner state shown on the page. A failure from this binding wins, then the owner's last action, then the
     * persisted failure of the last scheduled or manual upload until a later success clears it.
     */
    private fun DriveAppDataState.toSnapshot(pending: OwnerFailure?): DriveAppDataSnapshot {
        val backup = settings.backup
        return DriveAppDataSnapshot(
            accountName = settings.accountName,
            frequency = settings.frequency.toHost(),
            wifiOnly = settings.wifiOnly,
            lastBackup = backup?.let {
                DriveAppDataLastBackup(date = formatter.date(it.modifiedAtMillis), size = formatter.size(it.sizeBytes))
            },
            activity = activity?.toHost(),
            progressPercent = progressPercent,
            failure = (pending ?: operationFailure ?: settings.lastFailure)?.toHost(),
        )
    }
}

/** The owner never reports WAITING; the page's waiting row needs a real scheduler queue state, which is not wired yet. */
private fun OwnerActivity.toHost(): DriveAppDataActivity = when (this) {
    OwnerActivity.CHECKING -> DriveAppDataActivity.CHECKING
    OwnerActivity.PREPARING -> DriveAppDataActivity.PREPARING
    OwnerActivity.UPLOADING -> DriveAppDataActivity.UPLOADING
    OwnerActivity.DOWNLOADING -> DriveAppDataActivity.DOWNLOADING
}

private fun OwnerFrequency.toHost(): DriveAppDataFrequency = when (this) {
    OwnerFrequency.MANUAL -> DriveAppDataFrequency.MANUAL
    OwnerFrequency.DAILY -> DriveAppDataFrequency.DAILY
    OwnerFrequency.WEEKLY -> DriveAppDataFrequency.WEEKLY
    OwnerFrequency.MONTHLY -> DriveAppDataFrequency.MONTHLY
}

private fun DriveAppDataFrequency.toOwner(): OwnerFrequency = when (this) {
    DriveAppDataFrequency.MANUAL -> OwnerFrequency.MANUAL
    DriveAppDataFrequency.DAILY -> OwnerFrequency.DAILY
    DriveAppDataFrequency.WEEKLY -> OwnerFrequency.WEEKLY
    DriveAppDataFrequency.MONTHLY -> OwnerFrequency.MONTHLY
}

private fun OwnerFailure.toHost(): DriveAppDataFailure = when (this) {
    OwnerFailure.AUTHORIZATION -> DriveAppDataFailure.AUTHORIZATION
    OwnerFailure.CONFIGURATION -> DriveAppDataFailure.CONFIGURATION
    OwnerFailure.NETWORK -> DriveAppDataFailure.NETWORK
    OwnerFailure.QUOTA -> DriveAppDataFailure.QUOTA
    OwnerFailure.MISSING -> DriveAppDataFailure.MISSING
    OwnerFailure.INVALID -> DriveAppDataFailure.INVALID
    OwnerFailure.STORAGE -> DriveAppDataFailure.STORAGE
    OwnerFailure.UNKNOWN -> DriveAppDataFailure.UNKNOWN
}

private fun BackupDataCategory.toSelection(): BackupSelectionCategory = when (this) {
    BackupDataCategory.LIBRARY -> BackupSelectionCategory.LIBRARY
    BackupDataCategory.ACCOUNT -> BackupSelectionCategory.ACCOUNT
    BackupDataCategory.SETTINGS -> BackupSelectionCategory.SETTINGS
}

private fun BackupSelectionCategory.toDataCategory(): BackupDataCategory = when (this) {
    BackupSelectionCategory.LIBRARY -> BackupDataCategory.LIBRARY
    BackupSelectionCategory.ACCOUNT -> BackupDataCategory.ACCOUNT
    BackupSelectionCategory.SETTINGS -> BackupDataCategory.SETTINGS
}
