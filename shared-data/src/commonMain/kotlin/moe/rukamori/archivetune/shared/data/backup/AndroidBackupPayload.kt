package moe.rukamori.archivetune.shared.data.backup

import moe.rukamori.archivetune.shared.data.persistence.BackupCategory
import moe.rukamori.archivetune.shared.data.persistence.BackupPreferencePolicy
import moe.rukamori.archivetune.shared.data.persistence.LegacyDataStorePreferences
import moe.rukamori.archivetune.shared.domain.backup.*
import moe.rukamori.archivetune.shared.domain.settings.*

class AndroidBackupPayload(val files: Map<String, ByteArray>, val settings: PreferenceSnapshot)

/** Bounded Android ZIP parsing; platform adapters validate/convert the SQLite snapshot before staging. */
object PortableAndroidBackup {
    fun parse(archive: ByteArray): AndroidBackupPayload {
        if (archive.isEmpty()) throw BackupArchiveException(BackupArchiveProblem.NOT_A_BACKUP)
        if (archive.size > DriveAppDataLimits.MAX_ARCHIVE_BYTES) throw BackupArchiveException(BackupArchiveProblem.INSUFFICIENT_STORAGE)
        val files = try {
            val entries = PortableZip.read(archive, 1024, { name ->
                when {
                    name in DATABASE_FILES -> PortableBackupFormat.MAX_DATABASE_BYTES
                    name == "settings.xml" || name == "settings.preferences_pb" -> 8L * 1024 * 1024
                    name.startsWith("fonts/") && name.removePrefix("fonts/").let {
                        it.isNotBlank() && it.endsWith(".ttf", true) && it.none { c -> c in "/\\\u0000" } && it != ".."
                    } -> 32_000_000L
                    else -> throw BackupArchiveException(BackupArchiveProblem.NOT_A_BACKUP)
                }
            }, PortableBackupFormat.MAX_TOTAL_BYTES)
            require(entries.distinctBy { it.name }.size == entries.size)
            entries.associate { it.name to it.data }
        } catch (error: BackupArchiveException) { throw error
        } catch (_: PortableZipTooLarge) { throw BackupArchiveException(BackupArchiveProblem.INSUFFICIENT_STORAGE)
        } catch (_: IllegalArgumentException) { throw BackupArchiveException(BackupArchiveProblem.CORRUPTED) }
        require(!("settings.xml" in files && "settings.preferences_pb" in files)) { "Ambiguous settings" }
        require("song.db" in files || "settings.xml" in files || "settings.preferences_pb" in files)
        require(files.keys.none { it in DATABASE_FILES && it != "song.db" } || "song.db" in files)
        val selected = BackupCategory.entries.toSet()
        val settings = files["settings.xml"]?.let(AndroidBackupSettingsXml::parse)
            ?: files["settings.preferences_pb"]?.let(LegacyDataStorePreferences::read) ?: PreferenceSnapshot.EMPTY
        val filtered = PreferenceSnapshot.of(*settings.keys.filter { BackupPreferencePolicy.includes(it.name, selected) }
            .map { it to settings[it]!! }.toTypedArray())
        return AndroidBackupPayload(files.filterKeys { it in DATABASE_FILES }, filtered)
    }
    val DATABASE_FILES = setOf("song.db", "song.db-wal", "song.db-shm", "song.db-journal")
}
