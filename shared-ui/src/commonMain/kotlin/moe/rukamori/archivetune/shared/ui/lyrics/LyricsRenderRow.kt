package moe.rukamori.archivetune.shared.ui.lyrics

import moe.rukamori.archivetune.shared.domain.lyrics.LyricsLine
import moe.rukamori.archivetune.shared.domain.lyrics.LyricsWord
import moe.rukamori.archivetune.shared.domain.lyrics.romanization.LyricsPhonetics
import moe.rukamori.archivetune.shared.domain.lyrics.romanization.timedLyricsPresentation
import moe.rukamori.archivetune.shared.domain.lyrics.unicode.UnicodeBidi
import kotlin.math.roundToLong

/**
 * One sung word in a row. Times are milliseconds on the lyrics timeline. [range] locates the word in the
 * row's text, and is null when the word has no place in that text.
 */
data class LyricsWordTiming(
    val startMs: Long,
    val endMs: Long,
    val range: IntRange?,
)

/**
 * A lyric row as the renderers draw it. [text] is the source line, and the word sweep follows it. [phonetic]
 * is the row's reading, drawn beneath the line, or null when no reading applies. [timeMs] stays canonical and
 * is what seeking uses. [scanStartMs] and [scanEndMs] bound the span in which the row is active, on the same
 * timeline, so lead and sync offsets belong to the feed position and never to the rows.
 */
data class LyricsRenderRow(
    val index: Int,
    val timeMs: Long,
    val scanStartMs: Long,
    val scanEndMs: Long,
    val text: String,
    val phonetic: String?,
    val translation: String?,
    val words: List<LyricsWordTiming>?,
    val agent: String?,
    val isInstrumental: Boolean,
    val isSynced: Boolean,
    val isSecondVoice: Boolean,
    val isRtl: Boolean,
)

/** Builds render rows from canonical lines and the phonetics a presentation supplies for them. */
object LyricsRenderRows {
    /** A final line-only row stays lit this long when nothing follows it. */
    const val DEFAULT_LINE_HOLD_MS = 5_000L

    /** A sung word whose end time is missing or empty is held this long. */
    private const val DEFAULT_WORD_MS = 500L

    private const val PRIMARY_AGENT = "v1"

    /**
     * [phonetics] is aligned to [lines] by index, and a missing entry means no reading. Unsynced text, where no
     * line has a time and no line has words, never becomes active and never seeks.
     */
    fun build(
        lines: List<LyricsLine>,
        phonetics: List<LyricsPhonetics?> = emptyList(),
    ): List<LyricsRenderRow> {
        val synced = lines.any { it.time > 0L || it.words != null }
        val nextStarts = nextDistinctStarts(lines)
        return lines.mapIndexed { index, line ->
            val sung = line.words?.filter { !it.isBackground }.orEmpty()
            // The sweep follows the source text, so word ranges are located in it rather than in a reading.
            val sourceRanges = timedLyricsPresentation(line, LyricsPhonetics()).ranges
            val words = if (sung.isEmpty()) null else wordTimings(sung, sourceRanges)
            val reading = phonetics.getOrNull(index)
                ?.let { timedLyricsPresentation(line, it).text }
                ?.takeIf { it.isNotBlank() && it != line.text }
            val (scanStart, scanEnd) = if (synced) {
                scanSpan(line, words, nextStarts[index])
            } else {
                0L to 0L
            }
            LyricsRenderRow(
                index = index,
                timeMs = line.time,
                scanStartMs = scanStart,
                scanEndMs = scanEnd,
                text = line.text,
                phonetic = reading,
                translation = line.providerTranslationText?.takeIf { it.isNotBlank() },
                words = words,
                agent = line.agent,
                isInstrumental = line.isInstrumental,
                isSynced = synced,
                isSecondVoice = line.agent != null && line.agent != PRIMARY_AGENT,
                isRtl = UnicodeBidi.isRtlText(line.text),
            )
        }
    }

    /** One active-span scanner for a row list. Rows that are not synced have empty spans and never match. */
    fun scanner(rows: List<LyricsRenderRow>): ActiveLineScanner =
        ActiveLineScanner(
            starts = LongArray(rows.size) { rows[it].scanStartMs },
            ends = LongArray(rows.size) { rows[it].scanEndMs },
        )

    private fun wordTimings(
        sung: List<LyricsWord>,
        ranges: List<IntRange?>,
    ): List<LyricsWordTiming> =
        sung.mapIndexed { index, word ->
            val start = secondsToMs(word.startTime)
            val declaredEnd = secondsToMs(word.endTime)
            val nextStart = sung.getOrNull(index + 1)?.let { secondsToMs(it.startTime) }
            val end = when {
                declaredEnd > start -> declaredEnd
                nextStart != null && nextStart > start -> nextStart
                else -> start + DEFAULT_WORD_MS
            }
            LyricsWordTiming(startMs = start, endMs = end, range = ranges.getOrNull(index))
        }

    private fun scanSpan(
        line: LyricsLine,
        words: List<LyricsWordTiming>?,
        nextStart: Long?,
    ): Pair<Long, Long> {
        val start = line.time
        val wordsEnd = words?.maxOfOrNull { it.endMs } ?: start
        val end = when {
            line.durationMs > 0L -> start + line.durationMs
            wordsEnd > start -> wordsEnd
            nextStart != null -> nextStart
            else -> start + DEFAULT_LINE_HOLD_MS
        }
        return start to maxOf(end, start + 1L)
    }

    /** For each line, the next start time that differs from its own, or null when no later line starts. */
    private fun nextDistinctStarts(lines: List<LyricsLine>): List<Long?> {
        val result = arrayOfNulls<Long>(lines.size)
        for (index in lines.indices) {
            var next = index + 1
            while (next < lines.size && lines[next].time == lines[index].time) next++
            result[index] = lines.getOrNull(next)?.time
        }
        return result.toList()
    }

    private fun secondsToMs(seconds: Double): Long = (seconds * 1_000.0).roundToLong()
}
