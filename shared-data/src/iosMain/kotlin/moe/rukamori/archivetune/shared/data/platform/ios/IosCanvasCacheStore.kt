@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.data.platform.ios

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.usePinned
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile

/**
 * The bounded on-disk store for downloaded canvas loops in the app container: `<Application Support>/cache/canvas`,
 * the same relative layout the desktop store uses inside its instance directory. The frames layer downloads a
 * remote loop once and hands the decoder the local file, and the settings page measures, trims and clears the same
 * directory, so the bounded cache the canvas policy promises is a real store rather than a transient stream.
 */
class IosCanvasCacheStore(base: String? = null) {
    private val manager = NSFileManager.defaultManager

    /** The store directory. A missing store reads as zero bytes rather than an error. */
    val directory: String = (base ?: requireNotNull(
        (manager.URLsForDirectory(NSApplicationSupportDirectory, NSUserDomainMask).first() as NSURL).path,
    )) + "/cache/canvas"

    fun ensure() {
        manager.createDirectoryAtPath(directory, true, null, null)
    }

    /** The loop a URL resolves to, or null when it was never stored or was trimmed away. */
    fun resolve(url: String): String? = pathFor(url).takeIf { manager.fileExistsAtPath(it) }

    /** The local path a remote loop lands at, whether it already exists or not. */
    fun pathFor(url: String): String = "$directory/${canvasFileHash(url)}.${canvasFileExtension(url)}"

    /** The stored bytes for [url], or null when absent or unreadable. */
    fun bytesOf(url: String): ByteArray? {
        val path = resolve(url) ?: return null
        val data = NSData.dataWithContentsOfFile(path) ?: return null
        return if (data.length == 0uL) byteArrayOf() else data.bytes?.readBytes(data.length.toInt())
    }

    /** Writes one downloaded loop and answers its path. A partial or failed write answers null. */
    fun store(url: String, bytes: ByteArray): String? {
        ensure()
        val path = pathFor(url)
        val data = bytes.usePinned {
            NSData.create(bytes = if (bytes.isEmpty()) null else it.addressOf(0), length = bytes.size.toULong())
        }
        return if (data.writeToFile(path, true)) path else null
    }

    /** Total bytes the store currently holds, or null when the directory does not exist. */
    fun usedBytes(): Long? = entries()?.sumOf { it.bytes }

    /** Deletes oldest-first (by modification date) until the store fits within [limitMb]. */
    fun applyLimitMb(limitMb: Int) {
        val entries = entries() ?: return
        val limit = limitMb.toLong() * 1024L * 1024L
        var total = entries.sumOf { it.bytes }
        for (entry in entries.sortedBy { it.modifiedMs }) {
            if (total <= limit) break
            if (manager.removeItemAtPath(entry.path, null)) total -= entry.bytes
        }
    }

    /** Removes every stored loop; the directory itself stays. */
    fun clear() {
        entries()?.forEach { manager.removeItemAtPath(it.path, null) }
    }

    private data class Entry(val path: String, val bytes: Long, val modifiedMs: Long)

    private fun entries(): List<Entry>? {
        val names = manager.contentsOfDirectoryAtPath(directory, null) ?: return null
        return names.filterIsInstance<String>().mapNotNull { name ->
            val path = "$directory/$name"
            val attributes = manager.attributesOfItemAtPath(path, null) ?: return@mapNotNull null
            Entry(
                path = path,
                bytes = (attributes[NSFileSize] as? NSNumber)?.longLongValue ?: 0L,
                modifiedMs = (((attributes[NSFileModificationDate] as? NSDate)
                    ?.timeIntervalSince1970 ?: 0.0) * 1000.0).toLong(),
            )
        }
    }

    /** A stable 64-bit FNV-1a name so one URL always maps to one file without keeping an index. */
    private fun canvasFileHash(url: String): String {
        var hash = -3750763034362895579L
        for (byte in url.encodeToByteArray()) {
            hash = hash xor (byte.toLong() and 0xFF)
            hash *= 1099511628211L
        }
        val hex = CharArray(16)
        for (index in 0 until 16) {
            val nibble = ((hash ushr ((15 - index) * 4)) and 0xF).toInt()
            hex[index] = "0123456789abcdef"[nibble]
        }
        return hex.concatToString()
    }

    private fun canvasFileExtension(url: String): String {
        val path = url.substringBefore('?').substringBefore('#')
        val extension = path.substringAfterLast('.', "").lowercase()
        return if (extension in CANVAS_EXTENSIONS && extension.length <= 4) extension else "mp4"
    }

    private companion object {
        val CANVAS_EXTENSIONS = setOf("mp4", "m4v", "mov", "webm")
    }
}
