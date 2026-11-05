package moe.rukamori.archivetune.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.StatFs
import android.system.Os
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.db.InternalDatabase
import moe.rukamori.archivetune.playback.MusicService.Companion.PERSISTENT_QUEUE_FILE
import moe.rukamori.archivetune.shared.data.backup.RecoveryOutcome
import moe.rukamori.archivetune.shared.data.backup.RestoreFileOps
import moe.rukamori.archivetune.shared.data.backup.RestoreTarget
import moe.rukamori.archivetune.shared.data.backup.RestoreTransaction
import moe.rukamori.archivetune.shared.data.persistence.LegacyDataStorePreferences
import moe.rukamori.archivetune.viewmodels.BackupCategory

/**
 * Stages an Android backup for the next process and applies it before Hilt, Room or DataStore opens.
 *
 * The request process never touches a live file. The staged set is only a ready description plus
 * allowlisted bytes; the shared [RestoreTransaction] journal is created at startup and is the sole
 * authority for whether the old or new state is opened.
 */
internal object AndroidRestoreCoordinator {
    private const val RESTORE_PENDING_DIR = "restore-pending"
    private const val STAGE_PREFIX = "staged-"
    private const val STAGE_ROOT_PREFIX = "stage-"
    private const val BACKUP_PREFIX = "previous-"
    private const val READY_FILE = "ready"
    private const val JOURNAL_FILE = "transaction"
    private const val BLOCKED_FILE = "blocked"
    private const val SETTINGS_PREVIOUS_FILE = "settings-previous.preferences_pb"
    private const val READY_VERSION = "2"
    private const val SETTINGS_XML = "settings.xml"
    private const val SETTINGS_PB = "settings.preferences_pb"
    private const val BUFFER_SIZE = 64 * 1024
    private const val TAG = "AndroidRestore"

    @Volatile
    private var blocked = false

    @Volatile
    private var blockedMarkerExistedAtStartup = false

    fun isBlocked(): Boolean = blocked

    /** True when this process found an existing blocked marker and therefore skipped another recovery pass. */
    internal fun wasBlockedAtStartup(): Boolean = blockedMarkerExistedAtStartup

    /** Read-only check used by the launcher gate; it never creates the restore directory. */
    internal fun hasBlockedMarker(context: Context): Boolean = blockedMarker(context).isFile

    internal fun clearBlockedMarker(context: Context) {
        runCatching { blockedMarker(context).delete() }
        blockedMarkerExistedAtStartup = false
    }

