package moe.rukamori.archivetune.shared.domain.lyrics

import kotlin.math.roundToLong

/**
 * Word timings carried by inline `<mm:ss.xxx>` stamps (`[00:12.000]<00:12.000>Hello <00:12.500>world`).
 * Enhanced LRC marks each word START only, so word ends are inferred and then clamped.
 */
internal object EnhancedLrcWords {
    val WORD_TIME_REGEX = Regex("""<\d{1,3}:\d{2}(?:[.:]\d{2,3})?>""")
    val INLINE_MILLISECONDS_TIME_REGEX = Regex("""<\d{1,8}(?:,\d{1,8})?>""")
    private val WORD_STAMP_REGEX = Regex("""(<\d{1,3}:\d{2}(?:[.:]\d{2,3})?>)([^<]*)""")

    /** End given to the last word of a line whose end no further stamp pins down. */
    const val LAST_WORD_DEFAULT_DURATION_MS = 600L

    /** Floor so a word is never lit for a single frame. */
    const val MIN_WORD_DURATION_MS = 40L

    /** Assumed length of the final line, which has no following stamp to clamp against. */
    const val TRAILING_LINE_DURATION_MS = 4_000L

    /**
     * Parses the `<mm:ss.xxx>` fraction of an enhanced-LRC word stamp into milliseconds, or null
     * when the stamp is malformed.
     */
    fun parseStampMs(stamp: String): Long? {
        val body = stamp.removePrefix("<").removeSuffix(">")
        val parts = body.split(':', '.')
        if (parts.size < 2) return null
        val minutes = parts[0].toLongOrNull() ?: return null
        val seconds = parts[1].toLongOrNull() ?: return null
        val millis =
            parts.getOrNull(2)?.let { fraction ->
                when (fraction.length) {
                    1 -> fraction.toLongOrNull()?.times(100)
                    2 -> fraction.toLongOrNull()?.times(10)
                    else -> fraction.take(3).padEnd(3, '0').toLongOrNull()
                }
            } ?: 0L
        return minutes * MINUTE_MS + seconds * SECOND_MS + millis
    }

    /**
     * Extracts per-word timings from a line carrying inline stamps, or null when the line has fewer
     * than two stamped words. One stamp is not word sync, and a single word says nothing about which
     * word is being sung.
     *
     * The stamp marks a word's start and the NEXT stamp ends it. The last word gets
     * [LAST_WORD_DEFAULT_DURATION_MS] and is later clamped to the next line by [clampLastWordEnds].
     */
    fun extractWordTimestamps(rawText: String): List<LyricsWord>? {
        if (!WORD_TIME_REGEX.containsMatchIn(rawText)) return null
        val stamps = WORD_STAMP_REGEX.findAll(rawText).toList()
        if (stamps.size < 2) return null

        val words = mutableListOf<LyricsWord>()
        stamps.forEachIndexed { index, stamp ->
            val startMs = parseStampMs(stamp.groupValues[1]) ?: return@forEachIndexed
            val normalizedText = stamp.groupValues[2].collapseLyricsWhitespace()
            val wordText = normalizedText.trimLyricsSpace()
            if (wordText.isEmpty()) return@forEachIndexed
            // Keep one trailing space when the source carries it: verbatim word renderers lay each
            // word out as-is, so trimming the edge gap collapsed "Hello world" into "Helloworld".
            val textWithGap = if (normalizedText.endsWith(" ")) "$wordText " else wordText
            val nextStartMs = stamps.getOrNull(index + 1)?.let { parseStampMs(it.groupValues[1]) }
            val endMs = nextStartMs ?: (startMs + LAST_WORD_DEFAULT_DURATION_MS)
            words.add(
                LyricsWord(
                    text = textWithGap,
                    startTime = startMs / 1000.0,
                    endTime = maxOf(endMs, startMs + MIN_WORD_DURATION_MS) / 1000.0,
                ),
            )
        }
        return words.takeIf { it.size >= 2 }
    }

    /**
     * Pulls the end of each line's last word back to the start of the next line. Only guessed tails
     * are touched; a word end from any other producer is real, and [hasGuessedLastWordEnd] tells the
     * two apart.
     */
    fun clampLastWordEnds(entries: List<LyricsDraft>): List<LyricsDraft> =
        entries.mapIndexed { index, entry ->
            val words = entry.words ?: return@mapIndexed entry
            if (words.isEmpty() || !hasGuessedLastWordEnd(words)) return@mapIndexed entry
            val nextStartMs =
                entries
                    .getOrNull(index + 1)
                    ?.takeIf { it.time > entry.time }
                    ?.time
                    ?: (entry.time + TRAILING_LINE_DURATION_MS)
            val lastEndMs = (words.last().endTime * 1000.0).toLong()
            if (lastEndMs > nextStartMs) {
                val clamped = words.last().copy(endTime = nextStartMs / 1000.0)
                entry.copy(words = words.dropLast(1) + clamped)
            } else {
                entry
            }
        }

    /**
     * True when [words] ends in the tail [extractWordTimestamps] invents for a last word. That is the
     * extractor's signature (the duration is not read from anywhere), so it doubles as the provenance
     * check that keeps the clamp off real word timings.
     */
    private fun hasGuessedLastWordEnd(words: List<LyricsWord>): Boolean {
        val lastWord = words.last()
        val startMs = (lastWord.startTime * 1000.0).roundToLong()
        val guessedDurationMs = LAST_WORD_DEFAULT_DURATION_MS.coerceAtLeast(MIN_WORD_DURATION_MS)
        return (lastWord.endTime * 1000.0).roundToLong() - startMs == guessedDurationMs
    }
}
