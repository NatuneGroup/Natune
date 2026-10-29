package moe.rukamori.archivetune.shared.data.backup

import java.io.*
import java.nio.file.Files
import java.util.zip.*
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.stream.XMLOutputFactory
import org.w3c.dom.Element
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import moe.rukamori.archivetune.shared.data.persistence.*
import moe.rukamori.archivetune.shared.domain.backup.BackupArchiveException
import moe.rukamori.archivetune.shared.domain.backup.BackupArchiveProblem
import moe.rukamori.archivetune.shared.domain.backup.DriveAppDataLimits
import moe.rukamori.archivetune.shared.domain.settings.*

/** Validated payload only. Hosts apply under their database/settings shutdown+transaction boundary. */
class PreparedBackup internal constructor(
    val settings: PreferenceSnapshot,
    val libraryFiles: Map<String, ByteArray>,
    val fonts: Map<String, ByteArray>,
)

/**
 * Existing ZIP entry names and settings.xml schema, with no extraction into user directories.
 *
 * Bounds are the ones the GMS Drive client restores through: a compressed archive of
 * [DriveAppDataLimits.MAX_ARCHIVE_BYTES], 2 GiB of expanded content and an 8 MiB settings.xml. Every size breach,
 * including an oversized settings.xml, is a storage refusal typed as [BackupArchiveProblem.INSUFFICIENT_STORAGE].
 * A direct restore therefore reports it as a classified failure, not as an untyped IllegalArgumentException.
 */
