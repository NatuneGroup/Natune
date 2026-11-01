@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.data.backup

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.posix.O_RDONLY
import platform.posix.close
import platform.posix.fclose
import platform.posix.ferror
import platform.posix.fflush
import platform.posix.fileno
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fsync
import platform.posix.fwrite
import platform.posix.open

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
        // A short read can be end of file or an error; only the error may pass silently.
        check(ferror(file) == 0) { "File read failed" }
        val bytes = ByteArray(total.toInt())
        var offset = 0
        chunks.forEach { chunk ->
            chunk.copyInto(bytes, offset)
            offset += chunk.size
        }
        return bytes
    } finally {
        check(fclose(file) == 0) { "File close failed" }
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
            check(ferror(file) == 0) { "File read failed" }
            return buffer.copyOf(read.toInt())
        }
    } finally {
        check(fclose(file) == 0) { "File close failed" }
    }
}

/**
 * Writes [bytes] to [path] durably, replacing any file there; the parent directory must exist. Flush, fsync and
 * close all report, so a caller that promotes what it wrote (a staged restore, a journal) never carries a page the
 * kernel still holds. The rename that publishes the file wants [syncPortableDirectory] on its parent.
 */
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
        check(fflush(file) == 0) { "File flush failed" }
        check(fsync(fileno(file)) == 0) { "File sync failed" }
    } finally {
        check(fclose(file) == 0) { "File close failed" }
    }
}

/** Syncs the directory at [path], so the renames it recorded survive the process that made them. */
fun syncPortableDirectory(path: String) {
    val descriptor = open(path, O_RDONLY)
    check(descriptor >= 0) { "Directory could not be opened" }
    try {
        check(fsync(descriptor) == 0) { "Directory sync failed" }
    } finally {
        check(close(descriptor) == 0) { "Directory close failed" }
    }
}
