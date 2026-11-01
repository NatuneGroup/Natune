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
    private val recordsDirectory: File get() = File(directory, RECORDS_DIRECTORY)

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
        val stored = pendingAttempt()
        // Only a same-build attempt owned by another (dead) process is protected until the launcher
        // reads it. A healthy launch retires a leftover from another build or an unreadable marker.
        if (stored != attempt && stored?.version == attempt.version) return false
        return clearPending()
    }

    fun clearPending(): Boolean = File(directory, PENDING_FILE).let { !it.exists() || it.delete() }

    fun captureJava(
        timestampMs: Long,
        thread: String,
        throwable: Throwable,
        build: CrashBuild = CrashBuild.UNKNOWN,
    ) {
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
        val content = writer.toString()
        // The record is written first so a failure of the single-slot file below still leaves the crash listed.
        runCatching {
            saveRecord(
                CrashRecord(
                    timestampMs = timestampMs,
                    pid = build.pid,
                    kind = ProcessExitKind.CRASH,
                    status = 0,
                    captured = true,
                    process = build.process,
                    thread = thread.replace('\n', ' ').take(128),
                    body = content.split('\n', limit = 3).getOrElse(2) { "" },
                    truncated = content.length >= MAX_JAVA_CHARS,
                    versionName = build.versionName,
                    versionCode = build.versionCode,
                ),
            )
        }
        writeSync(JAVA_CRASH_FILE, content)
    }

    fun javaCrash(): SavedJavaCrash? {
        val saved = read(JAVA_CRASH_FILE, MAX_JAVA_CHARS * 4) ?: return null
        val lines = saved.split('\n', limit = 3)
        if (lines.size != 3) return null
        val timestamp = lines[0].toLongOrNull() ?: return null
        return SavedJavaCrash(timestamp, lines[1], lines[2], saved.length >= MAX_JAVA_CHARS)
    }

    /** Called once the newest Java crash has a record, or it was imported from a previous build. */
    fun removeJavaCrash() {
        File(directory, JAVA_CRASH_FILE).delete()
    }

    fun acknowledgedThrough(): Long = read(REVIEWED_FILE, 64)?.trim()?.toLongOrNull() ?: 0L

    fun acknowledgeThrough(timestampMs: Long) {
        writeSync(REVIEWED_FILE, maxOf(acknowledgedThrough(), timestampMs).toString())
    }

    fun exitsImportedThrough(): Long = read(EXITS_IMPORTED_FILE, 64)?.trim()?.toLongOrNull() ?: 0L

    fun markExitsImportedThrough(timestampMs: Long) {
        writeSync(EXITS_IMPORTED_FILE, maxOf(exitsImportedThrough(), timestampMs).toString())
    }

    /** Saves one record, then keeps only the newest [MAX_CRASH_RECORDS]. Returns false when the write failed. */
    fun saveRecord(record: CrashRecord): Boolean {
        val saved = runCatching {
            writeFile(recordsDirectory, crashRecordFileName(record.id), encodeCrashRecord(record))
        }.isSuccess
        if (saved) runCatching { pruneRecords() }
        return saved
    }

    fun listRecords(): List<CrashRecord> =
        recordFiles().mapNotNull { readRecordFile(it) }.sortedByDescending { it.timestampMs }

    fun readRecord(id: String): CrashRecord? =
        if (isValidCrashRecordId(id)) readRecordFile(File(recordsDirectory, crashRecordFileName(id))) else null

    /**
     * Clearing also drops the single-slot Java file and moves the import watermark, so the exits Android
     * still remembers, and the Java crash they duplicate, do not come back at the next launch.
     */
    fun clearRecords(): Boolean {
        recordsDirectory.listFiles()?.forEach { it.delete() }
        removeJavaCrash()
        runCatching { markExitsImportedThrough(System.currentTimeMillis()) }
        return recordFiles().isEmpty()
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

    private fun readRecordFile(file: File): CrashRecord? =
        try {
            file.inputStream().use { decodeCrashRecord(it.readCappedText(MAX_RECORD_BYTES).text) }
        } catch (_: IOException) {
            null
        }

    /** Newest first, by the timestamp in the file name, so pruning drops the oldest. */
    private fun recordFiles(): List<File> {
        val files = recordsDirectory.listFiles() ?: return emptyList()
        return files
            .filter { it.isFile && isCrashRecordFileName(it.name) }
            .sortedByDescending { crashRecordFileTimestamp(it.name) ?: Long.MIN_VALUE }
    }

    private fun pruneRecords() {
        recordFiles().drop(MAX_CRASH_RECORDS).forEach { it.delete() }
    }

    private fun writeSync(name: String, body: String) = writeFile(directory, name, body)

    private fun writeFile(parent: File, name: String, body: String) {
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Recovery directory unavailable")
        val destination = File(parent, name)
        val staging = File(parent, "$name.tmp")
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
        private const val EXITS_IMPORTED_FILE = "exits_imported_through"
        private const val RECORDS_DIRECTORY = "records"
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