    /**
     * Stages [uri] for the next process. This operation only reads the archive and writes private
     * staging files; the live database, fonts and DataStore are untouched.
     */
    suspend fun stage(
        context: Context,
        uri: Uri,
        categories: Set<BackupCategory>,
    ): StagedRestore = withContext(Dispatchers.IO) {
        val root = restoreRoot(context)
        if (!AndroidRestoreArchivePolicy.canStage(
                readyExists = File(root, READY_FILE).exists(),
                journalExists = File(root, JOURNAL_FILE).exists(),
            )
        ) {
            throw PendingRestoreException()
        }
        cleanupOrphans(context)

        val includeLibrary = BackupCategory.LIBRARY in categories
        val includeSettings = BackupCategory.SETTINGS in categories
        val includeAccount = BackupCategory.ACCOUNT in categories
        val stageId = UUID.randomUUID().toString()
        val stageRoot = File(root, "$STAGE_ROOT_PREFIX$stageId")
        val stagedDir = File(stageRoot, "staged")
        ensureDirectory(stagedDir)
        var readyWritten = false

        try {
            val fontsDir = File(context.filesDir, BackupArchiveRepository.CUSTOM_FONTS_DIR_NAME)
            val fontStageRoot = File(fontsDir, RESTORE_PENDING_DIR)
            val fontStage = File(fontStageRoot, "$STAGE_PREFIX$stageId")
            val settingsDir = File(context.filesDir, "datastore")
            val pbStageRoot = File(settingsDir, RESTORE_PENDING_DIR)
            val pbStage = File(pbStageRoot, "$STAGE_PREFIX$stageId")
            val settingsExcludedKeys =
                if (includeAccount) emptySet() else BackupArchiveRepository.ACCOUNT_PREFERENCE_KEYS
            var hasDb = false
            var settingsKind = SettingsKind.NONE
            var fontCount = 0
            var totalBytes = 0L
            val seen = HashSet<String>()

            context.applicationContext.contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        currentCoroutineContext().ensureActive()
                        val name = entry.name
                        if (entry.isDirectory || name in seen) {
                            throw IllegalArgumentException("Invalid restore entry")
                        }
                        seen += name
                        val isDb = AndroidRestoreArchivePolicy.isDatabase(name)
                        val isXml = AndroidRestoreArchivePolicy.isSettingsXml(name)
                        val isPb = AndroidRestoreArchivePolicy.isSettingsPb(name)
                        val isFont = AndroidRestoreArchivePolicy.isFont(name)
                        if (!AndroidRestoreArchivePolicy.isAllowed(name)) {
                            throw IllegalArgumentException("Unknown restore entry")
                        }
                        when {
                            isDb && includeLibrary -> {
                                if (name == InternalDatabase.DB_NAME) hasDb = true
                                checkEntrySize(entry, AndroidRestoreLimits.MAX_ENTRY_BYTES)
                                totalBytes = copyEntry(
                                    zip,
                                    File(stagedDir, name),
                                    name,
                                    totalBytes,
                                    AndroidRestoreLimits.MAX_ENTRY_BYTES,
                                )
                            }

                            isXml && includeSettings -> {
                                if (settingsKind == SettingsKind.PB) throw IllegalArgumentException("Ambiguous settings payload")
                                settingsKind = SettingsKind.XML
                                require(entry.size != 0L) { "Restore settings are empty" }
                                checkEntrySize(entry, AndroidRestoreLimits.MAX_SETTINGS_BYTES)
                                totalBytes = copyEntry(
                                    zip,
                                    File(stagedDir, SETTINGS_XML),
                                    name,
                                    totalBytes,
                                    AndroidRestoreLimits.MAX_SETTINGS_BYTES,
                                )
                            }

                            isPb && includeSettings -> {
                                if (settingsKind == SettingsKind.XML) throw IllegalArgumentException("Ambiguous settings payload")
                                settingsKind = SettingsKind.PB
                                require(entry.size != 0L) { "Restore settings are empty" }
                                checkEntrySize(entry, AndroidRestoreLimits.MAX_SETTINGS_BYTES)
                                ensureDirectory(pbStage)
                                totalBytes = copyEntry(
                                    zip,
                                    File(pbStage, SETTINGS_PB),
                                    name,
                                    totalBytes,
                                    AndroidRestoreLimits.MAX_SETTINGS_BYTES,
                                )
                            }

                            isFont && includeSettings -> {
                                require(entry.size != 0L) { "Restore font is empty" }
                                checkEntrySize(entry, AndroidRestoreLimits.MAX_FONT_BYTES)
                                if (fontCount >= AndroidRestoreLimits.MAX_FONT_ENTRIES) {
                                    throw IllegalArgumentException("Too many restore fonts")
                                }
                                fontCount++
                                ensureDirectory(fontStage)
                                totalBytes = copyEntry(
                                    zip,
                                    File(fontStage, AndroidRestoreArchivePolicy.fontName(name)),
                                    name,
                                    totalBytes,
                                    AndroidRestoreLimits.MAX_FONT_BYTES,
                                )
                            }
                        }
                        entry = zip.nextEntry
                    }
                }
            } ?: throw IllegalArgumentException("Restore archive unavailable")

            require(seen.isNotEmpty()) { "Empty restore archive" }
            require(!includeLibrary || hasDb) { "Backup missing database" }
            require(!includeSettings || settingsKind != SettingsKind.NONE) { "Backup missing settings" }

            val fontEntries = fontStage.listFiles()
                ?.filter { it.isFile }
                ?.map { AndroidRestoreFont(it.name, it.length()) }
                ?.sortedBy { it.name }
                .orEmpty()
            require(fontEntries.size <= AndroidRestoreLimits.MAX_FONT_ENTRIES) { "Too many restore fonts" }
            require(fontEntries.all(AndroidRestoreFontPolicy::isValid)) { "Invalid restore font" }

            var previousSettingsPresent = false
            if (settingsKind == SettingsKind.XML) {
                val liveSettings = File(settingsDir, SETTINGS_PB)
                if (liveSettings.isFile) {
                    require(liveSettings.length() in 1..AndroidRestoreLimits.MAX_SETTINGS_BYTES) {
                        "Live settings are invalid to snapshot"
                    }
                    FileInputStream(liveSettings).use { input ->
                        totalBytes = copyBounded(
                            input,
                            File(stageRoot, SETTINGS_PREVIOUS_FILE),
                            totalBytes,
                            AndroidRestoreLimits.MAX_SETTINGS_BYTES,
                            SETTINGS_PREVIOUS_FILE,
                        )
                    }
                    previousSettingsPresent = true
                }
            }

            val ready = ReadyMarker(
                stageId = stageId,
                library = includeLibrary,
                settings = includeSettings,
                account = includeAccount,
                settingsKind = settingsKind,
                fontEntries = fontEntries,
                previousSettingsPresent = previousSettingsPresent,
            )
            val targets = buildTargets(context, ready)
            preflightTargets(targets)
            if (includeLibrary) preflightDatabase(File(stagedDir, InternalDatabase.DB_NAME))
            if (settingsKind == SettingsKind.XML) {
                preflightXml(File(stagedDir, SETTINGS_XML), settingsExcludedKeys)
            } else if (settingsKind == SettingsKind.PB) {
                preflightPb(File(pbStage, SETTINGS_PB))
            }
            require(StatFs(root.path).availableBytes >= AndroidRestoreLimits.MIN_FREE_BYTES) {
                "Not enough free space for restore staging"
            }

            writeReady(context, ready)
            readyWritten = true
            StagedRestore(
                stageId = stageId,
                library = includeLibrary,
                settings = includeSettings,
                account = includeAccount,
                settingsKind = settingsKind,
            )
        } finally {
            if (!readyWritten) {
                deleteTree(stageRoot)
                val fontsDir = File(context.filesDir, BackupArchiveRepository.CUSTOM_FONTS_DIR_NAME)
                deleteTree(File(fontsDir, RESTORE_PENDING_DIR, "$STAGE_PREFIX$stageId"))
                deleteTree(File(fontsDir, RESTORE_PENDING_DIR, "$BACKUP_PREFIX$stageId"))
                val settingsDir = File(context.filesDir, "datastore")
                deleteTree(File(settingsDir, RESTORE_PENDING_DIR, "$STAGE_PREFIX$stageId"))
                deleteTree(File(settingsDir, RESTORE_PENDING_DIR, "$BACKUP_PREFIX$stageId"))
            }
        }
    }

    /**
     * Applies a ready stage or finishes an interrupted transaction before any application owner opens
     * Room or DataStore. A blocked decision is durable and is also shown by the launcher gate.
     */
    fun recoverAtStartup(context: Context) {
        try {
            runBlocking(Dispatchers.IO) {
                val root = restoreRoot(context)
                if (AndroidRestoreBlockedPolicy.shouldSkipHeavyRecovery(hasBlockedMarker(context))) {
                    blocked = true
                    blockedMarkerExistedAtStartup = true
                    return@runBlocking
                }
                val ops = AndroidRestoreFileOps()
                val journal = File(root, JOURNAL_FILE)
                val ready = readReady(context)
                recoverAtStartup(
                    ops = ops,
                    journalPath = journal.path,
                    ready = ready,
                    callbacks = AndroidRestoreRecoveryCallbacks(
                        hasRequiredStage = { candidate -> hasRequiredStage(context, candidate) },
                        commit = { candidate -> commit(context, ops, candidate) },
                        cleanup = { candidate -> cleanupSafely(context, candidate) },
                        deletePendingQueue = { deletePendingQueue(context) },
                        resumeApply = { candidate -> resumeApply(context, ops, candidate, journal.path) },
                        block = { markBlocked(context) },
                    ),
                )
                if (!blocked) clearBlockedMarker(context)
            }
        } catch (failure: Exception) {
            Log.w(TAG, "Restore recovery failed", failure)
            markBlocked(context)
        } catch (failure: LinkageError) {
            Log.w(TAG, "Restore recovery could not load", failure)
            markBlocked(context)
        }
    }

    internal data class AndroidRestoreRecoveryCallbacks(
        val hasRequiredStage: (ReadyMarker) -> Boolean,
        val commit: suspend (ReadyMarker) -> Unit,
        val cleanup: (ReadyMarker?) -> Unit,
        val deletePendingQueue: () -> Unit,
        val resumeApply: suspend (ReadyMarker) -> Unit,
        val block: () -> Unit,
    )

    /**
     * The testable state machine. The Android adapter supplies file paths and DataStore work; this
     * function only classifies the shared journal and invokes the matching action.
     */
    internal suspend fun recoverAtStartup(
        ops: RestoreFileOps,
        journalPath: String,
        ready: ReadyMarker?,
        callbacks: AndroidRestoreRecoveryCallbacks,
    ): RecoveryOutcome {
        val outcome = try {
            RestoreTransaction.recover(ops, journalPath)
        } catch (_: Exception) {
            val unsafe = runCatching { RestoreTransaction.recoverEndsInRollback(ops, journalPath) }
                .getOrDefault(true)
            if (unsafe) callbacks.block()
            return RecoveryOutcome.CORRUPT
        }
        when (outcome) {
            RecoveryOutcome.NOTHING ->
                if (ready == null) {
                    callbacks.cleanup(null)
                } else if (runCatching { callbacks.hasRequiredStage(ready) }.getOrDefault(false)) {
                    callbacks.commit(ready)
                } else {
                    callbacks.cleanup(ready)
                }

            RecoveryOutcome.ROLLED_BACK -> callbacks.cleanup(ready)

            RecoveryOutcome.COMMITTED -> {
                runCatching { callbacks.deletePendingQueue() }
                runCatching { callbacks.cleanup(ready) }
            }

            RecoveryOutcome.PENDING_APPLY ->
                if (ready?.settingsKind == SettingsKind.XML) {
                    callbacks.resumeApply(ready)
                } else {
                    callbacks.block()
                }

            RecoveryOutcome.CORRUPT -> callbacks.block()
        }
        return outcome
    }

    private suspend fun settleAfterTransactionFailure(
        context: Context,
        ops: RestoreFileOps,
        ready: ReadyMarker,
        journal: File,
        applyPendingSettings: Boolean,
    ) {
        val outcome = try {
            RestoreTransaction.recover(ops, journal.path)
        } catch (failure: Exception) {
            val unsafe = runCatching { RestoreTransaction.recoverEndsInRollback(ops, journal.path) }
                .getOrDefault(true)
            if (unsafe) markBlocked(context) else Log.w(TAG, "Committed restore cleanup is pending", failure)
            return
        }
        when (outcome) {
            RecoveryOutcome.COMMITTED -> {
                deletePendingQueue(context)
                cleanupSafely(context, ready)
            }

            RecoveryOutcome.ROLLED_BACK, RecoveryOutcome.NOTHING -> cleanupSafely(context, ready)

            RecoveryOutcome.PENDING_APPLY ->
                if (applyPendingSettings && ready.settingsKind == SettingsKind.XML) {
                    resumeApply(context, ops, ready, journal.path)
                } else {
                    Log.w(TAG, "Restore settings apply is pending; next start will retry")
                }

            RecoveryOutcome.CORRUPT -> markBlocked(context)
        }
    }

    private fun cleanupSafely(context: Context, ready: ReadyMarker?) {
        runCatching { cleanupAfter(context, ready) }
            .onFailure { Log.w(TAG, "Restore cleanup will be retried", it) }
    }

    private fun markBlocked(context: Context) {
        blocked = true
        runCatching {
            val marker = blockedMarker(context)
            ensureDirectory(marker.parentFile ?: throw IllegalStateException("Blocked marker has no parent"))
            AndroidRestoreFileOps().writeAtomically(marker.path, "1\n".encodeToByteArray())
        }.onFailure { Log.w(TAG, "Blocked restore marker could not be written", it) }
    }

    private suspend fun commit(
        context: Context,
        ops: RestoreFileOps,
        ready: ReadyMarker,
    ) {
        val root = restoreRoot(context)
        val journal = File(root, JOURNAL_FILE)
        val targets = try {
            buildTargets(context, ready)
        } catch (_: Exception) {
            cleanupSafely(context, ready)
            return
        }
        val transaction = RestoreTransaction(ops, journal.path, targets)
        try {
            transaction.commit(retainBackups = ready.settingsKind == SettingsKind.XML)
        } catch (failure: Exception) {
            if (journal.exists()) {
                settleAfterTransactionFailure(context, ops, ready, journal, applyPendingSettings = true)
            } else {
                Log.w(TAG, "Restore commit rolled back", failure)
                cleanupSafely(context, ready)
            }
            return
        }
        if (ready.settingsKind == SettingsKind.XML) {
            try {
                applyXml(context, ready)
            } catch (failure: Exception) {
                try {
                    transaction.rollbackCommitted()
                    restorePreviousSettings(context, ready)
                    cleanupSafely(context, ready)
                } catch (_: Exception) {
                    Log.w(TAG, "Restore settings apply and rollback failed", failure)
                    markBlocked(context)
                }
                return
            }
        }
        try {
            transaction.finalize()
            deletePendingQueue(context)
            cleanupSafely(context, ready)
        } catch (_: Exception) {
            settleAfterTransactionFailure(context, ops, ready, journal, applyPendingSettings = false)
        }
    }

    private suspend fun resumeApply(
        context: Context,
        ops: RestoreFileOps,
        ready: ReadyMarker,
        journalPath: String,
    ) {
        val transaction = RestoreTransaction.resume(ops, journalPath) ?: run {
            markBlocked(context)
            return
        }
        try {
            applyXml(context, ready)
        } catch (failure: Exception) {
            try {
                transaction.rollbackCommitted()
                restorePreviousSettings(context, ready)
                cleanupSafely(context, ready)
            } catch (_: Exception) {
                Log.w(TAG, "Retried settings apply and rollback failed", failure)
                markBlocked(context)
            }
            return
        }
        try {
            transaction.finalize()
            deletePendingQueue(context)
            cleanupSafely(context, ready)
        } catch (_: Exception) {
            settleAfterTransactionFailure(context, ops, ready, File(journalPath), applyPendingSettings = false)
        }
    }

    private suspend fun applyXml(context: Context, ready: ReadyMarker) {
        val file = File(stageRoot(context, ready), "staged/$SETTINGS_XML")
        require(file.isFile) { "Staged settings missing" }
        FileInputStream(file).use { input ->
            restoreSettingsFromXml(
                context,
                input,
                if (ready.account) emptySet() else BackupArchiveRepository.ACCOUNT_PREFERENCE_KEYS,
            )
        }
    }

    private fun buildTargets(context: Context, ready: ReadyMarker): List<RestoreTarget> {
        val targets = mutableListOf<RestoreTarget>()
        val stageRoot = stageRoot(context, ready)
        if (ready.library) {
            val dbDir = context.getDatabasePath(InternalDatabase.DB_NAME).parentFile
                ?: throw IllegalStateException("Database directory unavailable")
            val staged = File(stageRoot, "staged")
            val previous = File(stageRoot, "previous")
            RestoreTarget.database(
                livePath = File(dbDir, InternalDatabase.DB_NAME).path,
                stagedPath = File(staged, InternalDatabase.DB_NAME).path,
                backupPath = File(previous, InternalDatabase.DB_NAME).path,
            ).forEach { targets.add(it) }
            targets += RestoreTarget(
                livePath = File(dbDir, "${InternalDatabase.DB_NAME}-journal").path,
                stagedPath = File(staged, "${InternalDatabase.DB_NAME}-journal").path,
                backupPath = File(previous, "${InternalDatabase.DB_NAME}-journal").path,
                stagedRequired = false,
            )
        }
        if (ready.settingsKind == SettingsKind.PB) {
            val settingsDir = File(context.filesDir, "datastore")
            val staged = File(settingsDir, RESTORE_PENDING_DIR, "$STAGE_PREFIX${ready.stageId}")
            val previous = File(settingsDir, RESTORE_PENDING_DIR, "$BACKUP_PREFIX${ready.stageId}")
            targets += RestoreTarget(
                livePath = File(settingsDir, SETTINGS_PB).path,
                stagedPath = File(staged, SETTINGS_PB).path,
                backupPath = File(previous, SETTINGS_PB).path,
            )
        }
        if (ready.settings) {
            val fontsDir = File(context.filesDir, BackupArchiveRepository.CUSTOM_FONTS_DIR_NAME)
            val staged = File(fontsDir, RESTORE_PENDING_DIR, "$STAGE_PREFIX${ready.stageId}")
            val previous = File(fontsDir, RESTORE_PENDING_DIR, "$BACKUP_PREFIX${ready.stageId}")
            ready.fontEntries.forEach { font ->
                val file = File(staged, font.name)
                require(file.isFile && file.length() == font.length) { "Staged font changed: ${font.name}" }
                targets += RestoreTarget(
                    livePath = File(fontsDir, font.name).path,
                    stagedPath = file.path,
                    backupPath = File(previous, font.name).path,
                )
            }
        }
        return targets
    }

    private fun preflightTargets(targets: List<RestoreTarget>) {
        targets.forEach { target ->
            val live = File(target.livePath).parentFile
                ?: throw IllegalArgumentException("Live target has no parent")
            sameFilesystem(live, File(target.stagedPath).parentFile ?: throw IllegalArgumentException("Stage has no parent"))
            sameFilesystem(live, File(target.backupPath).parentFile ?: throw IllegalArgumentException("Backup has no parent"))
        }
    }

    private fun sameFilesystem(first: File, second: File) {
        ensureDirectory(first)
        ensureDirectory(second)
        val firstDevice = Os.stat(first.path).st_dev
        val secondDevice = Os.stat(second.path).st_dev
        require(firstDevice == secondDevice) { "Restore staging crosses filesystems" }
    }

    private fun preflightDatabase(file: File) {
        require(file.isFile && file.length() > 0L) { "Restore database is empty" }
        val database = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val cursor = database.rawQuery("PRAGMA quick_check(1)", null)
            try {
                require(cursor.moveToFirst() && cursor.getString(0) == "ok") {
                    "Restore database failed integrity check"
                }
            } finally {
                cursor.close()
            }
        } finally {
            database.close()
        }
    }

    private fun preflightXml(file: File, excludedKeys: Set<String>) {
        require(file.isFile && file.length() > 0L) { "Restore settings missing" }
        val values = FileInputStream(file).use { input -> readLegacySettings(input, excludedKeys) }
        require(!values.isEmpty) { "Restore settings are empty" }
    }

    private fun preflightPb(file: File) {
        require(file.isFile && file.length() > 0L) { "Restore settings missing" }
        val snapshot = LegacyDataStorePreferences.read(file.readBytes())
        require(snapshot.keys.isNotEmpty()) { "Restore settings are empty" }
    }

    private fun hasRequiredStage(context: Context, ready: ReadyMarker): Boolean {
        val staged = File(stageRoot(context, ready), "staged")
        if (ready.library && !File(staged, InternalDatabase.DB_NAME).isFile) return false
        if (ready.settingsKind == SettingsKind.XML && !File(staged, SETTINGS_XML).isFile) return false
        if (ready.settingsKind == SettingsKind.XML) {
            val snapshotPresent = File(stageRoot(context, ready), SETTINGS_PREVIOUS_FILE).isFile
            if (ready.previousSettingsPresent != snapshotPresent) return false
        }
        if (ready.settingsKind == SettingsKind.PB &&
            !File(File(context.filesDir, "datastore/$RESTORE_PENDING_DIR/$STAGE_PREFIX${ready.stageId}"), SETTINGS_PB).isFile
        ) {
            return false
        }
        if (ready.settings) {
            val fonts = File(
                context.filesDir,
                "${BackupArchiveRepository.CUSTOM_FONTS_DIR_NAME}/$RESTORE_PENDING_DIR/$STAGE_PREFIX${ready.stageId}",
            )
            val actual = fonts.listFiles()
                ?.filter { it.isFile }
                ?.map { AndroidRestoreFont(it.name, it.length()) }
                ?.sortedBy { it.name }
                .orEmpty()
            if (!AndroidRestoreFontPolicy.matches(ready.fontEntries, actual)) return false
        }
        return true
    }

    private fun writeReady(context: Context, ready: ReadyMarker) {
        val body = buildString {
            append("version=").append(READY_VERSION).append('\n')
            append("stage=").append(ready.stageId).append('\n')
            append("library=").append(ready.library).append('\n')
            append("settings=").append(ready.settings).append('\n')
            append("account=").append(ready.account).append('\n')
            append("kind=").append(ready.settingsKind.name).append('\n')
            append("previous-settings=").append(ready.previousSettingsPresent).append('\n')
            append("fonts=").append(AndroidRestoreFontPolicy.encode(ready.fontEntries)).append('\n')
        }
        AndroidRestoreFileOps().writeAtomically(
            File(restoreRoot(context), READY_FILE).path,
            body.encodeToByteArray(),
        )
    }

    private fun readReady(context: Context): ReadyMarker? {
        val file = File(restoreRoot(context), READY_FILE)
        if (!file.isFile) return null
        val values = file.readLines()
            .mapNotNull { line -> line.indexOf('=').takeIf { it > 0 }?.let { line.substring(0, it) to line.substring(it + 1) } }
            .toMap()
        if (values["version"] != READY_VERSION) return null
        val stageId = values["stage"]?.takeIf(AndroidRestoreFontPolicy::isSafeStageId) ?: return null
        val kind = values["kind"]?.let { runCatching { SettingsKind.valueOf(it) }.getOrNull() } ?: return null
        val settings = values["settings"]?.toBoolean() ?: false
        if (settings != (kind != SettingsKind.NONE)) return null
        val fontEntries = AndroidRestoreFontPolicy.decode(values["fonts"].orEmpty()) ?: return null
        return ReadyMarker(
            stageId = stageId,
            library = values["library"]?.toBoolean() ?: false,
            settings = settings,
            account = values["account"]?.toBoolean() ?: false,
            settingsKind = kind,
            fontEntries = fontEntries,
            previousSettingsPresent = values["previous-settings"]?.toBoolean() ?: false,
        )
    }

    private fun deletePendingQueue(context: Context) {
        runCatching { context.filesDir.resolve(PERSISTENT_QUEUE_FILE).delete() }
    }

    private fun cleanupAfter(context: Context, ready: ReadyMarker?) {
        File(restoreRoot(context), READY_FILE).delete()
        if (ready == null) {
            cleanupOrphans(context)
            return
        }
        deleteTree(stageRoot(context, ready))
        val fontsDir = File(context.filesDir, BackupArchiveRepository.CUSTOM_FONTS_DIR_NAME)
        deleteTree(File(fontsDir, RESTORE_PENDING_DIR, "$STAGE_PREFIX${ready.stageId}"))
        deleteTree(File(fontsDir, RESTORE_PENDING_DIR, "$BACKUP_PREFIX${ready.stageId}"))
        val settingsDir = File(context.filesDir, "datastore")
        deleteTree(File(settingsDir, RESTORE_PENDING_DIR, "$STAGE_PREFIX${ready.stageId}"))
        deleteTree(File(settingsDir, RESTORE_PENDING_DIR, "$BACKUP_PREFIX${ready.stageId}"))
    }

    private fun cleanupOrphans(context: Context) {
        val root = restoreRoot(context)
        File(root, READY_FILE).delete()
        root.listFiles()?.filter { it.name.startsWith(STAGE_ROOT_PREFIX) }?.forEach(::deleteTree)
        deleteTree(File(context.filesDir, "${BackupArchiveRepository.CUSTOM_FONTS_DIR_NAME}/$RESTORE_PENDING_DIR"))
        deleteTree(File(context.filesDir, "datastore/$RESTORE_PENDING_DIR"))
        val fontsDir = File(context.filesDir, BackupArchiveRepository.CUSTOM_FONTS_DIR_NAME)
        fontsDir.listFiles()
            ?.filter { it.name.startsWith(".restore-staged-") || it.name.startsWith(".restore-previous-") }
            ?.forEach(::deleteTree)
        val settingsDir = File(context.filesDir, "datastore")
        settingsDir.listFiles()
            ?.filter { it.name.startsWith(".restore-staged-") || it.name.startsWith(".restore-previous-") }
            ?.forEach(::deleteTree)
    }

    private fun restoreRoot(context: Context): File =
        restoreRootPath(context).also(::ensureDirectory)

    private fun restoreRootPath(context: Context): File {
        val databaseDir = context.getDatabasePath(InternalDatabase.DB_NAME).parentFile
            ?: throw IllegalStateException("Database directory unavailable")
        return File(databaseDir, RESTORE_PENDING_DIR)
    }

    private fun blockedMarker(context: Context): File = File(restoreRootPath(context), BLOCKED_FILE)

    private fun stageRoot(context: Context, ready: ReadyMarker): File =
        File(restoreRoot(context), "$STAGE_ROOT_PREFIX${ready.stageId}")

    private fun checkEntrySize(entry: ZipEntry, limit: Long) {
        require(entry.size < 0L || entry.size <= limit) { "Restore entry is too large: ${entry.name}" }
    }

    private fun copyEntry(
        zip: ZipInputStream,
        target: File,
        entryName: String,
        totalBytes: Long,
        limit: Long,
    ): Long = copyBounded(zip, target, totalBytes, limit, entryName)

    private fun copyBounded(
        input: InputStream,
        target: File,
        totalBytes: Long,
        limit: Long,
        entryName: String,
    ): Long {
        ensureDirectory(target.parentFile ?: throw IllegalArgumentException("Restore entry has no parent"))
        var entryBytes = 0L
        var total = totalBytes
        FileOutputStream(target).use { output ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                entryBytes += read
                total += read
                require(entryBytes <= limit) { "Restore entry is too large: $entryName" }
                require(total <= AndroidRestoreLimits.MAX_TOTAL_BYTES) { "Restore payload is too large" }
                output.write(buffer, 0, read)
            }
            output.flush()
            output.fd.sync()
        }
        return total
    }

    private fun restorePreviousSettings(context: Context, ready: ReadyMarker) {
        restorePreviousSettingsFromSnapshot(
            ops = AndroidRestoreFileOps(),
            livePath = File(context.filesDir, "datastore/$SETTINGS_PB").path,
            snapshotPath = File(stageRoot(context, ready), SETTINGS_PREVIOUS_FILE).path,
            previousSettingsPresent = ready.previousSettingsPresent,
        )
    }

    private fun ensureDirectory(directory: File) {
        require(directory.isDirectory || directory.mkdirs()) { "Restore directory unavailable" }
    }

    private fun deleteTree(file: File) {
        if (file.exists()) file.deleteRecursively()
    }
}

