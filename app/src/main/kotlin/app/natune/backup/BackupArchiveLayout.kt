package app.natune.backup

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import app.natune.shared.data.backup.BackupSettingsReader
import app.natune.shared.data.backup.ReadBackupSettings

/**
 * What a backup archive holds, decided from its entries and its manifest, never from its file name or extension:
 *
 * - `song.db` with `settings.xml` or `settings.preferences_pb`: Natune and ArchiveTune `.backup` files.
 * - `backup.json` with `settings.json` and `song.db`: Natune `.zip` backups, the Metrolist-style layout.
 * - `backup.json` whose `format` is not Natune's, or a `settings.json` with no manifest: another app's archive
 *   (Metrolist writes only `platform` and `databaseVersion`). Its library is another app's schema, so only its
 *   settings can restore, and only those this build stores under the same name and kind.
 */
internal object BackupArchiveLayout {
    const val MANIFEST = "backup.json"
    const val SETTINGS_JSON = "settings.json"
    private const val MAX_MANIFEST_BYTES = 64 * 1024
    private const val TAG = "BackupArchiveLayout"
    private val json = Json { ignoreUnknownKeys = true }

    /** True for another app's archive. An unreadable manifest counts as foreign, so its database is never restored. */
    fun isForeign(manifest: ByteArray?, hasSettingsJson: Boolean): Boolean {
        if (manifest == null) return hasSettingsJson
        val format = runCatching {
            ((json.parseToJsonElement(manifest.decodeToString()) as JsonObject)["format"] as? JsonPrimitive)
                ?.takeIf { it.isString }?.content
        }.getOrNull()
        return format?.startsWith("natune") != true
    }

    fun readManifest(input: InputStream): ByteArray = readBounded(input, MAX_MANIFEST_BYTES.toLong())

    fun readBounded(input: InputStream, limit: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size().toLong() + count <= limit) { "Backup entry exceeds limit" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    fun readSettings(bytes: ByteArray, foreign: Boolean): ReadBackupSettings =
        BackupSettingsReader.read(bytes, foreign).also { read ->
            if (read.skipped.isNotEmpty()) {
                Log.i(TAG, "Restore leaves out ${read.skipped.size} settings: " +
                    read.skipped.groupingBy { it.reason }.eachCount())
            }
        }

    /** Stages a `settings.json` as the legacy XML the restore commit applies, keeping only [includeKey] names. */
    fun convertSettingsJsonToXml(source: File, target: File, foreign: Boolean, includeKey: (String) -> Boolean) {
        require(source.isFile && source.length() > 0L) { "Restore settings missing" }
        val read = readSettings(source.readBytes(), foreign)
        FileOutputStream(target).use { writeLegacySettingsXml(it, read.settings, includeKey) }
    }
}
