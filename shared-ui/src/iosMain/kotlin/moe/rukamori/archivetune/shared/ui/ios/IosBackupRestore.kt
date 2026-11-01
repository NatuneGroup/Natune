@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.ui.ios

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.shared.data.backup.PortableBackupArchive
import moe.rukamori.archivetune.shared.data.backup.PortableBackupFormat
import moe.rukamori.archivetune.shared.data.backup.RestoreJournal
import moe.rukamori.archivetune.shared.data.backup.mergedRestoredSettings
import moe.rukamori.archivetune.shared.data.backup.readPortableFileBytes
import moe.rukamori.archivetune.shared.data.backup.settingsSubset
import moe.rukamori.archivetune.shared.data.backup.syncPortableDirectory
import moe.rukamori.archivetune.shared.data.backup.writePortableFileBytes
import moe.rukamori.archivetune.shared.data.persistence.BackupCategory
import moe.rukamori.archivetune.shared.data.persistence.inspectPortableStateFile
import moe.rukamori.archivetune.shared.data.platform.ios.iosNowMillis
import moe.rukamori.archivetune.shared.domain.backup.BackupArchiveException
import moe.rukamori.archivetune.shared.domain.backup.BackupArchiveGateway
import moe.rukamori.archivetune.shared.domain.backup.BackupArchiveInspection
import moe.rukamori.archivetune.shared.domain.backup.BackupArchiveProblem
import moe.rukamori.archivetune.shared.domain.backup.BackupDataCategory
import moe.rukamori.archivetune.shared.domain.backup.BackupRestoreOutcome
import moe.rukamori.archivetune.shared.domain.backup.InMemoryPreparedRestoreRegistry
import moe.rukamori.archivetune.shared.domain.backup.PreparedRestoreRegistry
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.ui.settings.BackupSelectionCategory
import moe.rukamori.archivetune.shared.ui.settings.VaultSettingsStore
import platform.Foundation.NSFileManager
import platform.Foundation.NSUUID

/** Scratch space for archives being built or inspected, inside the instance directory so moves stay atomic. */
private const val BACKUP_WORK_DIRECTORY = "backup-work"

/** What one archive holds after its entries were checked; the database was written to [database] and inspected there. */
private class ParsedIosArchive(
    val database: String?,
    val settings: PreferenceSnapshot?,
    categories: Set<BackupDataCategory>,
) {
    val categories: Set<BackupDataCategory> = categories
}

/**
 * The iOS [BackupArchiveGateway]. Creating reads the logical settings and a database snapshot; restoring stages the
 * chosen sections next to the live files and asks for a restart, because the open database cannot be swapped under the
 * running queue and library. [IosPendingRestore] applies the staged sections at the next start, before any owner opens
 * them. The archive itself is the shared [PortableBackupArchive], byte-compatible with the desktop host.
 */
