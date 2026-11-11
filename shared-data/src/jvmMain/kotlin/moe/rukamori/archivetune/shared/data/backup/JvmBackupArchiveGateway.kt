package moe.rukamori.archivetune.shared.data.backup

import java.io.ByteArrayInputStream
import java.nio.file.Path
import java.util.zip.ZipInputStream
import kotlin.concurrent.Volatile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.shared.data.persistence.BackupCategory
import moe.rukamori.archivetune.shared.domain.backup.BackupArchiveException
import moe.rukamori.archivetune.shared.domain.backup.BackupArchiveGateway
import moe.rukamori.archivetune.shared.domain.backup.BackupArchiveInspection
import moe.rukamori.archivetune.shared.domain.backup.BackupArchiveProblem
import moe.rukamori.archivetune.shared.domain.backup.BackupDataCategory
import moe.rukamori.archivetune.shared.domain.backup.BackupRestoreOutcome
import moe.rukamori.archivetune.shared.domain.backup.DriveAppDataLimits
import moe.rukamori.archivetune.shared.domain.persistence.RestoreLifecycle
import moe.rukamori.archivetune.shared.domain.persistence.RestorePreferences

/**
 * Host reads for archive creation. They run inside [RestoreLifecycle.withExclusiveAccess], so database and font
 * writers are quiesced while the snapshot is taken. There is deliberately no empty default: a host that cannot
 * read its files must fail the backup instead of writing an archive without them.
 */
interface BackupSnapshotSource {
    /** Checkpointed library files keyed by archive name: song.db plus any WAL, shared-memory or journal sidecars. */
    suspend fun libraryFiles(): Map<String, ByteArray>

    /** Custom font files keyed by their file name under fonts/. */
    suspend fun fontFiles(): Map<String, ByteArray>
}

/**
 * Binds [BackupArchiveGateway] to the committed JVM codec and transactional restore. Create exactly one instance per
 * process and share it with the prepared-restore registry. A fatal rollback failure is remembered by the instance
 * that hit it, so a second instance would not see it.
 *
 * The codec validates every selected section. Inspection derives restore choices from its parsed preference names
 * and validated library/font entries, so a settings payload does not imply that credentials are present.
 * Restart is requested for every applied section because library and settings replacement can leave in-memory
 * caches and restart-scoped preferences stale.
 */
