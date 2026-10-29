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
 * timeline, so lead and sync offsets belong to the feed position and never to the rows. [background] holds trailing
 * background vocals split out of [text] with their own [backgroundWords], and is null unless the host asked for it.
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
    val background: String? = null,
    val backgroundWords: List<LyricsWordTiming>? = null,
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
     * line has a time and no line has words, never becomes active and never seeks. With [splitBackground], background
     * vocals that trail the lead words leave the row text for [LyricsRenderRow.background]; background words placed
     * anywhere else stay inline, because moving them would break the lead word ranges.
     */
    fun build(
        lines: List<LyricsLine>,
        phonetics: List<LyricsPhonetics?> = emptyList(),
        splitBackground: Boolean = false,
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
            val split = if (splitBackground) splitTrailingBackground(line, sourceRanges) else null
            val (scanStart, scanEnd) = if (synced) {
                scanSpan(line, if (split == null) words else words.orEmpty() + split.words, nextStarts[index])
            } else {
                0L to 0L
            }
            LyricsRenderRow(
                index = index,
                timeMs = line.time,
                scanStartMs = scanStart,
                scanEndMs = scanEnd,
                text = split?.lead ?: line.text,
                phonetic = reading,
                translation = line.providerTranslationText?.takeIf { it.isNotBlank() },
                words = words,
                agent = line.agent,
                isInstrumental = line.isInstrumental,
                isSynced = synced,
                isSecondVoice = line.agent != null && line.agent != PRIMARY_AGENT,
                isRtl = UnicodeBidi.isRtlText(line.text),
                background = split?.background,
                backgroundWords = split?.words,
            )
        }
    }

    private class BackgroundSplit(val lead: String, val background: String, val words: List<LyricsWordTiming>)

    /**
     * Splits background vocals off the end of [line]'s text. They must start after the last located lead word, so
     * every lead range still points into the shortened text. Returns null when there is nothing to split or the
     * background text cannot be found there.
     */
    private fun splitTrailingBackground(line: LyricsLine, leadRanges: List<IntRange?>): BackgroundSplit? {
        val backing = line.words?.filter { it.isBackground }.orEmpty()
        if (backing.isEmpty()) return null
        val text = line.text
        val leadEnd = leadRanges.lastOrNull { it != null }?.let { it.last + 1 } ?: 0
        val first = backing.first().text.trim()
        if (first.isEmpty()) return null
        val start = text.indexOf(first, leadEnd)
        if (start <= 0) return null
        val lead = text.substring(0, start).trimEnd()
        val background = text.substring(start).trim()
        if (lead.isBlank() || background.isBlank()) return null
        var offset = 0
        val ranges = backing.map { word ->
            val fragment = word.text.trim()
            val at = if (fragment.isEmpty()) -1 else background.indexOf(fragment, offset)
            if (at < 0) null else (at until at + fragment.length).also { offset = it.last + 1 }
        }
        return BackgroundSplit(lead, background, wordTimings(backing, ranges))
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