internal class IosBackupGateway(
    private val settings: VaultSettingsStore,
    private val snapshotDatabase: suspend (String) -> Unit,
    private val instanceDirectory: String,
    private val nowMillis: () -> Long,
) : BackupArchiveGateway {
    override suspend fun create(selected: Set<BackupDataCategory>): ByteArray {
        require(selected.isNotEmpty())
        return withScratch { scratch ->
            val database = if (BackupDataCategory.LIBRARY in selected) {
                val path = "$scratch/${PortableBackupFormat.DATABASE}"
                snapshotDatabase(path)
                // The same bound the desktop writer enforces on the snapshot before it enters the archive.
                readPortableFileBytes(path, PortableBackupFormat.MAX_DATABASE_BYTES)
            } else {
                null
            }
            val preferences = settingsSubset(settings.snapshot(), selected)
            PortableBackupArchive.create(PortableBackupFormat.PLATFORM_IOS, database, preferences, nowMillis())
        }
    }

    override suspend fun inspect(archive: ByteArray): BackupArchiveInspection =
        withScratch { scratch -> BackupArchiveInspection(parse(archive, scratch).categories) }

    /**
     * Stages the [selected] sections and reports that a restart applies them. A previously staged restore is replaced
     * only after the new one is complete, so a failure here leaves the earlier choice, or nothing, in place.
     */
    override suspend fun restore(archive: ByteArray, selected: Set<BackupDataCategory>): BackupRestoreOutcome =
        withScratch { scratch ->
            val parsed = parse(archive, scratch)
            val chosen = selected intersect parsed.categories
            if (chosen.isEmpty()) throw BackupArchiveException(BackupArchiveProblem.NO_RESTORABLE_DATA)
            withContext(NonCancellable) {
                IosPendingRestore.stage(
                    instanceDirectory = instanceDirectory,
                    categories = chosen,
                    database = parsed.database.takeIf { BackupDataCategory.LIBRARY in chosen },
                    settings = parsed.settings?.let { settingsSubset(it, chosen) },
                )
            }
            BackupRestoreOutcome(restartRequired = true)
        }

    /**
     * The shared checks of [PortableBackupArchive.parse], plus the database inspection the desktop parser runs: the
     * staged file must be an intact portable state file this build can migrate before the archive is trusted.
     * Archives in the Android format carry no manifest, so they are refused as not-a-backup on this host.
     */
    private fun parse(archive: ByteArray, scratch: String): ParsedIosArchive {
        val parsed = PortableBackupArchive.parse(archive)
        val database = parsed.database?.let { bytes ->
            val path = "$scratch/${PortableBackupFormat.DATABASE}"
            writePortableFileBytes(path, bytes)
            try {
                inspectPortableStateFile(path)
            } catch (_: Exception) {
                throw BackupArchiveException(BackupArchiveProblem.CORRUPTED)
            }
            path
        }
        return ParsedIosArchive(database, parsed.settings, parsed.categories)
    }

    private suspend fun <T> withScratch(block: suspend (String) -> T): T {
        val scratch = "$instanceDirectory/$BACKUP_WORK_DIRECTORY/" + NSUUID().UUIDString
        NSFileManager.defaultManager.createDirectoryAtPath(scratch, true, null, null)
        try {
            return block(scratch)
        } finally {
            NSFileManager.defaultManager.removeItemAtPath(scratch, null)
        }
    }
}

/**
 * A restore staged by [IosBackupGateway.restore], applied by the next process before its owners open the files. The
 * staging directory is renamed into place only when complete, so a crash while staging leaves no pending restore.
 * Every swap step journals itself through [RestoreJournal] before the move it names, so a killed process finds the
 * interrupted swap, completes it and can still put back the files `previous/` kept. Applying is idempotent: a
 * restarted apply skips files already moved and writes the settings again.
 */
