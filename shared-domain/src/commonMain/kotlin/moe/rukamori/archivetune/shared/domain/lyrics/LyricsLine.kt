package moe.rukamori.archivetune.shared.domain.lyrics

/** One timed word. Times are seconds, matching the Android `WordTimestamp` model. */
data class LyricsWord(
    val text: String,
    val startTime: Double,
    val endTime: Double,
    val isBackground: Boolean = false,
)

/**
 * One displayed lyric row. A row with word timings is a [KaraokeLine]; line-only text stays a
 * [SyncedLine] and never receives invented word timings.
 */
sealed interface LyricsLine : Comparable<LyricsLine> {
    /** Line start in milliseconds. */
    val time: Long
    val text: String
    val agent: String?
    val isInstrumental: Boolean
    val durationMs: Long
    val providerRomanizedText: String?
    val providerRomanizedWords: List<String>?
    val providerRomanizedLanguage: String?
    val providerTranslationText: String?

    /** Per-word timings, or null for a line-only row. */
    val words: List<LyricsWord>?

    override fun compareTo(other: LyricsLine): Int = time.compareTo(other.time)
}

data class SyncedLine(
    override val time: Long,
    override val text: String,
    override val agent: String? = null,
    override val isInstrumental: Boolean = false,
    override val durationMs: Long = 0L,
    override val providerRomanizedText: String? = null,
    override val providerRomanizedWords: List<String>? = null,
    override val providerRomanizedLanguage: String? = null,
    override val providerTranslationText: String? = null,
) : LyricsLine {
    override val words: List<LyricsWord>?
        get() = null
}

data class KaraokeLine(
    override val time: Long,
    override val text: String,
    override val agent: String? = null,
    override val isInstrumental: Boolean = false,
    override val durationMs: Long = 0L,
    override val providerRomanizedText: String? = null,
    override val providerRomanizedWords: List<String>? = null,
    override val providerRomanizedLanguage: String? = null,
    override val providerTranslationText: String? = null,
    override val words: List<LyricsWord>,
) : LyricsLine {
    init {
        require(words.isNotEmpty()) { "A karaoke line needs at least one word timing" }
    }
}

object LyricsSentinels {
    /** Stored in place of lyrics when no provider had any. Same value as the Android `LyricsEntity` sentinel. */
    const val NOT_FOUND = "LYRICS_NOT_FOUND"
}

/**
 * Parse-time row before the sealed split. Carries the fields the Android `LyricsEntry` parsers fill,
 * so the parsers can merge, clamp and sort rows before they become [LyricsLine] values.
 */
internal data class LyricsDraft(
    val time: Long,
    val text: String,
    val words: List<LyricsWord>? = null,
    val agent: String? = null,
    val isInstrumental: Boolean = false,
    val durationMs: Long = 0L,
    val providerRomanizedText: String? = null,
    val providerRomanizedWords: List<String>? = null,
    val providerRomanizedLanguage: String? = null,
    val providerTranslationText: String? = null,
) {
    fun toLine(): LyricsLine {
        val timed = words
        return if (timed.isNullOrEmpty()) {
            SyncedLine(
                time = time,
                text = text,
                agent = agent,
                isInstrumental = isInstrumental,
                durationMs = durationMs,
                providerRomanizedText = providerRomanizedText,
                providerRomanizedWords = providerRomanizedWords,
                providerRomanizedLanguage = providerRomanizedLanguage,
                providerTranslationText = providerTranslationText,
            )
        } else {
            KaraokeLine(
                time = time,
                text = text,
                agent = agent,
                isInstrumental = isInstrumental,
                durationMs = durationMs,
                providerRomanizedText = providerRomanizedText,
                providerRomanizedWords = providerRomanizedWords,
                providerRomanizedLanguage = providerRomanizedLanguage,
                providerTranslationText = providerTranslationText,
                words = timed,
            )
        }
    }
}
