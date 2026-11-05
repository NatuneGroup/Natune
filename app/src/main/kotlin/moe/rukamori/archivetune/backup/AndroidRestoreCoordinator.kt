package moe.rukamori.archivetune.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.system.Os
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
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
    private const val STAGE_PREFIX = "stage-"
    private const val READY_FILE = "ready"
    private const val JOURNAL_FILE = "transaction"
    private const val READY_VERSION = "1"
    private const val SETTINGS_XML = "settings.xml"
    private const val SETTINGS_PB = "settings.preferences_pb"
    private const val FONT_STAGE_PREFIX = ".restore-staged-"
    private const val FONT_BACKUP_PREFIX = ".restore-previous-"
    private const val BUFFER_SIZE = 64 * 1024

    @Volatile
    private var blocked = false

    fun isBlocked(): Boolean = blocked

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
        val stageRoot = File(root, "$STAGE_PREFIX$stageId")
        val stagedDir = File(stageRoot, "staged")
        ensureDirectory(stagedDir)
        var readyWritten = false

        try {
            val fontsDir = File(context.filesDir, BackupArchiveRepository.CUSTOM_FONTS_DIR_NAME)
            val fontStage = File(fontsDir, "$FONT_STAGE_PREFIX$stageId")
            val settingsDir = File(context.filesDir, "datastore")
            val pbStage = File(settingsDir, "$FONT_STAGE_PREFIX$stageId")
            val settingsExcludedKeys =
                if (includeAccount) emptySet() else BackupArchiveRepository.ACCOUNT_PREFERENCE_KEYS
            var hasDb = false
            var settingsKind = SettingsKind.NONE
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
                        if (isDb && includeLibrary) {
                            if (name == InternalDatabase.DB_NAME) hasDb = true
                            copyEntry(zip, File(stagedDir, name))
                        } else if (isXml && includeSettings) {
                            if (settingsKind == SettingsKind.PB) throw IllegalArgumentException("Ambiguous settings payload")
                            settingsKind = SettingsKind.XML
                            copyEntry(zip, File(stagedDir, SETTINGS_XML))
                        } else if (isPb && includeSettings) {
                            if (settingsKind == SettingsKind.XML) throw IllegalArgumentException("Ambiguous settings payload")
                            settingsKind = SettingsKind.PB
                            ensureDirectory(pbStage)
                            copyEntry(zip, File(pbStage, SETTINGS_PB))
                        } else if (isFont && includeSettings) {
                            ensureDirectory(fontStage)
                            copyEntry(zip, File(fontStage, AndroidRestoreArchivePolicy.fontName(name)))
                        }
                        entry = zip.nextEntry
                    }
                }
            } ?: throw IllegalArgumentException("Restore archive unavailable")

            require(seen.isNotEmpty()) { "Empty restore archive" }
            require(!includeLibrary || hasDb) { "Backup missing database" }
            require(!includeSettings || settingsKind != SettingsKind.NONE) { "Backup missing settings" }

            val ready = ReadyMarker(
                stageId = stageId,
                library = includeLibrary,
                settings = includeSettings,
                account = includeAccount,
                settingsKind = settingsKind,
            )
            val targets = buildTargets(context, ready)
            preflightTargets(targets)
            if (includeLibrary) preflightDatabase(File(stagedDir, InternalDatabase.DB_NAME))
            if (settingsKind == SettingsKind.XML) {
                preflightXml(File(stagedDir, SETTINGS_XML), settingsExcludedKeys)
            } else if (settingsKind == SettingsKind.PB) {
                preflightPb(File(pbStage, SETTINGS_PB))
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
                deleteTree(File(fontsDir, "$FONT_STAGE_PREFIX$stageId"))
                deleteTree(File(fontsDir, "$FONT_BACKUP_PREFIX$stageId"))
                val settingsDir = File(context.filesDir, "datastore")
                deleteTree(File(settingsDir, "$FONT_STAGE_PREFIX$stageId"))
                deleteTree(File(settingsDir, "$FONT_BACKUP_PREFIX$stageId"))
            }
        }
    }

    /**
     * Applies a ready stage or finishes an interrupted transaction before any application owner opens
     * Room or DataStore. A recover failure is sticky for this process and makes [App] show the
     * recovery screen instead of entering the normal graph.
     */
    fun recoverAtStartup(context: Context) {
        try {
            runBlocking(Dispatchers.IO) {
            val ops = AndroidRestoreFileOps()
            val root = restoreRoot(context)
            val journal = File(root, JOURNAL_FILE)
            val ready = readReady(context)
            val outcome = try {
                RestoreTransaction.recover(ops, journal.path)
            } catch (_: Exception) {
                blocked = true
                return@runBlocking
            }
            when (outcome) {
                RecoveryOutcome.NOTHING ->
                    if (ready != null) {
                        if (hasRequiredStage(context, ready)) commit(context, ops, ready) else cleanupAfter(context, ready)
                    } else {
                        cleanupOrphans(context)
                    }

                RecoveryOutcome.ROLLED_BACK -> {
                    cleanupAfter(context, ready)
                }

                RecoveryOutcome.COMMITTED -> {
                    val transaction = RestoreTransaction.resume(ops, journal.path)
                    if (transaction == null) {
                        blocked = true
                    } else {
                        try {
                            transaction.finalize()
                            deletePendingQueue(context)
                            cleanupAfter(context, ready)
                        } catch (_: Exception) {
                            blocked = true
                        }
                    }
                }

                RecoveryOutcome.PENDING_APPLY -> {
                    if (ready?.settingsKind != SettingsKind.XML) {
                        blocked = true
                    } else {
                        resumeApply(context, ops, ready, journal.path)
                    }
                }

                RecoveryOutcome.CORRUPT -> blocked = true
            }
            }
        } catch (_: Exception) {
            blocked = true
        } catch (_: LinkageError) {
            blocked = true
        }
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
            cleanupAfter(context, ready)
            return
        }
        val transaction = RestoreTransaction(ops, journal.path, targets)
        try {
            transaction.commit(retainBackups = ready.settingsKind == SettingsKind.XML)
        } catch (_: Exception) {
            if (journal.exists()) blocked = true else cleanupAfter(context, ready)
            return
        }
        if (ready.settingsKind == SettingsKind.XML) {
            try {
                applyXml(context, ready)
            } catch (_: Exception) {
                try {
                    transaction.rollbackCommitted()
                    cleanupAfter(context, ready)
                } catch (_: Exception) {
                    blocked = true
                }
                return
            }
        }
        try {
            transaction.finalize()
            deletePendingQueue(context)
            cleanupAfter(context, ready)
        } catch (_: Exception) {
            blocked = true
        }
    }

    private suspend fun resumeApply(
        context: Context,
        ops: RestoreFileOps,
        ready: ReadyMarker,
        journalPath: String,
    ) {
        val transaction = RestoreTransaction.resume(ops, journalPath) ?: run {
            blocked = true
            return
        }
        try {
            applyXml(context, ready)
        } catch (_: Exception) {
            try {
                transaction.rollbackCommitted()
                cleanupAfter(context, ready)
            } catch (_: Exception) {
                blocked = true
            }
            return
        }
        try {
            transaction.finalize()
            deletePendingQueue(context)
            cleanupAfter(context, ready)
        } catch (_: Exception) {
            blocked = true
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
            val staged = File(settingsDir, "$FONT_STAGE_PREFIX${ready.stageId}")
            val previous = File(settingsDir, "$FONT_BACKUP_PREFIX${ready.stageId}")
            targets += RestoreTarget(
                livePath = File(settingsDir, SETTINGS_PB).path,
                stagedPath = File(staged, SETTINGS_PB).path,
                backupPath = File(previous, SETTINGS_PB).path,
            )
        }
        if (ready.settings) {
            val fontsDir = File(context.filesDir, BackupArchiveRepository.CUSTOM_FONTS_DIR_NAME)
            val staged = File(fontsDir, "$FONT_STAGE_PREFIX${ready.stageId}")
            val previous = File(fontsDir, "$FONT_BACKUP_PREFIX${ready.stageId}")
            staged.listFiles()?.filter { it.isFile }?.sortedBy { it.name }?.forEach { file ->
                targets += RestoreTarget(
                    livePath = File(fontsDir, file.name).path,
                    stagedPath = file.path,
                    backupPath = File(previous, file.name).path,
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
        require(file.isFile) { "Restore settings missing" }
        FileInputStream(file).use { input ->
            readLegacySettings(input, excludedKeys)
        }
    }

    private fun preflightPb(file: File) {
        require(file.isFile) { "Restore settings missing" }
        LegacyDataStorePreferences.read(file.readBytes())
    }

    private fun hasRequiredStage(context: Context, ready: ReadyMarker): Boolean {
        val staged = File(stageRoot(context, ready), "staged")
        if (ready.library && !File(staged, InternalDatabase.DB_NAME).isFile) return false
        if (ready.settingsKind == SettingsKind.XML && !File(staged, SETTINGS_XML).isFile) return false
        if (ready.settingsKind == SettingsKind.PB &&
            !File(File(context.filesDir, "datastore/$FONT_STAGE_PREFIX${ready.stageId}"), SETTINGS_PB).isFile
        ) {
            return false
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
        val stageId = values["stage"]?.takeIf { it.isNotBlank() } ?: return null
        val kind = values["kind"]?.let { runCatching { SettingsKind.valueOf(it) }.getOrNull() } ?: return null
        return ReadyMarker(
            stageId = stageId,
            library = values["library"]?.toBoolean() ?: false,
            settings = values["settings"]?.toBoolean() ?: false,
            account = values["account"]?.toBoolean() ?: false,
            settingsKind = kind,
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
        deleteTree(File(fontsDir, "$FONT_STAGE_PREFIX${ready.stageId}"))
        deleteTree(File(fontsDir, "$FONT_BACKUP_PREFIX${ready.stageId}"))
        val settingsDir = File(context.filesDir, "datastore")
        deleteTree(File(settingsDir, "$FONT_STAGE_PREFIX${ready.stageId}"))
        deleteTree(File(settingsDir, "$FONT_BACKUP_PREFIX${ready.stageId}"))
    }

    private fun cleanupOrphans(context: Context) {
        val root = restoreRoot(context)
        root.listFiles()?.filter { it.name.startsWith(STAGE_PREFIX) }?.forEach(::deleteTree)
        val fontsDir = File(context.filesDir, BackupArchiveRepository.CUSTOM_FONTS_DIR_NAME)
        fontsDir.listFiles()
            ?.filter { it.name.startsWith(FONT_STAGE_PREFIX) || it.name.startsWith(FONT_BACKUP_PREFIX) }
            ?.forEach(::deleteTree)
        val settingsDir = File(context.filesDir, "datastore")
        settingsDir.listFiles()
            ?.filter { it.name.startsWith(FONT_STAGE_PREFIX) || it.name.startsWith(FONT_BACKUP_PREFIX) }
            ?.forEach(::deleteTree)
    }

    private fun restoreRoot(context: Context): File {
        val databaseDir = context.getDatabasePath(InternalDatabase.DB_NAME).parentFile
            ?: throw IllegalStateException("Database directory unavailable")
        return File(databaseDir, "restore-pending").also(::ensureDirectory)
    }

    private fun stageRoot(context: Context, ready: ReadyMarker): File =
        File(restoreRoot(context), "$STAGE_PREFIX${ready.stageId}")

    private fun copyEntry(zip: ZipInputStream, target: File) {
        ensureDirectory(target.parentFile ?: throw IllegalArgumentException("Restore entry has no parent"))
        FileOutputStream(target).use { output ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = zip.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
            }
            output.flush()
            output.fd.sync()
        }
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

private data class ReadyMarker(
    val stageId: String,
    val library: Boolean,
    val settings: Boolean,
    val account: Boolean,
    val settingsKind: SettingsKind,
)
