package moe.rukamori.archivetune.shared.data.diagnostics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.rukamori.archivetune.shared.domain.diagnostics.DiagnosticLevel
import moe.rukamori.archivetune.shared.domain.diagnostics.DiagnosticLines
import moe.rukamori.archivetune.shared.domain.diagnostics.DiagnosticLogStore
import moe.rukamori.archivetune.shared.domain.diagnostics.DiagnosticRecord

/** One file of the log directory, by plain name, with its size in bytes. */
data class DiagnosticLogFile(val name: String, val size: Long)

/**
 * The host's log directory. Every name is a plain file name inside one directory that holds nothing else of the app's.
 * Calls arrive one at a time from [RotatingDiagnosticLog], so an implementation needs no locking of its own.
 */
interface DiagnosticLogFiles {
    suspend fun list(): List<DiagnosticLogFile>

    /** Appends [text] as UTF-8, creating the file when it is missing. False when the write failed. */
    suspend fun append(name: String, text: String): Boolean

    /** The last [maxBytes] bytes of [name], or fewer when the file is shorter. Empty when it cannot be read. */
    suspend fun readTail(name: String, maxBytes: Long): ByteArray

    suspend fun delete(name: String)
}

/**
 * The Android persistent log capture on a host without logcat: rotating files `s<session>-p<part>.log`, the newest
 * [PARTS_PER_SESSION] parts of [PART_BYTES] for this session, earlier sessions pruned at start to
 * [PREVIOUS_RETENTION_BYTES] while the most recent one is kept whole, and the same export and viewer caps.
 *
 * [append] never blocks and never fails: it redacts the line on the caller's thread and hands it to one writer
 * coroutine, which writes whatever is waiting in one batch. The hand-off is bounded, so a flood drops its oldest
 * unwritten lines instead of growing memory. Nothing runs while nothing is logged; there is no flush timer.
 */