internal class IosPendingRestore private constructor(
    private val directory: String,
    private val categories: Set<BackupDataCategory>,
    private val journal: RestoreJournal,
) {
    /** True once the staged database is the live file, until [rollbackDatabase] puts the previous one back. */
    val databaseSwapped: Boolean get() = journal.swapped

    /** A swap or apply step failed and [markFailed] moved the pending set aside; nothing may apply it again. */
    var failed = false
        private set

    /**
     * Moves the staged database into place and keeps the current one aside until [finish]. Call before the database
     * is opened. Each step journals first, so re-entering after a kill continues where the last process stopped:
     * a live file is moved aside only while `previous/` does not already hold it, and a staged file still present
     * is promoted even when the journal already says swapped.
     */
    fun swapDatabase(instanceDirectory: String, databaseFile: String) {
        if (BackupDataCategory.LIBRARY !in categories) return
        val previous = "$directory/$PREVIOUS_DIRECTORY"
        ensureDirectory(previous)
        DATABASE_SUFFIXES.forEach { suffix ->
            val live = "$instanceDirectory/$databaseFile$suffix"
            val aside = "$previous/$databaseFile$suffix"
            if (existsAt(live) && !existsAt(aside)) {
                journal.noteMoved(suffix)
                persistJournal()
                moveItem(live, aside, replace = true)
            }
        }
        val staged = "$directory/${PortableBackupFormat.DATABASE}"
        if (existsAt(staged)) {
            if (!journal.swapped) {
                journal.swapped = true
                persistJournal()
            }
            moveItem(staged, "$instanceDirectory/$databaseFile", replace = true)
        }
        // A kill between the swapped mark and the promote leaves neither file live; the previous file is the one
        // consistent state to open, so it goes back before Room would create an empty database.
        if (journal.swapped && !existsAt("$instanceDirectory/$databaseFile") &&
            existsAt("$previous/$databaseFile")
        ) {
            moveItem("$previous/$databaseFile", "$instanceDirectory/$databaseFile", replace = true)
            journal.movedAside.remove("")
            persistJournal()
        }
        syncPortableDirectory(instanceDirectory)
    }

    /**
     * Puts the files that [swapDatabase] set aside back. The restored file and the WAL and shared-memory files Room
     * made for it go first, because a WAL left next to a different database would be replayed into it. The journal
     * decides what is aside, so a rollback works in a process that never saw the swap.
     */
    fun rollbackDatabase(instanceDirectory: String, databaseFile: String) {
        if (journal.swapped) {
            DATABASE_SUFFIXES.forEach { suffix -> deleteTree("$instanceDirectory/$databaseFile$suffix") }
            journal.swapped = false
            persistJournal()
        }
        val previous = "$directory/$PREVIOUS_DIRECTORY"
        journal.movedAside.toList().forEach { suffix ->
            val aside = "$previous/$databaseFile$suffix"
            if (existsAt(aside)) {
                moveItem(aside, "$instanceDirectory/$databaseFile$suffix", replace = true)
            }
            journal.movedAside.remove(suffix)
            persistJournal()
        }
        syncPortableDirectory(instanceDirectory)
    }

    /**
     * Replaces the restored ACCOUNT and SETTINGS preferences through the vault, keeping every preference the archive
     * does not own (device-only keys, vault metadata and the sections that were not chosen). The current snapshot is
     * staged into `previous/` first, so [rollbackSettings] can put back exactly what the merge replaced.
     */
    suspend fun applySettings(settings: VaultSettingsStore) {
        val file = "$directory/${PortableBackupFormat.SETTINGS}"
        if (!existsAt(file)) return
        val owned = categories.mapNotNull { category ->
            when (category) {
                BackupDataCategory.ACCOUNT -> BackupCategory.ACCOUNT
                BackupDataCategory.SETTINGS -> BackupCategory.SETTINGS
                BackupDataCategory.LIBRARY -> null
            }
        }.toSet()
        if (owned.isEmpty()) return
        val restored = PortableBackupFormat.decodeSettings(
            readPortableFileBytes(file, PortableBackupFormat.MAX_SETTINGS_BYTES),
        )
        val current = settings.snapshot()
        if (!journal.settingsStaged) {
            val previous = "$directory/$PREVIOUS_DIRECTORY"
            ensureDirectory(previous)
            writePortableFileBytes("$previous/${PortableBackupFormat.SETTINGS}", PortableBackupFormat.encodeSettings(current))
            journal.settingsStaged = true
            persistJournal()
        }
        settings.replaceAll(mergedRestoredSettings(current, restored, owned))
    }

    /** Puts back the settings snapshot [applySettings] staged, when it ran. Safe to call when it did not. */
    suspend fun rollbackSettings(settings: VaultSettingsStore) {
        if (!journal.settingsStaged) return
        val file = "$directory/$PREVIOUS_DIRECTORY/${PortableBackupFormat.SETTINGS}"
        if (existsAt(file)) {
            settings.replaceAll(
                PortableBackupFormat.decodeSettings(readPortableFileBytes(file, PortableBackupFormat.MAX_SETTINGS_BYTES)),
            )
        }
        journal.settingsStaged = false
        persistJournal()
    }

    /** Removes the staged restore and the files it replaced; only a fully applied restore reaches this. */
    fun finish() {
        deleteTree(directory)
    }

    /**
     * Keeps the pending set for diagnosis under [FAILED_DIRECTORY], which [read] never applies. The previous files
     * it holds are the rollback a failed restore owed the user, so they are kept rather than deleted.
     */
    fun markFailed() {
        failed = true
        val parent = directory.substringBeforeLast('/')
        val failed = "$parent/$FAILED_DIRECTORY"
        deleteTree(failed)
        moveItem(directory, failed, replace = false)
    }

    /** The journal rewritten whole under a work name, synced and renamed: every step is atomic and durable. */
    private fun persistJournal() {
        writePortableFileBytes("$directory/$JOURNAL.work", journal.encode().encodeToByteArray())
        moveItem("$directory/$JOURNAL.work", "$directory/$JOURNAL", replace = true)
        syncPortableDirectory(directory)
    }

    companion object {
        private const val PENDING_DIRECTORY = "restore-pending"
        private const val FAILED_DIRECTORY = "restore-failed"
        private const val MARKER = "restore.json"
        private const val JOURNAL = "journal"
        private const val PREVIOUS_DIRECTORY = "previous"
        private val DATABASE_SUFFIXES = listOf("", "-wal", "-shm", "-journal")

        /** The restore staged by an earlier process, or null when none is waiting. A malformed one is discarded. */
        fun read(instanceDirectory: String): IosPendingRestore? {
            // Staging folders and archive scratch space left by a process that stopped mid-way hold nothing to apply.
            childrenOf(instanceDirectory)
                .filter { it.startsWith("$PENDING_DIRECTORY.") }
                .forEach { deleteTree("$instanceDirectory/$it") }
            deleteTree("$instanceDirectory/$BACKUP_WORK_DIRECTORY")
            // A path that is not a directory fails the marker read below, which discards it the same way.
            val directory = "$instanceDirectory/$PENDING_DIRECTORY"
            if (!existsAt(directory)) return null
            deleteTree("$directory/$JOURNAL.work")
            val categories = try {
                PortableBackupFormat.decodePendingMarker(
                    readPortableFileBytes("$directory/$MARKER", PortableBackupFormat.MAX_MANIFEST_BYTES),
                )
            } catch (_: Exception) {
                null
            }
            if (categories == null) {
                deleteTree(directory)
                return null
            }
            // A journal that does not parse is treated as none: the file names under previous/ still gate every
            // move, so a missing or malformed journal degrades to a fresh swap, never to a wrong rollback.
            val journal = try {
                if (existsAt("$directory/$JOURNAL")) {
                    RestoreJournal.decode(
                        readPortableFileBytes("$directory/$JOURNAL", PortableBackupFormat.MAX_MANIFEST_BYTES)
                            .decodeToString(),
                    )
                } else {
                    RestoreJournal()
                }
            } catch (_: Exception) {
                RestoreJournal()
            }
            return IosPendingRestore(directory, categories, journal)
        }

        fun stage(
            instanceDirectory: String,
            categories: Set<BackupDataCategory>,
            database: String?,
            settings: PreferenceSnapshot?,
        ) {
            val target = "$instanceDirectory/$PENDING_DIRECTORY"
            val staging = "$instanceDirectory/$PENDING_DIRECTORY." + NSUUID().UUIDString
            try {
                ensureDirectory(staging)
                database?.let { moveItem(it, "$staging/${PortableBackupFormat.DATABASE}", replace = false) }
                settings?.let {
                    writePortableFileBytes(
                        "$staging/${PortableBackupFormat.SETTINGS}",
                        PortableBackupFormat.encodeSettings(it),
                    )
                }
                writePortableFileBytes("$staging/$MARKER", PortableBackupFormat.encodePendingMarker(categories))
                deleteTree(target)
                moveItem(staging, target, replace = false)
                syncPortableDirectory(instanceDirectory)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                deleteTree(staging)
                throw BackupArchiveException(BackupArchiveProblem.INSUFFICIENT_STORAGE, failure)
            }
        }
    }
}

