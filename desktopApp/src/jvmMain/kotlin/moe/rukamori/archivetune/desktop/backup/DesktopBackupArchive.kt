package moe.rukamori.archivetune.desktop.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.shared.data.backup.AndroidBackupArchive
import moe.rukamori.archivetune.shared.data.backup.JvmBackupArchiveCodec
import moe.rukamori.archivetune.shared.data.backup.PortableBackupFormat
import moe.rukamori.archivetune.shared.data.backup.backupCategoriesOf
import moe.rukamori.archivetune.shared.data.backup.mergedRestoredSettings
import moe.rukamori.archivetune.shared.data.backup.settingsSubset
import moe.rukamori.archivetune.shared.data.persistence.AndroidSongDbExport
import moe.rukamori.archivetune.shared.data.persistence.BackupCategory
import moe.rukamori.archivetune.shared.data.persistence.BackupPreferencePolicy
import moe.rukamori.archivetune.shared.data.persistence.inspectPortableStateFile
import moe.rukamori.archivetune.shared.domain.backup.BackupArchiveException
import moe.rukamori.archivetune.shared.domain.backup.BackupArchiveGateway
import moe.rukamori.archivetune.shared.domain.backup.BackupArchiveInspection
import moe.rukamori.archivetune.shared.domain.backup.BackupArchiveProblem
import moe.rukamori.archivetune.shared.domain.backup.BackupDataCategory
import moe.rukamori.archivetune.shared.domain.backup.BackupRestoreOutcome
import moe.rukamori.archivetune.shared.domain.backup.DriveAppDataLimits
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.ui.settings.VaultSettingsStore

/**
 * The desktop backup archive is the shared [PortableBackupFormat] the portable hosts exchange. Android archives
 * carry the schema-36 `song.db`; the portable hosts keep their library in the portable state database, so they write
 * their own ZIP:
 *
 * - `backup.json`: the manifest (format name and version, platform, portable schema version, creation time).
 * - `portable-state.db`: a VACUUM INTO copy of the library, queue and history, present with LIBRARY.
 * - `settings.json`: typed logical preferences, filtered by [BackupPreferencePolicy] for ACCOUNT and SETTINGS.
 *
 * Every entry name, size and count is bounded before anything is written, and an archive is inspected again before a
 * restore. Vault references never enter the archive: account values are exported by their logical names and go back
 * through the vault on restore.
 */

/** What one archive holds after its entries were checked. The database, when present, sits at [database]. */
internal class ParsedDesktopArchive(val database: Path?, val settings: PreferenceSnapshot?) {
    /** The sections this archive can restore: LIBRARY with a database, ACCOUNT and SETTINGS by their preference names. */
    val categories: Set<BackupDataCategory> = backupCategoriesOf(database != null, settings)
}

/**
 * The desktop [BackupArchiveGateway]. Creating reads the logical settings and a database snapshot; restoring stages the
 * chosen sections next to the live files and asks for a restart, because the open database cannot be swapped under the
 * running queue and library. [DesktopPendingRestore] applies the staged sections at the next start, before any owner
 * opens them.
 */
