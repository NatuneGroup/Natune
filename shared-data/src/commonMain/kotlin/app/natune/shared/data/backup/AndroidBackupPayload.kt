package app.natune.shared.data.backup

import app.natune.shared.data.persistence.BackupCategory
import app.natune.shared.data.persistence.BackupPreferencePolicy
import app.natune.shared.data.persistence.LegacyDataStorePreferences
import app.natune.shared.domain.backup.*
import app.natune.shared.domain.settings.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
                    name == PortableBackupFormat.SETTINGS -> PortableBackupFormat.MAX_SETTINGS_BYTES
                    name == PortableBackupFormat.MANIFEST -> PortableBackupFormat.MAX_MANIFEST_BYTES
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
        val settingsFiles = listOf("settings.xml", "settings.preferences_pb", PortableBackupFormat.SETTINGS).filter { it in files }
        if (settingsFiles.size > 1) throw BackupArchiveException(BackupArchiveProblem.CORRUPTED)
        // An Android .zip names its origin in backup.json; another app's song.db (Metrolist's schema) never restores.
        val foreign = isForeign(files[PortableBackupFormat.MANIFEST], PortableBackupFormat.SETTINGS in files)
        val database = if (foreign) emptyMap() else files.filterKeys { it in DATABASE_FILES }
        if ("song.db" !in database && settingsFiles.isEmpty()) throw BackupArchiveException(BackupArchiveProblem.NOT_A_BACKUP)
        if (database.isNotEmpty() && "song.db" !in database) throw BackupArchiveException(BackupArchiveProblem.CORRUPTED)
        val selected = BackupCategory.entries.toSet()
        val settings = files["settings.xml"]?.let(AndroidBackupSettingsXml::parse)
            ?: files["settings.preferences_pb"]?.let(LegacyDataStorePreferences::read)
            ?: files[PortableBackupFormat.SETTINGS]?.let { BackupSettingsReader.read(it, foreign).settings }
            ?: PreferenceSnapshot.EMPTY
        val filtered = PreferenceSnapshot.of(*settings.keys.filter { BackupPreferencePolicy.includes(it.name, selected) }
            .map { it to settings[it]!! }.toTypedArray())
        return AndroidBackupPayload(database, filtered)
    }

    /** Natune's manifests name a `natune…` format; anything else, or settings.json without one, is another app's. */
    private fun isForeign(manifest: ByteArray?, hasSettingsJson: Boolean): Boolean {
        if (manifest == null) return hasSettingsJson
        val format = runCatching {
            ((Json.parseToJsonElement(manifest.decodeToString()) as JsonObject)["format"] as? JsonPrimitive)
                ?.takeIf { it.isString }?.content
        }.getOrNull()
        return format?.startsWith("natune") != true
    }
    val DATABASE_FILES = setOf("song.db", "song.db-wal", "song.db-shm", "song.db-journal")
}
