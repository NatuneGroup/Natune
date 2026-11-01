package moe.rukamori.archivetune.shared.data.backup

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.shared.data.persistence.BackupCategory
import moe.rukamori.archivetune.shared.data.persistence.LegacySongDbImport
import moe.rukamori.archivetune.shared.data.persistence.inspectPortableStateFile
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot

/**
 * The Android `.backup` archive the baseline writes through its own codec: `song.db` plus its journal siblings,
 * `settings.xml` (or the older `settings.preferences_pb`) and optional `fonts/`-prefixed ttf entries. A desktop or iOS host that is
 * offered one of these files imports it instead of refusing it: [recognizes] tells the two formats apart, and
 * [convert] produces the portable-state database and preference snapshot a host restore stages exactly like a
 * native archive. Custom fonts are dropped — the portable build keeps no font directory.
 *
 * [JvmBackupArchiveCodec] applies the baseline's bounds and validation, and [LegacySongDbImport] reads every
 * Android schema up to [LegacySongDbImport.ANDROID_SCHEMA_VERSION]
 * rows, so every refusal the Android restore would produce is produced here as well, as [IllegalArgumentException].
 */
object AndroidBackupArchive {
    const val DATABASE = "portable-state.db"

    /** Result of [convert]: the converted database (when the archive carried `song.db`) and the settings payload. */
    class ConvertedArchive(val database: Path?, val settings: PreferenceSnapshot) {
        val isEmpty get() = database == null && settings.keys.isEmpty()
    }

    /**
     * True when the zip's entry names look like an Android archive: it carries `song.db` or a settings payload and
     * none of the desktop manifest markers. Only the names are read; [convert] re-validates everything it uses.
     */
    fun recognizes(archive: ByteArray): Boolean =
        entryNames(archive).let { names ->
            names != null && "backup.json" !in names &&
                names.any { it == SONG_DB || it == SETTINGS_XML || it == SETTINGS_PB }
        }

    /**
     * Converts the archive into [scratch]. Returns the converted database file and the settings payload; throws
     * [IllegalArgumentException] when the archive is not an Android backup or fails the codec's validation.
     */
    suspend fun convert(archive: ByteArray, scratch: Path): ConvertedArchive = withContext(Dispatchers.IO) {
        val names = entryNames(archive) ?: throw IllegalArgumentException("Not a zip archive")
        val wantsLibrary = SONG_DB in names
        val wantsSettings = names.any { it == SETTINGS_XML || it == SETTINGS_PB }
        require(wantsLibrary || wantsSettings) { "Not an ArchiveTune backup" }
        val categories = buildSet {
            if (wantsLibrary) add(BackupCategory.LIBRARY)
            if (wantsSettings) {
                add(BackupCategory.SETTINGS)
                add(BackupCategory.ACCOUNT)
            }
        }
        val prepared = JvmBackupArchiveCodec().prepare(archive, categories)
        val database = if (wantsLibrary) {
            prepared.libraryFiles.forEach { (name, bytes) -> Files.write(scratch.resolve(name), bytes) }
            val converted = scratch.resolve(DATABASE)
            LegacySongDbImport.convert(scratch.resolve(SONG_DB), converted)
            inspectPortableStateFile(converted)
            converted
        } else {
            null
        }
        ConvertedArchive(database, prepared.settings)
    }

    private fun entryNames(archive: ByteArray): List<String>? = try {
        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            generateSequence { zip.nextEntry }.map { it.name }.toList()
        }
    } catch (_: ZipException) {
        null
    }

    private const val SONG_DB = "song.db"
    private const val SETTINGS_XML = "settings.xml"
    private const val SETTINGS_PB = "settings.preferences_pb"
}