internal class DesktopBackupArchiveGateway(
    private val settings: VaultSettingsStore,
    private val snapshotDatabase: suspend (Path) -> Unit,
    private val instanceDirectory: Path,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : BackupArchiveGateway {
    override suspend fun create(selected: Set<BackupDataCategory>): ByteArray {
        require(selected.isNotEmpty())
        return withScratch { scratch ->
            val database = if (BackupDataCategory.LIBRARY in selected) {
                scratch.resolve(PortableBackupFormat.DATABASE).also { snapshotDatabase(it) }
            } else {
                null
            }
            val preferences = settingsSubset(settings.snapshot(), selected)
            withContext(Dispatchers.IO) {
                if (database != null && Files.size(database) > PortableBackupFormat.MAX_DATABASE_BYTES) throw tooLarge()
                val output = BoundedByteOutput(DriveAppDataLimits.MAX_ARCHIVE_BYTES.toLong())
                ZipOutputStream(output).use { zip ->
                    zip.entry(PortableBackupFormat.MANIFEST) { it.write(PortableBackupFormat.manifest(PortableBackupFormat.PLATFORM_DESKTOP, nowMillis())) }
                    if (database != null) zip.entry(PortableBackupFormat.DATABASE) { Files.copy(database, it) }
                    if (preferences != null) {
                        val bytes = PortableBackupFormat.encodeSettings(preferences)
                        if (bytes.size > PortableBackupFormat.MAX_SETTINGS_BYTES) throw tooLarge()
                        zip.entry(PortableBackupFormat.SETTINGS) { it.write(bytes) }
                    }
                }
                output.toByteArray()
            }
        }
    }

    /**
     * An archive in the Android app's own format: `settings.xml` and a schema-39 `song.db` written from the library
     * snapshot by [AndroidSongDbExport]. The retained codec checks the result as the Android restore would.
     */
    suspend fun createAndroid(selected: Set<BackupDataCategory>): ByteArray {
        require(selected.isNotEmpty())
        return withScratch { scratch ->
            val libraryFiles = if (BackupDataCategory.LIBRARY in selected) {
                val snapshot = scratch.resolve(PortableBackupFormat.DATABASE).also { snapshotDatabase(it) }
                val songDb = scratch.resolve(ANDROID_SONG_DB)
                AndroidSongDbExport.export(snapshot, songDb, nowMillis())
                mapOf(ANDROID_SONG_DB to withContext(Dispatchers.IO) { Files.readAllBytes(songDb) })
            } else {
                emptyMap()
            }
            val preferences = settingsSubset(settings.snapshot(), selected) ?: PreferenceSnapshot.EMPTY
            val categories = selected.map { category ->
                when (category) {
                    BackupDataCategory.LIBRARY -> BackupCategory.LIBRARY
                    BackupDataCategory.ACCOUNT -> BackupCategory.ACCOUNT
                    BackupDataCategory.SETTINGS -> BackupCategory.SETTINGS
                }
            }.toSet()
            withContext(Dispatchers.IO) { JvmBackupArchiveCodec().create(preferences, categories, libraryFiles) }
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
            withContext(Dispatchers.IO + NonCancellable) {
                DesktopPendingRestore.stage(
                    instanceDirectory = instanceDirectory,
                    categories = chosen,
                    database = parsed.database.takeIf { BackupDataCategory.LIBRARY in chosen },
                    settings = parsed.settings?.let { settingsSubset(it, chosen) },
                )
            }
            BackupRestoreOutcome(restartRequired = true)
        }

    /** Reads and checks every entry. The database is written to [scratch] and inspected there. */
    private suspend fun parse(archive: ByteArray, scratch: Path): ParsedDesktopArchive = withContext(Dispatchers.IO) {
        if (archive.isEmpty() || archive.size > DriveAppDataLimits.MAX_ARCHIVE_BYTES) {
            throw BackupArchiveException(if (archive.isEmpty()) BackupArchiveProblem.NOT_A_BACKUP else BackupArchiveProblem.INSUFFICIENT_STORAGE)
        }
        val names = try {
            ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
                generateSequence { zip.nextEntry }.map { it.name }.toList()
            }
        } catch (_: ZipException) {
            throw BackupArchiveException(BackupArchiveProblem.NOT_A_BACKUP)
        }
        if (PortableBackupFormat.MANIFEST !in names) {
            // An Android archive carries song.db and settings.xml instead of the desktop manifest; it restores
            // through the retained codec and the schema-36 importer.
            return@withContext parseAndroid(archive, names, scratch)
        }
        var manifest: ByteArray? = null
        var database: Path? = null
        var settings: ByteArray? = null
        var entries = 0
        var total = 0L
        try {
            ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries += 1
                    if (entries > PortableBackupFormat.MAX_ENTRIES || entry.isDirectory) throw corrupted()
                    val limit = when (entry.name) {
                        PortableBackupFormat.MANIFEST -> PortableBackupFormat.MAX_MANIFEST_BYTES
                        PortableBackupFormat.SETTINGS -> PortableBackupFormat.MAX_SETTINGS_BYTES
                        PortableBackupFormat.DATABASE -> PortableBackupFormat.MAX_DATABASE_BYTES
                        // An Android archive (song.db, settings.xml) or any other name is not a desktop backup.
                        else -> throw BackupArchiveException(BackupArchiveProblem.NOT_A_BACKUP)
                    }
                    val remaining = PortableBackupFormat.MAX_TOTAL_BYTES - total
                    when (entry.name) {
                        PortableBackupFormat.MANIFEST -> {
                            if (manifest != null) throw corrupted()
                            manifest = zip.readBounded(minOf(limit, remaining))
                            total += manifest!!.size
                        }
                        PortableBackupFormat.SETTINGS -> {
                            if (settings != null) throw corrupted()
                            settings = zip.readBounded(minOf(limit, remaining))
                            total += settings!!.size
                        }
                        PortableBackupFormat.DATABASE -> {
                            if (database != null) throw corrupted()
                            val target = scratch.resolve(PortableBackupFormat.DATABASE)
                            total += Files.newOutputStream(target).use { zip.copyBounded(it, minOf(limit, remaining)) }
                            database = target
                        }
                    }
                }
            }
        } catch (rejected: BackupArchiveException) {
            throw rejected
        } catch (_: ZipException) {
            throw BackupArchiveException(BackupArchiveProblem.NOT_A_BACKUP)
        }
        PortableBackupFormat.checkManifest(manifest ?: throw BackupArchiveException(BackupArchiveProblem.NOT_A_BACKUP))
        database?.let { file ->
            try {
                inspectPortableStateFile(file)
            } catch (_: Exception) {
                throw corrupted()
            }
        }
        val preferences = settings?.let(PortableBackupFormat::decodeSettings)?.let { decoded ->
            // A key the policy never exports is refused at import too, so a crafted file cannot set vault metadata.
            if (decoded.keys.any { !BackupPreferencePolicy.includes(it.name, BackupCategory.entries.toSet()) }) throw corrupted()
            decoded
        }
        ParsedDesktopArchive(database, preferences).also {
            if (it.categories.isEmpty()) throw BackupArchiveException(BackupArchiveProblem.NO_RESTORABLE_DATA)
        }
    }

    /**
     * The Android `song.db` archive. The retained codec applies the baseline's bounds and validation, then
     * [LegacySongDbImport] converts the schema-36 library into the portable state database the restore stages like
     * a desktop archive. Custom fonts the Android archive carries are not restored, since the desktop keeps none.
     */
    private suspend fun parseAndroid(archive: ByteArray, names: List<String>, scratch: Path): ParsedDesktopArchive {
        if (!AndroidBackupArchive.recognizes(archive)) {
            throw BackupArchiveException(BackupArchiveProblem.NOT_A_BACKUP)
        }
        val converted = try {
            AndroidBackupArchive.convert(archive, scratch)
        } catch (typed: BackupArchiveException) {
            throw typed
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw corrupted()
        }
        return ParsedDesktopArchive(converted.database, converted.settings.takeIf { it.keys.isNotEmpty() }).also {
            if (it.categories.isEmpty()) throw BackupArchiveException(BackupArchiveProblem.NO_RESTORABLE_DATA)
        }
    }

    private suspend fun <T> withScratch(block: suspend (Path) -> T): T {
        val scratch = withContext(Dispatchers.IO) {
            Files.createDirectories(instanceDirectory.resolve(BACKUP_WORK_DIRECTORY)).resolve(UUID.randomUUID().toString()).also {
                Files.createDirectory(it)
            }
        }
        try {
            return block(scratch)
        } finally {
            withContext(Dispatchers.IO + NonCancellable) { deleteTree(scratch) }
        }
    }

    private fun ZipOutputStream.entry(name: String, write: (OutputStream) -> Unit) {
        putNextEntry(ZipEntry(name))
        write(this)
        closeEntry()
    }

    private fun InputStream.readBounded(limit: Long): ByteArray {
        val output = ByteArrayOutputStream()
        copyBounded(output, limit)
        return output.toByteArray()
    }

    private fun InputStream.copyBounded(output: OutputStream, limit: Long): Long {
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var copied = 0L
        while (true) {
            val read = read(buffer)
            if (read < 0) return copied
            copied += read
            if (copied > limit) throw tooLarge()
            output.write(buffer, 0, read)
        }
    }

    private fun corrupted() = BackupArchiveException(BackupArchiveProblem.CORRUPTED)

    private fun tooLarge() = BackupArchiveException(BackupArchiveProblem.INSUFFICIENT_STORAGE)

    /** Fails as soon as the archive would exceed the shared archive bound, instead of after building all of it. */
    private class BoundedByteOutput(private val limit: Long) : ByteArrayOutputStream() {
        override fun write(b: Int) {
            if (size() + 1L > limit) throw BackupArchiveException(BackupArchiveProblem.INSUFFICIENT_STORAGE)
            super.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (size().toLong() + len > limit) throw BackupArchiveException(BackupArchiveProblem.INSUFFICIENT_STORAGE)
            super.write(b, off, len)
        }
    }

    private companion object {
        const val COPY_BUFFER_BYTES = 64 * 1024
        const val ANDROID_SONG_DB = "song.db"
    }
}