class JvmBackupArchiveCodec {
    fun create(settings: PreferenceSnapshot, selected: Set<BackupCategory>,
        libraryFiles: Map<String, ByteArray> = emptyMap(), fonts: Map<String, ByteArray> = emptyMap()): ByteArray {
        require(selected.isNotEmpty())
        val entries = linkedMapOf<String, ByteArray>()
        if (BackupCategory.SETTINGS in selected || BackupCategory.ACCOUNT in selected)
            entries["settings.xml"] = settingsXml(settings, selected)
        if (BackupCategory.LIBRARY in selected) entries.putAll(libraryFiles)
        if (BackupCategory.SETTINGS in selected) fonts.forEach { (name, bytes) -> entries["fonts/$name"] = bytes }
        if (entries.values.sumOf { it.size.toLong() } > MAX_EXPANDED_BYTES) throw sizeLimitExceeded()
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                validEntryName(name)
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        }
        return output.toByteArray().also { prepare(it, selected) }
    }

    fun prepare(archive: ByteArray, selected: Set<BackupCategory>): PreparedBackup {
        require(selected.isNotEmpty())
        // Refused before inflating, so an oversized archive costs no decompression work.
        if (archive.size > DriveAppDataLimits.MAX_ARCHIVE_BYTES) throw sizeLimitExceeded()
        val files = linkedMapOf<String, ByteArray>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                validEntryName(entry.name)
                require(!entry.isDirectory && entry.name !in files && files.size < MAX_ENTRIES) {
                    "Duplicate or invalid backup entry"
                }
                val bytes = ByteArrayOutputStream()
                val buffer = ByteArray(READ_BUFFER_BYTES)
                var entryBytes = 0L
                while (true) {
                    val count = zip.read(buffer)
                    if (count < 0) break
                    total += count
                    entryBytes += count
                    // Per chunk, so an oversized settings.xml stops inflating early. It is a typed storage refusal
                    // like the other bounds, so no caller sees an untyped argument failure for the same breach.
                    if (entry.name == SETTINGS_XML && entryBytes > MAX_SETTINGS_BYTES) throw sizeLimitExceeded()
                    if (total > MAX_EXPANDED_BYTES) throw sizeLimitExceeded()
                    bytes.write(buffer, 0, count)
                }
                files[entry.name] = bytes.toByteArray()
                zip.closeEntry()
            }
        }
        require(files.isNotEmpty()) { "Empty backup archive" }
        require(!(files.containsKey("settings.xml") && files.containsKey("settings.preferences_pb"))) { "Ambiguous settings payloads" }
        val settings = if (BackupCategory.SETTINGS in selected || BackupCategory.ACCOUNT in selected)
            files["settings.xml"]?.let { parseSettings(it, selected) } ?: files["settings.preferences_pb"]?.let { bytes ->
                val legacy = LegacyDataStorePreferences.read(bytes)
                PreferenceSnapshot.of(*legacy.keys.filter { BackupPreferencePolicy.includes(it.name, selected) }
                    .map { it to legacy[it]!! }.toTypedArray())
            } ?: throw IllegalArgumentException("Backup contains no settings")
        else PreferenceSnapshot.EMPTY
        val library = if (BackupCategory.LIBRARY in selected) files.filterKeys { it in DB_FILES } else emptyMap()
        if (BackupCategory.LIBRARY in selected) validateDatabase(library)
        val fonts = if (BackupCategory.SETTINGS in selected) files.filterKeys { it.startsWith("fonts/") }
            .mapKeys { it.key.removePrefix("fonts/") }.onEach { (_, bytes) -> validateFont(bytes) }
        else emptyMap()
        return PreparedBackup(settings, library, fonts)
    }

    /** A typed storage refusal: the gateway maps it to STORAGE, as the Drive client does for the same breach. */
    private fun sizeLimitExceeded(): BackupArchiveException =
        BackupArchiveException(BackupArchiveProblem.INSUFFICIENT_STORAGE)

    private fun validEntryName(name: String) {
        require(name == "settings.xml" || name == "settings.preferences_pb" || name in DB_FILES ||
            (name.startsWith("fonts/") && name.removePrefix("fonts/").let { file ->
                file.isNotBlank() && file.endsWith(".ttf", true) &&
                    file.none { it == '/' || it == '\\' || it == '\u0000' } && file != ".."
            })) { "Unrecognized or unsafe backup entry" }
    }

    private fun validateDatabase(files: Map<String, ByteArray>) {
        val main = requireNotNull(files["song.db"]) { "Backup contains no library database" }
        require(main.size >= 100 && main.copyOfRange(0, 16).contentEquals("SQLite format 3\u0000".toByteArray())) {
            "Invalid SQLite snapshot"
        }
        val scratch = Files.createTempDirectory("archivetune-validate-backup")
        try {
            files.forEach { (name, bytes) -> Files.write(scratch.resolve(name), bytes) }
            val connection = BundledSQLiteDriver().open(scratch.resolve("song.db").toString())
            try {
                val expectedIdentity = connection.prepare("PRAGMA user_version").use { statement ->
                    require(statement.step()) { "Android library has no schema version" }
                    // Public Room identities from the retained Android schema exports, not credentials.
                    when (statement.getLong(0)) {
                        36L -> "7f6fc454b3f0d77468bc4ae9d5a71c3f"
                        37L -> "ea9228eb769d7cdde2b6a9a6a4532182"
                        else -> throw IllegalArgumentException("Unsupported Android library schema")
                    }
                }
                connection.prepare("PRAGMA integrity_check").use { statement ->
                    require(statement.step() && statement.getText(0) == "ok" && !statement.step()) { "Corrupt SQLite snapshot" }
                }
                connection.prepare("SELECT identity_hash FROM room_master_table WHERE id = 42").use { statement ->
                    require(statement.step() && statement.getText(0) == expectedIdentity) {
                        "SQLite snapshot does not match the retained Android schema"
                    }
                }
            } finally { connection.close() }
        } catch (failure: Exception) {
            throw IllegalArgumentException("Invalid library database snapshot", failure)
        } finally { scratch.toFile().deleteRecursively() }
    }

    private fun validateFont(bytes: ByteArray) {
        require(bytes.size in 12..32_000_000)
        val signature = bytes.copyOfRange(0, 4)
        require(signature.contentEquals(byteArrayOf(0, 1, 0, 0)) ||
            signature.contentEquals("OTTO".toByteArray()) || signature.contentEquals("ttcf".toByteArray())) { "Invalid font signature" }
        // Validate table directory bounds rather than trusting only an extension/magic.
        if (!signature.contentEquals("ttcf".toByteArray())) {
            val count = ((bytes[4].toInt() and 255) shl 8) or (bytes[5].toInt() and 255)
            require(count in 1..4096 && 12L + count * 16L <= bytes.size) { "Invalid font table directory" }
            repeat(count) { index ->
                val offset = unsigned32(bytes, 12 + index * 16 + 8)
                val length = unsigned32(bytes, 12 + index * 16 + 12)
                require(offset <= bytes.size && length <= bytes.size - offset) { "Font table exceeds file" }
            }
        } else {
            val count = unsigned32(bytes, 8)
            require(count in 1..4096 && 12 + 4 * count <= bytes.size) { "Invalid font collection" }
        }
        try {
            require(java.awt.Font.createFonts(ByteArrayInputStream(bytes)).isNotEmpty()) { "Empty custom font" }
        } catch (failure: java.awt.FontFormatException) {
            throw IllegalArgumentException("Invalid custom font", failure)
        }
    }
    private fun unsigned32(bytes: ByteArray, offset: Int): Long =
        (0..3).fold(0L) { value, index -> (value shl 8) or (bytes[offset + index].toLong() and 255) }

    private fun settingsXml(snapshot: PreferenceSnapshot, selected: Set<BackupCategory>): ByteArray {
        val output = ByteArrayOutputStream()
        val writer = XMLOutputFactory.newFactory().createXMLStreamWriter(output, "UTF-8")
        writer.writeStartDocument("UTF-8", "1.0")
        writer.writeStartElement("ArchiveTuneBackup"); writer.writeStartElement("Settings")
        snapshot.keys.filter { BackupPreferencePolicy.includes(it.name, selected) }.forEach { key ->
            val tag = when(key.kind) {
                PreferenceKind.BOOLEAN -> "boolean"; PreferenceKind.STRING -> "string"; PreferenceKind.FLOAT -> "float"
                PreferenceKind.INT -> "int"; PreferenceKind.LONG -> "long"; PreferenceKind.DOUBLE -> "double"
                PreferenceKind.STRING_SET -> "string-set"
            }
            writer.writeStartElement(tag); writer.writeAttribute("name", key.name)
            val value = snapshot[key]!!
            if (key.kind == PreferenceKind.STRING_SET) (value as Set<*>).forEach {
                writer.writeStartElement("item"); writer.writeCharacters(it as String); writer.writeEndElement()
            } else writer.writeAttribute("value", value.toString())
            writer.writeEndElement()
        }
        writer.writeEndElement(); writer.writeEndElement(); writer.writeEndDocument()
        writer.close()
        return output.toByteArray()
    }

    private fun parseSettings(bytes: ByteArray, selected: Set<BackupCategory>): PreferenceSnapshot {
        // The 8 MiB settings.xml bound is enforced while the archive is scanned, before this parse runs.
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            isXIncludeAware = false; isExpandEntityReferences = false
        }
        val root = try { factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes)).documentElement }
        catch (failure: Exception) { throw IllegalArgumentException("Malformed settings XML", failure) }
        require(root.tagName == "ArchiveTuneBackup")
        val settings = root.children().single()
        require(settings.tagName == "Settings")
        val entries = settings.children().map { entry ->
            require(entry.hasAttribute("name"))
            val name = entry.getAttribute("name")
            val value = entry.getAttribute("value")
            val parsed: Pair<PreferenceKey<*>, Any> = when(entry.tagName) {
                "boolean" -> BooleanPreferenceKey(name) to value.toBooleanStrict()
                "string" -> StringPreferenceKey(name) to value
                "int" -> IntPreferenceKey(name) to value.toInt()
                "long" -> LongPreferenceKey(name) to value.toLong()
                "float" -> FloatPreferenceKey(name) to value.toFloat().also { require(it.isFinite()) }
                "double" -> DoublePreferenceKey(name) to value.toDouble().also { require(it.isFinite()) }
                "string-set" -> StringSetPreferenceKey(name) to entry.children().map {
                    require(it.tagName == "item" && it.children().isEmpty()); it.textContent
                }.toSet()
                else -> throw IllegalArgumentException("Unknown backup preference type")
            }
            require(entry.tagName == "string-set" || entry.children().isEmpty())
            parsed
        }
        require(entries.map { it.first }.distinct().size == entries.size) { "Duplicate backup preference key" }
        return PreferenceSnapshot.of(*entries.filter { BackupPreferencePolicy.includes(it.first.name, selected) }.toTypedArray())
    }

    private fun Element.children(): List<Element> = (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }
    private companion object {
        // Long, so the 2 GiB expanded sum cannot overflow Int. The compressed bound is DriveAppDataLimits, shared
        // with the gateway and the transport, so an archive is never offered that this codec would refuse.
        const val MAX_EXPANDED_BYTES: Long = 2L * 1024 * 1024 * 1024
        const val MAX_SETTINGS_BYTES: Long = 8L * 1024 * 1024
        const val MAX_ENTRIES = 1024
        const val READ_BUFFER_BYTES = 64 * 1024
        const val SETTINGS_XML = "settings.xml"
        val DB_FILES = setOf("song.db", "song.db-wal", "song.db-shm", "song.db-journal")
    }
}
