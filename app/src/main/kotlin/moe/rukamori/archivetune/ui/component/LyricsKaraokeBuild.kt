package moe.rukamori.archivetune.ui.component

import com.mocharealm.accompanist.lyrics.core.model.ISyncedLine
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeAlignment
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeSyllable
import moe.rukamori.archivetune.lyrics.LyricsEntry
import moe.rukamori.archivetune.lyrics.LyricsPhonetics
import moe.rukamori.archivetune.lyrics.LyricsUtils.hasTrueWordSync
import moe.rukamori.archivetune.lyrics.LyricsUtils.providedTranslationTextForEntry
import moe.rukamori.archivetune.lyrics.WordTimestamp
import kotlin.math.roundToInt

/**
 * How long a line-synced line may keep focus after its own timestamp before it hands over to the
 * interlude dots. A shorter gap to the next line simply holds the line until that line starts.
 */
private const val LINE_SYNCED_MAX_FOCUS_HOLD_MS = 5_000L

/** The last line has no successor to butt against, so give it a fixed window. */
private const val LINE_SYNCED_TRAILING_LINE_DURATION_MS = 4_000L

/** The TTML agent that marks the second voice — the backing vocal a lead line is answered by. */
private const val SECOND_VOICE_AGENT = "v2"

/** A line's words, split into the lead voice and the background vocals that answer it. */
internal data class LyricsVoiceSplit(
    val lead: List<WordTimestamp>,
    val background: List<WordTimestamp>,
) {
    val isBackgroundOnly: Boolean get() = lead.isEmpty() && background.isNotEmpty()
    val isMixed: Boolean get() = lead.isNotEmpty() && background.isNotEmpty()
}

/** The lead and background words of [entry], keeping the document's order inside each voice. */
internal fun splitVoices(words: List<WordTimestamp>?): LyricsVoiceSplit {
    val all = words.orEmpty()
    return LyricsVoiceSplit(
        lead = all.filter { !it.isBackground },
        background = all.filter { it.isBackground },
    )
}

/** The alignment a line's text is drawn with: the second voice answers from the end of the row. */
internal fun karaokeAlignmentOf(entry: LyricsEntry): KaraokeAlignment =
    if (entry.agent?.lowercase() == SECOND_VOICE_AGENT) KaraokeAlignment.End else KaraokeAlignment.Start

/**
 * The [SyncedLyrics] the Enhanced pane draws, one row per timed entry.
 *
 * A word-synced line becomes a [KaraokeLine.MainKaraokeLine] carrying its own syllable spans; its
 * background vocals ride along as one [KaraokeLine.AccompanimentKaraokeLine] with their own timings,
 * which is what makes them draw as the smaller second line and sweep over their own spans.
 *
 * A line that is *only* background vocals — a featured artist's answer, e.g. "gimme some day" — is a
 * secondary line in its own right, so it is published as that same accompaniment row instead of being
 * promoted to a lead line at full size. A second voice line (`ttm:agent="v2"`) is drawn the same way:
 * it answers the lead, so it belongs under it, smaller and to the end of the row.
 *
 * A line-synced LRC line becomes a [com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine];
 * when it carries a reading it becomes a [KaraokeLine.MainKaraokeLine] whose syllables are only pegs
 * for that reading (see [buildAlternateLineSyncedLrcLine]), which the renderers never sweep.
 */