/**
 * A restore staged by [DesktopBackupArchiveGateway.restore], applied by the next process before its owners open the
 * files. The staging directory is renamed into place only when complete, so a crash while staging leaves no pending
 * restore. Applying is idempotent: a restarted apply skips a database already moved and writes the settings again.
 */
internal class DesktopPendingRestore private constructor(
    private val directory: Path,
    private val categories: Set<BackupDataCategory>,
) {
    /** Live database files moved aside by [swapDatabase], by suffix. */
    private val movedAside = mutableListOf<String>()
    private var stagedInPlace = false

    /** True once the staged database is the live file, until [rollbackDatabase] puts the previous one back. */
    val databaseSwapped: Boolean get() = stagedInPlace

    /**
     * Moves the staged database into place and keeps the current one aside until [finish]. Call before the database
     * is opened. A failure part-way leaves what was moved aside for [rollbackDatabase].
     */
    fun swapDatabase(instanceDirectory: Path, databaseFile: String) {
        val staged = directory.resolve(PortableBackupFormat.DATABASE)
        if (BackupDataCategory.LIBRARY !in categories || !Files.exists(staged, LinkOption.NOFOLLOW_LINKS)) return
        val previous = Files.createDirectories(directory.resolve(PREVIOUS_DIRECTORY))
        DATABASE_SUFFIXES.forEach { suffix ->
            val live = instanceDirectory.resolve(databaseFile + suffix)
            if (Files.exists(live, LinkOption.NOFOLLOW_LINKS)) {
                Files.move(live, previous.resolve(databaseFile + suffix), StandardCopyOption.REPLACE_EXISTING)
                movedAside += suffix
            }
        }
        Files.move(staged, instanceDirectory.resolve(databaseFile), StandardCopyOption.ATOMIC_MOVE)
        stagedInPlace = true
    }

    /**
     * Puts the files that [swapDatabase] set aside back. The restored file and the WAL and shared-memory files Room made
     * for it go first, because a WAL left next to a different database would be replayed into it.
     */
    fun rollbackDatabase(instanceDirectory: Path, databaseFile: String) {
        if (stagedInPlace) {
            DATABASE_SUFFIXES.forEach { suffix -> Files.deleteIfExists(instanceDirectory.resolve(databaseFile + suffix)) }
            stagedInPlace = false
        }
        val previous = directory.resolve(PREVIOUS_DIRECTORY)
        movedAside.toList().forEach { suffix ->
            Files.move(previous.resolve(databaseFile + suffix), instanceDirectory.resolve(databaseFile + suffix),
                StandardCopyOption.REPLACE_EXISTING)
            movedAside -= suffix
        }
    }

    /**
     * Replaces the restored ACCOUNT and SETTINGS preferences through the vault, keeping every preference the archive
     * does not own (device-only keys, vault metadata and the sections that were not chosen).
     */
    suspend fun applySettings(settings: VaultSettingsStore) {
        val file = directory.resolve(PortableBackupFormat.SETTINGS)
        if (!withContext(Dispatchers.IO) { Files.exists(file, LinkOption.NOFOLLOW_LINKS) }) return
        val restored = PortableBackupFormat.decodeSettings(withContext(Dispatchers.IO) { Files.readAllBytes(file) })
        val owned = categories.mapNotNull { category ->
            when (category) {
                BackupDataCategory.ACCOUNT -> BackupCategory.ACCOUNT
                BackupDataCategory.SETTINGS -> BackupCategory.SETTINGS
                BackupDataCategory.LIBRARY -> null
            }
        }.toSet()
        if (owned.isEmpty()) return
        settings.replaceAll(mergedRestoredSettings(settings.snapshot(), restored, owned))
    }

    /** Removes the staged restore and the database it replaced. */
    fun finish() {
        deleteTree(directory)
    }

    companion object {
        private const val PENDING_DIRECTORY = "restore-pending"
        private const val MARKER = "restore.json"
        private const val PREVIOUS_DIRECTORY = "previous"
        private val DATABASE_SUFFIXES = listOf("", "-wal", "-shm", "-journal")

        /** The restore staged by an earlier process, or null when none is waiting. A malformed one is discarded. */
        fun read(instanceDirectory: Path): DesktopPendingRestore? {
            // Staging folders and archive scratch space left by a process that stopped mid-way hold nothing to apply.
            Files.list(instanceDirectory).use { entries ->
                entries.filter { it.fileName.toString().startsWith("$PENDING_DIRECTORY.") }.forEach(::deleteTree)
            }
            deleteTree(instanceDirectory.resolve(BACKUP_WORK_DIRECTORY))
            val directory = instanceDirectory.resolve(PENDING_DIRECTORY)
            val marker = directory.resolve(MARKER)
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return null
            val categories = try {
                PortableBackupFormat.decodePendingMarker(Files.readAllBytes(marker))
            } catch (_: Exception) {
                null
            }
            if (categories == null) {
                deleteTree(directory)
                return null
            }
            return DesktopPendingRestore(directory, categories)
        }

        fun stage(
            instanceDirectory: Path,
            categories: Set<BackupDataCategory>,
            database: Path?,
            settings: PreferenceSnapshot?,
        ) {
            val target = instanceDirectory.resolve(PENDING_DIRECTORY)
            val staging = instanceDirectory.resolve("$PENDING_DIRECTORY.${UUID.randomUUID()}")
            try {
                Files.createDirectory(staging)
                database?.let { Files.move(it, staging.resolve(PortableBackupFormat.DATABASE)) }
                settings?.let { Files.write(staging.resolve(PortableBackupFormat.SETTINGS), PortableBackupFormat.encodeSettings(it)) }
                Files.write(staging.resolve(MARKER), PortableBackupFormat.encodePendingMarker(categories))
                deleteTree(target)
                Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE)
            } catch (failure: IOException) {
                deleteTree(staging)
                throw BackupArchiveException(BackupArchiveProblem.INSUFFICIENT_STORAGE, failure)
            }
        }
    }
}

/** Scratch space for archives being built or inspected, inside the instance directory so moves stay atomic. */
private const val BACKUP_WORK_DIRECTORY = "backup-work"

private fun deleteTree(root: Path) {
    if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
    Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
}
