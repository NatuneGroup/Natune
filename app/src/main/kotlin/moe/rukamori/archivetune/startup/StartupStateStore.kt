package moe.rukamori.archivetune.startup

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.PrintWriter
import java.io.Writer

internal data class StartupAttempt(val timestampMs: Long, val pid: Int, val version: String)
internal data class SavedJavaCrash(val timestampMs: Long, val thread: String, val stack: String, val truncated: Boolean)
internal data class CappedText(val text: String, val truncated: Boolean)
internal data class CappedBytes(val bytes: ByteArray, val truncated: Boolean)

internal class StartupStateStore(private val directory: File) {
    fun pendingExists(): Boolean = File(directory, PENDING_FILE).isFile
    fun pendingAttempt(): StartupAttempt? = readAttempt(PENDING_FILE)
    fun lastAttempt(): StartupAttempt? = readAttempt(LAST_ATTEMPT_FILE)

    fun begin(attempt: StartupAttempt) {
        // A service/provider restart must not replace the failed attempt before the launcher reads it.
        if (pendingExists()) return
        val encoded = "${attempt.timestampMs}\n${attempt.pid}\n${attempt.version.take(128)}"
        writeSync(LAST_ATTEMPT_FILE, encoded)
        writeSync(PENDING_FILE, encoded)
    }

    fun markHealthy(attempt: StartupAttempt): Boolean {
        if (pendingAttempt() != attempt) return false
        return clearPending()
    }

    fun clearPending(): Boolean = File(directory, PENDING_FILE).let { !it.exists() || it.delete() }

    fun captureJava(timestampMs: Long, thread: String, throwable: Throwable) {
        val writer = CappedWriter(MAX_JAVA_CHARS)
        PrintWriter(writer).use { output ->
            output.println(timestampMs)
            output.println(thread.replace('\n', ' ').take(128))
            try {
                throwable.printStackTrace(output)
            } catch (_: Throwable) {
                // Even an exhausted VM can leave the timestamp and the part already written.
            }
        }
        writeSync(JAVA_CRASH_FILE, writer.toString())
    }

    fun javaCrash(): SavedJavaCrash? {
        val saved = read(JAVA_CRASH_FILE, MAX_JAVA_CHARS * 4) ?: return null
        val lines = saved.split('\n', limit = 3)
        if (lines.size != 3) return null
        val timestamp = lines[0].toLongOrNull() ?: return null
        return SavedJavaCrash(timestamp, lines[1], lines[2], saved.length >= MAX_JAVA_CHARS)
    }

    fun acknowledgedThrough(): Long = read(REVIEWED_FILE, 64)?.trim()?.toLongOrNull() ?: 0L

    fun acknowledgeThrough(timestampMs: Long) {
        writeSync(REVIEWED_FILE, maxOf(acknowledgedThrough(), timestampMs).toString())
    }

    private fun readAttempt(name: String): StartupAttempt? {
        val lines = read(name, 512)?.split('\n', limit = 3) ?: return null
        if (lines.size != 3) return null
        return StartupAttempt(lines[0].toLongOrNull() ?: return null, lines[1].toIntOrNull() ?: return null, lines[2])
    }

    private fun read(name: String, maxBytes: Int): String? =
        try {
            File(directory, name).inputStream().use { it.readCappedText(maxBytes).text }
        } catch (_: IOException) {
            null
        }

    private fun writeSync(name: String, body: String) {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Recovery directory unavailable")
        val destination = File(directory, name)
        val staging = File(directory, "$name.tmp")
        FileOutputStream(staging).use { output ->
            output.write(body.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        if (!staging.renameTo(destination)) throw IOException("Recovery record could not be saved")
    }

    private class CappedWriter(private val limit: Int) : Writer() {
        private val text = StringBuilder()
        override fun write(buffer: CharArray, offset: Int, length: Int) {
            val count = minOf(length, limit - text.length)
            if (count > 0) text.append(buffer, offset, count)
        }
        override fun write(value: String, offset: Int, length: Int) {
            val count = minOf(length, limit - text.length)
            if (count > 0) text.append(value, offset, offset + count)
        }
        override fun flush() = Unit
        override fun close() = Unit
        override fun toString(): String = text.toString()
    }

    companion object {
        private const val PENDING_FILE = "startup_pending"
        private const val LAST_ATTEMPT_FILE = "last_startup"
        private const val JAVA_CRASH_FILE = "java_crash.txt"
        private const val REVIEWED_FILE = "reviewed_through"
        internal const val MAX_JAVA_CHARS = 16_000
    }
}

internal fun InputStream.readCappedText(maxBytes: Int): CappedText =
    readCappedBytes(maxBytes).let { CappedText(it.bytes.toString(Charsets.UTF_8), it.truncated) }

internal fun InputStream.readCappedBytes(maxBytes: Int): CappedBytes {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(minOf(4096, maxBytes))
    var remaining = maxBytes
    while (remaining > 0) {
        val count = read(buffer, 0, minOf(buffer.size, remaining))
        if (count < 0) return CappedBytes(output.toByteArray(), truncated = false)
        if (count == 0) break
        output.write(buffer, 0, count)
        remaining -= count
    }
    return CappedBytes(output.toByteArray(), truncated = true)
}
