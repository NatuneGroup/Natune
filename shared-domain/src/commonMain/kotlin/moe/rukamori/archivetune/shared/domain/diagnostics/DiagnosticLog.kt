package moe.rukamori.archivetune.shared.domain.diagnostics

import kotlinx.coroutines.flow.StateFlow
import moe.rukamori.archivetune.shared.domain.feedback.DiagnosticRedaction

/** The five levels the log viewer filters on, with the one-letter labels logcat prints. */
enum class DiagnosticLevel(val label: Char) {
    VERBOSE('V'),
    DEBUG('D'),
    INFO('I'),
    WARNING('W'),
    ERROR('E'),
    ;

    companion object {
        fun of(label: Char): DiagnosticLevel = entries.firstOrNull { it.label == label } ?: ERROR
    }
}

/** One logical log call, folded back together from its stored lines. [id] is unique within one read. */
data class DiagnosticRecord(
    val id: String,
    val timeMillis: Long,
    val level: DiagnosticLevel,
    val tag: String?,
    val message: String,
)

/**
 * The persistent, redacted log of this app's own process, kept across launches so a report also carries the end of the
 * previous run. Hosts back it with rotating files. Every line is redacted before it is written.
 */
interface DiagnosticLogStore {
    /** Advances whenever a line is written or the viewer is cleared; a viewer re-reads only when it changes. */
    val revision: StateFlow<Long>

    fun append(level: DiagnosticLevel, tag: String, message: String, error: Throwable? = null)

    /** The newest records for the viewer, oldest first, without the ones cleared from the viewer. */
    suspend fun readForViewer(): List<DiagnosticRecord>

    /** Earlier sessions first, newest line last, capped for a report. */
    suspend fun readForExport(): String

    /** Hides what was logged so far from the viewer. The files stay, as on Android, so a later report still has them. */
    fun clearViewer()
}

/**
 * The stored line format: `<epochMillis> <session> <L>/<tag>: <text>`, one line per message line with the header repeated,
 * the way logcat's threadtime format prints a multi-line call. Lines that do not parse are kept as markers.
 */
object DiagnosticLines {
    private val line = Regex("""^(\d{1,15}) (\d+) ([VDIWE])/([^:]*): ?(.*)$""")
    private const val REPEAT_KEY_MAX_LENGTH = 500
    private val repeatDigits = Regex("""\d+""")

    fun encode(timeMillis: Long, session: Long, level: DiagnosticLevel, tag: String, message: String): String {
        val safeTag = tag.replace(':', '_').replace('\n', ' ').trim().ifEmpty { "-" }
        val redacted = DiagnosticRedaction.redact(message)
        return buildString {
            redacted.lineSequence().forEach { text ->
                append(timeMillis).append(' ').append(session).append(' ')
                append(level.label).append('/').append(safeTag).append(": ").append(text).append('\n')
            }
        }
    }

    fun marker(text: String): String = DiagnosticRedaction.redact(text).replace('\n', ' ') + "\n"

    private data class Parsed(val time: Long, val session: Long, val level: Char, val tag: String, val text: String)

    private fun parse(text: String): Parsed? {
        val match = line.matchEntire(text) ?: return null
        val (time, session, level, tag, message) = match.destructured
        return Parsed(time.toLongOrNull() ?: return null, session.toLongOrNull() ?: 0L, level[0], tag, message)
    }

    /** Folds consecutive lines of one call back into one record. Markers are dropped from the viewer. */
    fun records(text: String, notBefore: Long = Long.MIN_VALUE): List<DiagnosticRecord> {
        val records = ArrayList<DiagnosticRecord>()
        val occurrences = HashMap<String, Int>()
        var pending: Parsed? = null
        val message = StringBuilder()
        fun flush() {
            val current = pending ?: return
            pending = null
            if (current.time >= notBefore) {
                val body = message.toString()
                val base = "${current.time}_${current.level}_${current.tag}_${body.hashCode()}"
                val occurrence = occurrences.getOrElse(base) { 0 }
                occurrences[base] = occurrence + 1
                records += DiagnosticRecord(
                    id = "${base}_$occurrence",
                    timeMillis = current.time,
                    level = DiagnosticLevel.of(current.level),
                    tag = current.tag.takeIf { it.isNotBlank() && it != "-" },
                    message = body,
                )
            }
            message.clear()
        }
        text.lineSequence().forEach { raw ->
            if (raw.isBlank()) return@forEach
            val parsed = parse(raw)
            if (parsed == null) {
                flush()
                return@forEach
            }
            val current = pending
            if (current != null && current.time == parsed.time && current.session == parsed.session &&
                current.level == parsed.level && current.tag == parsed.tag
            ) {
                message.append('\n').append(parsed.text)
            } else {
                flush()
                pending = parsed
                message.append(parsed.text)
            }
        }
        flush()
        return records
    }

    /**
     * Report text: consecutive calls that differ only in their numbers collapse into one line plus a repeat count, so a
     * retry storm does not push the useful context out of the cap.
     */
    fun collapseRepeats(text: String, formatTime: (Long) -> String): String = buildString {
        val lines = text.lines().filter { it.isNotBlank() }
        fun key(raw: String): String? {
            val parsed = parse(raw) ?: return null
            val normalized = if (parsed.text.length <= REPEAT_KEY_MAX_LENGTH) repeatDigits.replace(parsed.text, "#") else parsed.text
            return "${parsed.session}|${parsed.level}|${parsed.tag}|$normalized"
        }
        var index = 0
        while (index < lines.size) {
            val raw = lines[index]
            val parsed = parse(raw)
            val repeatKey = key(raw)
            var end = index + 1
            if (repeatKey != null) {
                while (end < lines.size && key(lines[end]) == repeatKey) end++
            }
            if (parsed == null) {
                appendLine(raw)
            } else {
                append(formatTime(parsed.time)).append(' ').append(parsed.level).append('/').append(parsed.tag)
                    .append(": ").appendLine(parsed.text)
            }
            val repeats = end - index - 1
            if (repeats > 0) {
                val last = parse(lines[end - 1])?.time
                append("    … repeated ").append(repeats).append("× more")
                if (last != null) append(", last at ").append(formatTime(last))
                appendLine()
            }
            index = end
        }
    }
}

/** The viewer's search and level chips: a record shows when its level is selected and the query is in its tag or text. */
fun filterDiagnosticRecords(
    records: List<DiagnosticRecord>,
    query: String,
    levels: Set<DiagnosticLevel>,
): List<DiagnosticRecord> {
    val normalized = query.trim()
    return records.filter { record ->
        record.level in levels &&
            (normalized.isEmpty() ||
                record.tag?.contains(normalized, ignoreCase = true) == true ||
                record.message.contains(normalized, ignoreCase = true))
    }
}

/** Share and copy text, one `[time] L/tag: message` line per record, as the Android viewer formats it. */
fun formatDiagnosticRecords(records: List<DiagnosticRecord>, formatTime: (Long) -> String): String =
    records.joinToString(separator = "\n") { record ->
        "[${formatTime(record.timeMillis)}] ${record.level.label}/${record.tag.orEmpty()}: ${record.message}"
    }
