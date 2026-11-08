@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.ui.ios

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import moe.rukamori.archivetune.shared.data.backup.readPortableFileBytes
import moe.rukamori.archivetune.shared.data.backup.writePortableFileBytes
import moe.rukamori.archivetune.shared.data.bytesource.Md5
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.ui.host.HostLyricsStorage
import platform.Foundation.NSFileManager
import platform.Foundation.NSNumber
import platform.Foundation.NSFileSize
import platform.posix.rename

/** Provider text and durable edits are source-bound; an edit always wins over a refreshed provider cache. */
internal class IosLyricsStore(private val root: String) : HostLyricsStorage {
    private val lock = Mutex()
    private fun path(item: ContentItem): String = "$root/" + Md5.hexDigest(
        "${item.ref.provider}/${item.ref.kind}/${item.ref.id}".encodeToByteArray()) + ".json"
    override suspend fun read(item: ContentItem, force: Boolean): String? = lock.withLock {
        val row = readRow(path(item)) ?: return@withLock null
        if (force && row["edited"]?.jsonPrimitive?.booleanOrNull != true) null else row["raw"]?.jsonPrimitive?.content
    }
    override suspend fun write(item: ContentItem, raw: String, edited: Boolean, guard: () -> Boolean): Boolean = lock.withLock {
        if (!guard()) return@withLock false
        require(raw.encodeToByteArray().size <= 1024 * 1024)
        val target = path(item)
        val previous = readRow(target)
        if (!edited && previous?.get("edited")?.jsonPrimitive?.booleanOrNull == true) return@withLock true
        NSFileManager.defaultManager.createDirectoryAtPath(root, true, null, null)
        // Limit provider cache entries; user edits are never evicted.
        val names = NSFileManager.defaultManager.contentsOfDirectoryAtPath(root, null).orEmpty().filterIsInstance<String>()
        if (names.size >= 256 && !NSFileManager.defaultManager.fileExistsAtPath(target)) {
            names.sorted().firstOrNull { readRow("$root/$it")?.get("edited")?.jsonPrimitive?.booleanOrNull != true }
                ?.let { NSFileManager.defaultManager.removeItemAtPath("$root/$it", null) }
            if (NSFileManager.defaultManager.contentsOfDirectoryAtPath(root, null).orEmpty().size >= 256 && !edited) return@withLock true
        }
        val temporary = "$target.tmp"
        try {
            writePortableFileBytes(temporary, buildJsonObject { put("raw", raw); put("edited", edited) }.toString().encodeToByteArray())
            check(rename(temporary, target) == 0) { "Cannot commit lyrics" }
        } finally { NSFileManager.defaultManager.removeItemAtPath(temporary, null) }
        true
    }
    fun usedBytes(): Long = NSFileManager.defaultManager.contentsOfDirectoryAtPath(root, null).orEmpty()
        .filterIsInstance<String>().sumOf { name ->
            (NSFileManager.defaultManager.attributesOfItemAtPath("$root/$name", null)?.get(NSFileSize) as? NSNumber)?.longLongValue ?: 0L
        }
    suspend fun clearProviderCache() = lock.withLock {
        NSFileManager.defaultManager.contentsOfDirectoryAtPath(root, null).orEmpty().filterIsInstance<String>().forEach { name ->
            if (readRow("$root/$name")?.get("edited")?.jsonPrimitive?.booleanOrNull != true)
                NSFileManager.defaultManager.removeItemAtPath("$root/$name", null)
        }
    }
    private fun readRow(path: String): JsonObject? = try {
        if (!NSFileManager.defaultManager.fileExistsAtPath(path)) null else
            Json.parseToJsonElement(readPortableFileBytes(path, 2L * 1024 * 1024).decodeToString()) as? JsonObject
    } catch (_: Exception) { null }
}
