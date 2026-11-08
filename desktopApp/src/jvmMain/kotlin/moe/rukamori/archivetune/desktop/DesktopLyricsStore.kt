package moe.rukamori.archivetune.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.ui.host.HostSavedLyrics
import moe.rukamori.archivetune.shared.ui.menu.LyricsEditSnapshot

/** Provider cache is clearable; user edits live separately and survive cache clearing. */
internal class DesktopLyricsStore(private val directory: Path) {
    private val gate = Mutex()
    private fun path(item: ContentItem, edit: Boolean): Path {
        val identity = listOf(item.ref.provider, item.ref.kind.name, item.ref.id).joinToString { "${it.length}:$it" }
        val hash = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
        return directory.resolve(if (edit) "lyrics-edits" else "cache/lyrics").resolve("$hash.json")
    }
    private fun read(item: ContentItem): JsonObject? {
        for (edit in listOf(true, false)) {
            val file = path(item, edit)
            if (!Files.isRegularFile(file) || Files.size(file) > MAX_DOCUMENT_BYTES) continue
            val json = runCatching { Json.parseToJsonElement(Files.readString(file)).jsonObject }.getOrNull() ?: continue
            if (json["raw"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true) return json
        }
        return null
    }
    suspend fun saved(item: ContentItem): HostSavedLyrics? = withContext(Dispatchers.IO) { gate.withLock {
        read(item)?.let { HostSavedLyrics(it.getValue("raw").jsonPrimitive.content,
            it["edited"]?.jsonPrimitive?.booleanOrNull == true) }
    } }
    suspend fun snapshot(item: ContentItem, occurrence: String): LyricsEditSnapshot? = withContext(Dispatchers.IO) { gate.withLock {
        read(item)?.let { snapshot(it, occurrence) }
    } }
    private fun snapshot(json: JsonObject, occurrence: String) = LyricsEditSnapshot(occurrence,
        json.getValue("raw").jsonPrimitive.content, json["source"]?.jsonPrimitive?.content ?: "CACHE", "Desktop",
        json["revision"]?.jsonPrimitive?.longOrNull ?: 0L)

    suspend fun fetched(item: ContentItem, raw: String) = withContext(Dispatchers.IO) { gate.withLock {
        if (read(item)?.get("edited")?.jsonPrimitive?.booleanOrNull == true) return@withLock
        write(item, raw, false, "CACHE", 0, { true })
        val root = directory.resolve("cache/lyrics")
        var total = 0L
        Files.list(root).use { stream ->
            stream.filter { Files.isRegularFile(it) }.sorted { a, b -> Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a)) }
                .forEach { file -> total += Files.size(file); if (total > 64L * 1024 * 1024) Files.deleteIfExists(file) }
        }
    } }
    suspend fun compareAndSet(item: ContentItem, before: LyricsEditSnapshot, after: LyricsEditSnapshot,
        current: () -> Boolean): LyricsEditSnapshot? = withContext(Dispatchers.IO) { gate.withLock {
        val json = read(item) ?: return@withLock null
        if (!current() || snapshot(json, before.mediaId) != before) return@withLock null
        val next = after.copy(revision = before.revision + 1)
        if (write(item, next.lyrics, true, next.source, next.revision, current)) next else null
    } }
    private fun write(item: ContentItem, raw: String, edited: Boolean, source: String, revision: Long, current: () -> Boolean): Boolean {
        require(raw.isNotBlank() && raw.toByteArray().size <= MAX_DOCUMENT_BYTES)
        val target = path(item, edited)
        Files.createDirectories(target.parent)
        val temporary = Files.createTempFile(target.parent, ".lyrics-", ".part")
        try {
            Files.writeString(temporary, buildJsonObject {
                put("raw", raw); put("edited", edited); put("source", source); put("revision", revision)
            }.toString())
            if (!current()) return false
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            return true
        } finally { Files.deleteIfExists(temporary) }
    }
    private companion object { const val MAX_DOCUMENT_BYTES = 1024 * 1024L }
}