/**
 * The backup owner of this process: the archive gateway, the one prepared-restore registry manual files and any later
 * cloud path share, and the restart request a staged restore raises. A restart dialog is this host's whole restart
 * mechanism; iOS offers no relaunch, so the user restarts the app and the pending restore applies at open.
 */
internal class IosBackupOwner(
    settings: VaultSettingsStore,
    snapshotDatabase: suspend (String) -> Unit,
    instanceDirectory: String,
    nowMillis: () -> Long = ::iosNowMillis,
    initialRestoreFailed: Boolean = false,
) {
    val archives = IosBackupGateway(settings, snapshotDatabase, instanceDirectory, nowMillis)
    val restores: PreparedRestoreRegistry = InMemoryPreparedRestoreRegistry(archives)
    private val restarting = MutableStateFlow(false)
    private val failedRestore = MutableStateFlow(initialRestoreFailed)

    /** True after a restore was staged; the settings page offers the restart dialog until the user answers. */
    val restartRequested: StateFlow<Boolean> = restarting.asStateFlow()

    /** True when the staged restore failed at open; the page reports it until the user answers. */
    val restoreFailed: StateFlow<Boolean> = failedRestore.asStateFlow()

    /**
     * Applies the selected sections of a prepared archive. Wire it to the page's restore callback; a failure leaves
     * the handle registered for a retry, and a restore that completed and asked for a restart raises the flag.
     */
    suspend fun restorePrepared(handle: String, selected: Set<BackupSelectionCategory>) {
        val outcome = restores.restore(handle, selected.map { it.toDataCategory() }.toSet())
        if (outcome.restartRequired) restarting.value = true
    }

    /** Releases a prepared archive without applying it. Wire it to the page's discard callback. */
    suspend fun discardPrepared(handle: String) {
        restores.discard(handle)
    }

    fun dismissRestart() {
        restarting.value = false
    }

    fun dismissRestoreFailed() {
        failedRestore.value = false
    }
}