internal object AndroidRestoreArchivePolicy {
    private const val DATABASE = "song.db"
    private const val SETTINGS_XML = "settings.xml"
    private const val SETTINGS_PB = "settings.preferences_pb"

    fun isDatabase(name: String): Boolean =
        name == DATABASE || name == "$DATABASE-wal" || name == "$DATABASE-shm" || name == "$DATABASE-journal"

    fun isSettingsXml(name: String): Boolean = name == SETTINGS_XML

    fun isSettingsPb(name: String): Boolean = name == SETTINGS_PB

    fun isFont(name: String): Boolean = isBackupFontEntry(name)

    fun isAllowed(name: String): Boolean =
        isDatabase(name) || isSettingsXml(name) || isSettingsPb(name) || isFont(name)

    fun fontName(name: String): String =
        name.removePrefix("${BackupArchiveRepository.FONTS_ZIP_PREFIX}/")

    fun canStage(readyExists: Boolean, journalExists: Boolean): Boolean =
        !readyExists && !journalExists
}

internal data class StagedRestore(
    val stageId: String,
    val library: Boolean,
    val settings: Boolean,
    val account: Boolean,
    val settingsKind: SettingsKind,
)

internal enum class SettingsKind {
    NONE,
    XML,
    PB,
}

internal class PendingRestoreException : IllegalStateException()

