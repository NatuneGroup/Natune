/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.lyrics

import kotlin.math.abs
import kotlin.math.sqrt

/** Format sniffing, word-sync classification and display text for stored or fetched lyrics. */
object LyricsDetection {
    private val SYNCED_LINE_PREFIX_REGEX = Regex("""^(\s*(?:\[[^\]]+])+)(\s*)(.*?)(\s*)$""")
    private val TTML_SPAN_REGEX =
        Regex("""<span\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val TTML_BEGIN_ATTRIBUTE_REGEX = Regex("""\bbegin\s*=""", RegexOption.IGNORE_CASE)
    private val TTML_END_ATTRIBUTE_REGEX = Regex("""\b(?:end|dur)\s*=""", RegexOption.IGNORE_CASE)

    fun isTtml(lyrics: String): Boolean {
        val trimmed = LyricsNormalizer.normalize(lyrics)
        if (!trimmed.startsWith("<")) return false

        return trimmed.contains("<tt", ignoreCase = true) ||
            trimmed.contains("http://www.w3.org/ns/ttml", ignoreCase = true)
    }

    /**
     * True when [lyrics] holds an actual translation: an AI-annotated TTML document, or a line-synced
     * document that repeats a timestamp prefix (the translated row written under the original).
     */
    fun hasTranslation(lyrics: String): Boolean {
        if (lyrics.isBlank()) return false
        if (isTtml(lyrics)) {
            return lyrics.contains("data-archivetune", ignoreCase = true) &&
                lyrics.contains("translation", ignoreCase = true)
        }
        val seenPrefixes = HashSet<String>()
        lyrics.lineSequence().forEach { line ->
            val match = SYNCED_LINE_PREFIX_REGEX.matchEntire(line) ?: return@forEach
            if (!seenPrefixes.add(match.groupValues[1])) return true
        }
        return false
    }

    fun isLineSyncedLrc(lyrics: String): Boolean =
        QrcParser.isQrc(LyricsNormalizer.normalize(lyrics)) ||
            lyrics.lineSequence().any { line ->
                val trimmedLine = line.trim()
                LrcParser.LINE_REGEX.matches(trimmedLine) || LrcParser.YRC_LINE_REGEX.matches(trimmedLine)
            }

    fun hasWordSyncedLyrics(lyrics: String): Boolean {
        val normalized = LyricsNormalizer.normalize(lyrics)
        if (QrcParser.isQrc(normalized)) return QrcParser.hasWordTimings(normalized)
        if (isTtml(normalized)) {
            return TTML_SPAN_REGEX.findAll(normalized).any { match ->
                TTML_BEGIN_ATTRIBUTE_REGEX.containsMatchIn(match.value) &&
                    TTML_END_ATTRIBUTE_REGEX.containsMatchIn(match.value)
            }
        }
        // Enhanced LRC needs BOTH a line-level [mm:ss.xxx] tag AND an inline word stamp on the same
        // line, so that plain text containing angle brackets (e.g. "<3") is never word-synced.
        return normalized.lineSequence().any { line ->
            LrcParser.LINE_REGEX.containsMatchIn(line) &&
                (
                    EnhancedLrcWords.WORD_TIME_REGEX.containsMatchIn(line) ||
                        EnhancedLrcWords.INLINE_MILLISECONDS_TIME_REGEX.containsMatchIn(line)
                )
        }
    }

    /**
     * True when a stored remote row is something the word-synced preference can still improve: the
     * row holds real text without word-level timing. Only a row fetched from a remote provider is
     * eligible; user edits, AI translations and hand-picked results are never replaced by this.
     * The sentinel and blanks are excluded because the fetch gates already retry them.
     */
    fun needsWordSyncedUpgrade(
        prioritizeWordSynced: Boolean,
        isRemoteSource: Boolean,
        storedLyrics: String?,
    ): Boolean =
        prioritizeWordSynced &&
            isRemoteSource &&
            storedLyrics != null &&
            storedLyrics != LyricsSentinels.NOT_FOUND &&
            storedLyrics.isNotBlank() &&
            !hasWordSyncedLyrics(storedLyrics)

    /**
     * True when [line] has real, per-word timing that should drive karaoke animation. Rejects the
     * synthetic timings providers fake by spacing words evenly with identical durations.
     */
    fun hasTrueWordSync(line: LyricsLine): Boolean {
        val raw = line.words ?: return false
        val words = raw.filter { it.text.isNotBlank() }
        if (words.isEmpty()) return false
        if (words.size == 1) {
            // A single word counts as word-synced when its span has a real, positive duration.
            val only = words.first()
            return (only.endTime - only.startTime) > 0.0
        }

        val startTimes = words.map { it.startTime }
        val endTimes = words.map { it.endTime }

        // All start times identical, or all end times identical, means no per-word timing.
        if (startTimes.distinct().size == 1) return false
        if (endTimes.distinct().size == 1) return false

        val durations = words.map { (it.endTime - it.startTime).coerceAtLeast(0.0) }
        if (durations.all { it <= 0.0 }) return false

        val lineStart = startTimes.min()
        val lineEnd = endTimes.max()
        val lineDuration = lineEnd - lineStart

        // Perfectly even start spacing is what providers produce when they fake word sync. Real
        // singing can be regular too, so identical durations are also required before rejecting.
        if (lineDuration > 0.0 && words.size > 2) {
            val tolerance = 0.05
            val isEvenlyDistributed =
                startTimes.indices.all { i ->
                    val expected = lineStart + (lineDuration * i / (words.size - 1))
                    abs(startTimes[i] - expected) < tolerance
                }
            if (isEvenlyDistributed) {
                val positiveDurations = durations.filter { it > 0.0 }
                if (positiveDurations.size >= 3) {
                    val avg = positiveDurations.average()
                    if (avg > 0.0) {
                        val variance = positiveDurations.map { (it - avg) * (it - avg) }.average()
                        val stddev = sqrt(variance)
                        if (stddev / avg < 0.1) return false
                    }
                }
            }
        }

        return true
    }

    /** Visible text: one row per non-empty line, whitespace collapsed, format markup removed. */
    fun displayText(lyrics: String): String {
        val raw = LyricsNormalizer.normalize(lyrics)
        if (raw.isEmpty() || raw == LyricsSentinels.NOT_FOUND) return ""

        val visibleLines: List<String> =
            when {
                isTtml(raw) -> runCatching { LyricsParser.parseTtml(raw).map { it.text } }.getOrElse { emptyList() }
                isLineSyncedLrc(raw) -> runCatching { LyricsParser.parse(raw).map { it.text } }.getOrElse { emptyList() }
                raw.startsWith("<") -> emptyList()
                else -> raw.lines().map { LrcParser.cleanInlineWordTimingText(it) }
            }

        return visibleLines
            .map { line -> line.collapseLyricsWhitespace().trimLyricsSpace() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    fun hasMeaningfulContent(lyrics: String): Boolean = displayText(lyrics).isNotEmpty()

    /** The normalised lyrics when they have visible content, otherwise the not-found sentinel. */
    fun lyricsOrNotFound(lyrics: String): String {
        val normalized = LyricsNormalizer.normalize(lyrics)
        return normalized.takeIf(::hasMeaningfulContent) ?: LyricsSentinels.NOT_FOUND
    }
}
