package moe.rukamori.archivetune.shared.data.backup

import moe.rukamori.archivetune.shared.data.persistence.BackupCategory
import moe.rukamori.archivetune.shared.data.persistence.BackupPreferencePolicy
import moe.rukamori.archivetune.shared.domain.backup.BackupArchiveException
import moe.rukamori.archivetune.shared.domain.backup.BackupArchiveProblem
import moe.rukamori.archivetune.shared.domain.backup.BackupDataCategory
import moe.rukamori.archivetune.shared.domain.backup.DriveAppDataLimits
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot

/** What one archive holds after its entries were checked. The database, when present, sits at [database]. */
class ParsedPortableArchive(val database: ByteArray?, val settings: PreferenceSnapshot?) {
    /** The sections this archive can restore: LIBRARY with a database, ACCOUNT and SETTINGS by their preference names. */
    val categories: Set<BackupDataCategory> = backupCategoriesOf(database != null, settings)
}

/**
 * Writes and checks the [PortableBackupFormat] ZIP every portable host shares. The container is the one
 * [PortableZip] emits: deflated entries, sizes and CRC in the local header, a central directory, no ZIP64. It reads
 * back through java.util.zip on the desktop exactly as java's own writer output reads here, so an archive crosses
 * hosts unchanged.
 */
object PortableBackupArchive {
    /**
     * Builds the archive for [selected] sections: the manifest first, then the database snapshot and the filtered
     * settings, in the desktop host's order. An archive over the shared bound fails before it is returned.
     */
    fun create(
        platform: String,
        database: ByteArray?,
        settings: PreferenceSnapshot?,
        nowMillis: Long,
    ): ByteArray {
        require(database != null || settings != null) { "Archive carries nothing" }
        val entries = mutableListOf<Pair<String, ByteArray>>()
        entries += PortableBackupFormat.MANIFEST to PortableBackupFormat.manifest(platform, nowMillis)
        if (database != null) {
            if (database.size.toLong() > PortableBackupFormat.MAX_DATABASE_BYTES) throw tooLarge()
            entries += PortableBackupFormat.DATABASE to database
        }
        if (settings != null) {
            val bytes = PortableBackupFormat.encodeSettings(settings)
            if (bytes.size.toLong() > PortableBackupFormat.MAX_SETTINGS_BYTES) throw tooLarge()
            entries += PortableBackupFormat.SETTINGS to bytes
        }
        val archive = PortableZip.write(entries, nowMillis)
        if (archive.size > DriveAppDataLimits.MAX_ARCHIVE_BYTES) throw tooLarge()
        return archive
    }

    /**
     * Reads and checks every entry, exactly the checks the desktop host's parser applies: bounded names, one of each
     * known entry, a valid manifest, allowlisted preferences and at least one restorable section. Archives in the
     * Android format (`song.db`, `settings.xml`) carry no manifest and are refused here; hosts that support them
     * delegate before calling.
     */
    fun parse(archive: ByteArray): ParsedPortableArchive {
        if (archive.isEmpty() || archive.size > DriveAppDataLimits.MAX_ARCHIVE_BYTES) {
            throw BackupArchiveException(
                if (archive.isEmpty()) BackupArchiveProblem.NOT_A_BACKUP else BackupArchiveProblem.INSUFFICIENT_STORAGE,
            )
        }
        val entries = try {
            PortableZip.read(
                archive,
                PortableBackupFormat.MAX_ENTRIES,
                { name ->
                    when (name) {
                        PortableBackupFormat.MANIFEST -> PortableBackupFormat.MAX_MANIFEST_BYTES
                        PortableBackupFormat.SETTINGS -> PortableBackupFormat.MAX_SETTINGS_BYTES
                        PortableBackupFormat.DATABASE -> PortableBackupFormat.MAX_DATABASE_BYTES
                        else -> throw BackupArchiveException(BackupArchiveProblem.NOT_A_BACKUP)
                    }
                },
                PortableBackupFormat.MAX_TOTAL_BYTES,
            )
        } catch (rejected: BackupArchiveException) {
            throw rejected
        } catch (_: PortableZipTooManyEntries) {
            throw corrupted()
        } catch (_: PortableZipTooLarge) {
            throw BackupArchiveException(BackupArchiveProblem.INSUFFICIENT_STORAGE)
        } catch (_: PortableZipNotArchive) {
            throw BackupArchiveException(BackupArchiveProblem.NOT_A_BACKUP)
        } catch (_: IllegalArgumentException) {
            // A declared size, offset or entry count beyond the file's own bounds.
            throw BackupArchiveException(BackupArchiveProblem.NOT_A_BACKUP)
        }
        if (entries.distinctBy { it.name }.size != entries.size) throw corrupted()
        val manifest = entries.firstOrNull { it.name == PortableBackupFormat.MANIFEST }?.data
            ?: throw BackupArchiveException(BackupArchiveProblem.NOT_A_BACKUP)
        PortableBackupFormat.checkManifest(manifest)
        val settings = entries.firstOrNull { it.name == PortableBackupFormat.SETTINGS }?.data
            ?.let(PortableBackupFormat::decodeSettings)?.let { decoded ->
                // A key the policy never exports is refused at import too, so a crafted file cannot set vault metadata.
                if (decoded.keys.any { !BackupPreferencePolicy.includes(it.name, BackupCategory.entries.toSet()) }) throw corrupted()
                decoded
            }
        val database = entries.firstOrNull { it.name == PortableBackupFormat.DATABASE }?.data
        return ParsedPortableArchive(database, settings).also {
            if (it.categories.isEmpty()) throw BackupArchiveException(BackupArchiveProblem.NO_RESTORABLE_DATA)
        }
    }

    private fun corrupted() = BackupArchiveException(BackupArchiveProblem.CORRUPTED)

    private fun tooLarge() = BackupArchiveException(BackupArchiveProblem.INSUFFICIENT_STORAGE)
}