internal fun buildSyncedLyrics(
    entries: List<LyricsEntry>,
    romanizationMap: Map<Int, LyricsPhonetics>,
): SyncedLyrics {
    if (entries.isEmpty()) return SyncedLyrics(emptyList())
    val lines = mutableListOf<ISyncedLine>()

    entries.forEachIndexed { index, entry ->
        if (entry.time < 0L) return@forEachIndexed
        if (entry.isInstrumental) return@forEachIndexed
        if (entry.text.isBlank() && entry.words.isNullOrEmpty()) return@forEachIndexed

        if (entry.words != null && hasTrueWordSync(entry)) {
            val translation = providedTranslationTextForEntry(entry)
            val voices = splitVoices(entry.words)
            val alignment = karaokeAlignmentOf(entry)
            val wordPhonetics = romanizationMap[index]?.words.orEmpty()
            val linePhonetic = romanizationMap[index]?.line.takeIf { wordPhonetics.isEmpty() }

            // A row that answers the lead voice: background-only lines and the document's second voice.
            if (voices.isBackgroundOnly || alignment == KaraokeAlignment.End) {
                val syllables = voices.background.ifEmpty { voices.lead }.toAlternateKaraokeSyllables(emptyList())
                val start = syllables.first().start
                val end = syllables.last().end
                if (end > start) {
                    lines +=
                        KaraokeLine.AccompanimentKaraokeLine(
                            syllables = syllables,
                            translation = translation,
                            alignment = alignment,
                            start = start,
                            end = end,
                            phonetic = null,
                        )
                }
                return@forEachIndexed
            }

            val mainSyllables = voices.lead.toAlternateKaraokeSyllables(wordPhonetics)
            val lineStart = mainSyllables.first().start
            val lineEnd = mainSyllables.last().end
            if (lineEnd <= lineStart) return@forEachIndexed

            val accompanimentLines =
                if (voices.isMixed) {
                    val bgSyllables = voices.background.toAlternateKaraokeSyllables(emptyList())
                    val bgStart = bgSyllables.first().start
                    val bgEnd = bgSyllables.last().end
                    if (bgEnd > bgStart) {
                        listOf(
                            KaraokeLine.AccompanimentKaraokeLine(
                                syllables = bgSyllables,
                                translation = null,
                                alignment = alignment,
                                start = bgStart,
                                end = bgEnd,
                                phonetic = null,
                            ),
                        )
                    } else {
                        null
                    }
                } else {
                    null
                }

            lines +=
                KaraokeLine.MainKaraokeLine(
                    syllables = mainSyllables,
                    translation = translation,
                    alignment = alignment,
                    start = lineStart,
                    end = lineEnd,
                    phonetic = linePhonetic,
                    accompanimentLines = accompanimentLines,
                )
        } else {
            val nextEntry = entries.getOrNull(index + 1)
            // LRC has no line ends, so a line is sung until the next one starts. Only a real instrumental break ends
            // a line early, because the interlude dots draw the silence between a line's end and the next start.
            val lineEnd =
                if (nextEntry != null && nextEntry.time > entry.time) {
                    val handOver = entry.time + LINE_SYNCED_MAX_FOCUS_HOLD_MS
                    if (nextEntry.time - handOver >= enhancedLineSyncedBreakMs) {
                        // The silence after the hold is at least the dots' minimum break, so the dots draw there.
                        handOver.toInt()
                    } else {
                        nextEntry.time.toInt()
                    }
                } else {
                    (entry.time + LINE_SYNCED_TRAILING_LINE_DURATION_MS).toInt()
                }
            lines.add(
                buildAlternateLineSyncedLrcLine(
                    entry = entry,
                    romanizedText = romanizationMap[index]?.line,
                    start = entry.time.toInt(),
                    end = lineEnd,
                ),
            )
        }
    }

    return SyncedLyrics(lines = lines)
}

/** One word per syllable, clipped to the next word's start so two words never both sweep. */
internal fun List<WordTimestamp>.toAlternateKaraokeSyllables(phonetics: List<String?>): List<KaraokeSyllable> =
    mapIndexed { index, word ->
        val start = word.startTime.toMilliseconds()
        val nextStart = getOrNull(index + 1)?.startTime?.toMilliseconds()
        val rawEnd = word.endTime.toMilliseconds()
        val end =
            nextStart
                ?.let { minOf(rawEnd, it) }
                ?: rawEnd

        KaraokeSyllable(
            content = word.text,
            start = start,
            end = end.coerceAtLeast(start + ALTERNATE_MIN_KARAOKE_SYLLABLE_DURATION_MS),
            phonetic = phonetics.getOrNull(index),
        )
    }

private fun Double.toMilliseconds(): Int = (this * 1000.0).roundToInt().coerceAtLeast(0)