internal fun restorePreviousSettingsFromSnapshot(
    ops: RestoreFileOps,
    livePath: String,
    snapshotPath: String,
    previousSettingsPresent: Boolean,
) {
    val snapshot = ops.readBytes(snapshotPath)
    when (AndroidRestoreSettingsRollbackPolicy.decide(
        previousSettingsPresent = previousSettingsPresent,
        snapshotExists = snapshot != null,
    )) {
        AndroidRestoreSettingsRollbackAction.RESTORE_SNAPSHOT -> {
            val bytes = snapshot ?: throw IllegalStateException("Previous settings snapshot is missing")
            require(bytes.size in 1..AndroidRestoreLimits.MAX_SETTINGS_BYTES.toInt()) {
                "Previous settings snapshot is invalid"
            }
            ops.writeAtomically(livePath, bytes)
        }

        AndroidRestoreSettingsRollbackAction.DELETE_LIVE -> {
            if (ops.exists(livePath)) ops.delete(livePath)
        }

        AndroidRestoreSettingsRollbackAction.FAIL_CLOSED -> {
            throw IllegalStateException("Previous settings snapshot is inconsistent")
        }
    }
}

internal data class ReadyMarker(
    val stageId: String,
    val library: Boolean,
    val settings: Boolean,
    val account: Boolean,
    val settingsKind: SettingsKind,
    val fontEntries: List<AndroidRestoreFont>,
    val previousSettingsPresent: Boolean,
)