class RotatingDiagnosticLog(
    private val files: DiagnosticLogFiles,
    scope: CoroutineScope,
    private val sessionStartMillis: Long,
    private val nowMillis: () -> Long,
    private val formatTime: (Long) -> String,
    sessionHeader: String,
) : DiagnosticLogStore {
    private val pending = Channel<String>(PENDING_LINES, BufferOverflow.DROP_OLDEST)
    private val io = Mutex()
    private val changes = MutableStateFlow(0L)
    private val clearedAt = MutableStateFlow(Long.MIN_VALUE)
    private var part = 0
    private var partBytes = 0L
    private var writeFailed = false

    override val revision: StateFlow<Long> = changes.asStateFlow()

    init {
        pending.trySend(DiagnosticLines.marker("--------- $sessionHeader"))
        scope.launch {
            io.withLock { prepare() }
            for (first in pending) {
                val batch = StringBuilder(first)
                while (batch.length < BATCH_CHARS) {
                    batch.append(pending.tryReceive().getOrNull() ?: break)
                }
                io.withLock { write(batch.toString()) }
                changes.update { it + 1 }
            }
        }
    }

    override fun append(level: DiagnosticLevel, tag: String, message: String, error: Throwable?) {
        val text = if (error == null) message else "$message\n${error.stackTraceToString()}"
        pending.trySend(DiagnosticLines.encode(nowMillis(), sessionStartMillis, level, tag, text))
    }

    override suspend fun readForViewer(): List<DiagnosticRecord> {
        val text = io.withLock { readTail(ordered(), VIEWER_TAIL_BYTES) }.decodeToString()
        val notBefore = clearedAt.value
        return DiagnosticLines.records(text, notBefore)
    }

    override suspend fun readForExport(): String {
        val bytes = io.withLock {
            val all = ordered()
            val current = all.filter { it.session == sessionStartMillis }
            val previous = all.filter { it.session != sessionStartMillis }
            val currentBytes = current.sumOf { it.file.size }
            val previousBudget = maxOf(MIN_PREVIOUS_EXPORT_BYTES, EXPORT_BYTES - currentBytes).coerceAtMost(EXPORT_BYTES)
            val previousTail = readTail(previous, previousBudget)
            previousTail + readTail(current, EXPORT_BYTES - previousTail.size)
        }
        return DiagnosticLines.collapseRepeats(bytes.decodeToString(), formatTime)
    }

    override fun clearViewer() {
        clearedAt.value = nowMillis()
        changes.update { it + 1 }
    }

    private class Part(val session: Long, val index: Int, val file: DiagnosticLogFile)

    private suspend fun ordered(): List<Part> = guarded(emptyList()) {
        files.list().mapNotNull { file ->
            val match = FILE_NAME.matchEntire(file.name) ?: return@mapNotNull null
            val session = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
            val index = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
            Part(session, index, file)
        }.sortedWith(compareBy<Part> { it.session }.thenBy { it.index })
    }

    private suspend fun prepare() {
        var retained = 0L
        ordered().filter { it.session != sessionStartMillis }
            .groupBy(Part::session)
            .entries
            .sortedByDescending { it.key }
            .forEachIndexed { index, (_, parts) ->
                val size = parts.sumOf { it.file.size }
                if (index == 0 || retained + size <= PREVIOUS_RETENTION_BYTES) {
                    retained += size
                } else {
                    parts.forEach { guarded(Unit) { files.delete(it.file.name) } }
                }
            }
        partBytes = ordered().firstOrNull { it.session == sessionStartMillis && it.index == 0 }?.file?.size ?: 0L
    }

    private suspend fun write(text: String) {
        if (writeFailed) return
        val size = text.encodeToByteArray().size.toLong()
        if (partBytes > 0 && partBytes + size > PART_BYTES) {
            part += 1
            partBytes = 0L
            guarded(Unit) { files.delete(partName(part - PARTS_PER_SESSION)) }
        }
        // One failed write stops capture for this session, as on Android, rather than retrying on every line.
        if (guarded(false) { files.append(partName(part), text) }) partBytes += size else writeFailed = true
    }

    private fun partName(index: Int) = "s$sessionStartMillis-p$index.log"

    /** The last [budget] bytes across [parts] in order, never starting mid-line. */
    private suspend fun readTail(parts: List<Part>, budget: Long): ByteArray {
        if (budget <= 0) return ByteArray(0)
        val chunks = ArrayList<ByteArray>()
        var remaining = budget
        for (entry in parts.asReversed()) {
            if (remaining <= 0) break
            val length = entry.file.size
            if (length == 0L) continue
            val take = minOf(length, remaining)
            val bytes = guarded(ByteArray(0)) { files.readTail(entry.file.name, take) }
            val chunk = if (take < length) {
                val newline = bytes.indexOf('\n'.code.toByte())
                if (newline < 0) ByteArray(0) else bytes.copyOfRange(newline + 1, bytes.size)
            } else {
                bytes
            }
            chunks += chunk
            remaining -= take
        }
        val result = ByteArray(chunks.sumOf { it.size })
        var offset = 0
        chunks.asReversed().forEach { chunk ->
            chunk.copyInto(result, offset)
            offset += chunk.size
        }
        return result
    }

    /** The log never reports its own failures anywhere, so a broken directory cannot feed back into the log. */
    private suspend fun <T> guarded(fallback: T, block: suspend () -> T): T =
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            fallback
        }

    companion object {
        const val PART_BYTES = 1L shl 20
        const val PARTS_PER_SESSION = 2
        const val PREVIOUS_RETENTION_BYTES = 2L shl 20
        const val EXPORT_BYTES = 1_536L * 1024
        const val MIN_PREVIOUS_EXPORT_BYTES = 512L * 1024
        const val VIEWER_TAIL_BYTES = 256L * 1024
        private const val PENDING_LINES = 2_048
        private const val BATCH_CHARS = 64 * 1024
        private val FILE_NAME = Regex("""s(\d+)-p(\d+)\.log""")
    }
}
