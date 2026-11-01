@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.data.backup

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fwrite

private const val COPY_BUFFER_BYTES = 64 * 1024

/**
 * Reads at most [maxBytes] of the file at [path]. A larger file throws, the same refusal the bounded archive reader
 * gives, so an oversized library snapshot stops the export before the archive is built.
 */
fun readPortableFileBytes(path: String, maxBytes: Long): ByteArray {
    require(maxBytes >= 0L)
    val file = fopen(path, "rb") ?: throw IllegalStateException("File could not be opened")
    try {
        val chunks = ArrayList<ByteArray>()
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var total = 0L
        buffer.usePinned { pinned ->
            while (true) {
                // One byte past the limit detects an oversized file instead of silently truncating it.
                val request = minOf(buffer.size.toLong(), maxBytes - total + 1L)
                if (request <= 0L) break
                val read = fread(pinned.addressOf(0), 1u, request.toULong(), file)
                if (read.toLong() == 0L) break
                total += read.toLong()
                require(total <= maxBytes) { "File exceeds the size limit" }
                chunks += buffer.copyOf(read.toInt())
            }
        }
        val bytes = ByteArray(total.toInt())
        var offset = 0
        chunks.forEach { chunk ->
            chunk.copyInto(bytes, offset)
            offset += chunk.size
        }
        return bytes
    } finally {
        fclose(file)
    }
}

/** Reads at most [count] bytes from the start of [path]; fewer when the file is shorter. */
fun readPortableFilePrefix(path: String, count: Int): ByteArray {
    require(count >= 0)
    if (count == 0) return byteArrayOf()
    val file = fopen(path, "rb") ?: return byteArrayOf()
    try {
        val buffer = ByteArray(count)
        buffer.usePinned { pinned ->
            val read = fread(pinned.addressOf(0), 1u, count.toULong(), file)
            return buffer.copyOf(read.toInt())
        }
    } finally {
        fclose(file)
    }
}

/** Writes [bytes] to [path], replacing any file there; the parent directory must exist. */
fun writePortableFileBytes(path: String, bytes: ByteArray) {
    val file = fopen(path, "wb") ?: throw IllegalStateException("File could not be opened")
    try {
        bytes.usePinned { pinned ->
            if (bytes.isNotEmpty()) {
                check(fwrite(pinned.addressOf(0), 1u, bytes.size.toULong(), file) == bytes.size.toULong()) {
                    "File write failed"
                }
            }
        }
    } finally {
        fclose(file)
    }
}