private fun ensureDirectory(path: String) {
    NSFileManager.defaultManager.createDirectoryAtPath(path, true, null, null)
}

private fun existsAt(path: String): Boolean = NSFileManager.defaultManager.fileExistsAtPath(path)

private fun deleteTree(path: String) {
    NSFileManager.defaultManager.removeItemAtPath(path, null)
}

private fun childrenOf(path: String): List<String> =
    NSFileManager.defaultManager.contentsOfDirectoryAtPath(path, null)?.mapNotNull { it as? String }.orEmpty()

/** Renames [from] to [to]; with [replace] the destination is removed first, matching the desktop's REPLACE_EXISTING. */
private fun moveItem(from: String, to: String, replace: Boolean) {
    if (replace) deleteTree(to)
    check(NSFileManager.defaultManager.moveItemAtPath(from, to, null)) { "File move failed" }
}

internal fun BackupSelectionCategory.toDataCategory(): BackupDataCategory = when (this) {
    BackupSelectionCategory.LIBRARY -> BackupDataCategory.LIBRARY
    BackupSelectionCategory.ACCOUNT -> BackupDataCategory.ACCOUNT
    BackupSelectionCategory.SETTINGS -> BackupDataCategory.SETTINGS
}

internal fun BackupDataCategory.toSelection(): BackupSelectionCategory = when (this) {
    BackupDataCategory.LIBRARY -> BackupSelectionCategory.LIBRARY
    BackupDataCategory.ACCOUNT -> BackupDataCategory.ACCOUNT
    BackupDataCategory.SETTINGS -> BackupSelectionCategory.SETTINGS
}