class JvmBackupArchiveGateway(
    databaseDirectory: Path,
    fontsDirectory: Path,
    private val preferences: RestorePreferences,
    private val lifecycle: RestoreLifecycle,
    private val snapshots: BackupSnapshotSource,
) : BackupArchiveGateway {
    private val codec = JvmBackupArchiveCodec()
    private val transactionalRestore = JvmTransactionalRestore(databaseDirectory, fontsDirectory, preferences, lifecycle)

    /** Set when a rollback could not restore the originals. Create and restore refuse work until explicit recovery. */
    @Volatile
    private var quiescedBy: RestoreRollbackFailure? = null

    override suspend fun create(selected: Set<BackupDataCategory>): ByteArray {
        quiescedBy?.let { throw it }
        val categories = selected.toBackupCategories()
        return withContext(Dispatchers.IO) {
            lifecycle.withExclusiveAccess {
                val libraryFiles = if (BackupCategory.LIBRARY in categories) snapshots.libraryFiles() else emptyMap()
                val fonts = if (BackupCategory.SETTINGS in categories) snapshots.fontFiles() else emptyMap()
                // Pre-check of the library and font bytes against the expanded bound. The codec re-checks the exact
                // total, including settings.xml, against its own bound.
                val expanded = libraryFiles.values.sumOf { it.size.toLong() } + fonts.values.sumOf { it.size.toLong() }
                if (expanded > MAX_EXPANDED_BYTES) {
                    throw BackupArchiveException(BackupArchiveProblem.INSUFFICIENT_STORAGE)
                }
                val archive = codec.create(preferences.snapshot(), categories, libraryFiles, fonts)
                // Refused here rather than written and then rejected on restore, because restore applies the same bound.
                if (archive.size > DriveAppDataLimits.MAX_ARCHIVE_BYTES) {
                    throw BackupArchiveException(BackupArchiveProblem.INSUFFICIENT_STORAGE)
                }
                archive
            }
        }
    }

    override suspend fun inspect(archive: ByteArray): BackupArchiveInspection = withContext(Dispatchers.IO) {
        // The compressed bound every route shares, so a file restore and a cloud restore accept the same archives.
        if (archive.size > DriveAppDataLimits.MAX_ARCHIVE_BYTES) {
            throw BackupArchiveException(BackupArchiveProblem.INSUFFICIENT_STORAGE)
        }
        val names = try {
            archiveEntryNames(archive)
        } catch (unreadable: Exception) {
            throw BackupArchiveException(BackupArchiveProblem.NOT_A_BACKUP, unreadable)
        }
        if (names.isEmpty()) throw BackupArchiveException(BackupArchiveProblem.NOT_A_BACKUP)
        val sections = sectionsIn(names)
        val prepared = if (sections.isNotEmpty()) validate(archive, sections) else null
        BackupArchiveInspection(buildSet {
            if (prepared != null) {
                addAll(backupCategoriesOf(LIBRARY_DATABASE in prepared.libraryFiles, prepared.settings))
                if (prepared.fonts.isNotEmpty()) add(BackupDataCategory.SETTINGS)
            }
        })
    }

    override suspend fun restore(archive: ByteArray, selected: Set<BackupDataCategory>): BackupRestoreOutcome {
        quiescedBy?.let { throw it }
        // Refused before anything is applied, so an oversized archive never starts a partial restore.
        if (archive.size > DriveAppDataLimits.MAX_ARCHIVE_BYTES) {
            throw BackupArchiveException(BackupArchiveProblem.INSUFFICIENT_STORAGE)
        }
        val categories = selected.toBackupCategories()
        try {
            transactionalRestore.restore(archive, categories)
        } catch (fatal: RestoreRollbackFailure) {
            quiescedBy = fatal
            throw fatal
        }
        return BackupRestoreOutcome(restartRequired = categories.isNotEmpty())
    }

    /** Validates the sections the names point at. The codec is the only content validator. */
    private fun validate(archive: ByteArray, sections: Set<BackupCategory>): PreparedBackup {
        return try {
            codec.prepare(archive, sections)
        } catch (invalid: IllegalArgumentException) {
            throw BackupArchiveException(BackupArchiveProblem.CORRUPTED, invalid)
        }
    }

    /** Entry names only. Entry contents are not kept, so this does not parse or validate the archive itself. */
    private fun archiveEntryNames(archive: ByteArray): Set<String> {
        val names = linkedSetOf<String>()
        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                names += entry.name
            }
        }
        return names
    }

    private fun sectionsIn(names: Set<String>): Set<BackupCategory> = buildSet {
        if (LIBRARY_DATABASE in names) add(BackupCategory.LIBRARY)
        // Validate both owners, then offer only the sections present in the parsed preference names.
        if (SETTINGS_XML in names || SETTINGS_LEGACY in names) {
            add(BackupCategory.SETTINGS)
            add(BackupCategory.ACCOUNT)
        }
    }

    private fun Set<BackupDataCategory>.toBackupCategories(): Set<BackupCategory> =
        map { it.toBackupCategory() }.toSet()

    private fun BackupDataCategory.toBackupCategory(): BackupCategory = when (this) {
        BackupDataCategory.LIBRARY -> BackupCategory.LIBRARY
        BackupDataCategory.ACCOUNT -> BackupCategory.ACCOUNT
        BackupDataCategory.SETTINGS -> BackupCategory.SETTINGS
    }


    private companion object {
        /**
         * Expanded library and font content the GMS baseline accepts (2 GiB). It is Long so the sum cannot overflow
         * Int. The compressed archive bound is [DriveAppDataLimits.MAX_ARCHIVE_BYTES], shared by every route.
         */
        const val MAX_EXPANDED_BYTES: Long = 2L * 1024 * 1024 * 1024
        const val LIBRARY_DATABASE = "song.db"
        const val SETTINGS_XML = "settings.xml"
        const val SETTINGS_LEGACY = "settings.preferences_pb"
    }
}
