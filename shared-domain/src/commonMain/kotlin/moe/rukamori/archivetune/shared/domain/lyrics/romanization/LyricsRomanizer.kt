package moe.rukamori.archivetune.shared.domain.lyrics.romanization

import moe.rukamori.archivetune.shared.domain.lyrics.LyricsLine
import moe.rukamori.archivetune.shared.domain.lyrics.unicode.LyricsScriptPredicates

/**
 * Built-in romanisation with its host-specific engines injected. [japaneseTokenizer] and [transliterator]
 * are optional: without a tokenizer Japanese is left as it is, and without a transliterator the generic
 * script engine produces nothing. Both match Android when its language pack or ICU is missing.
 */
class LyricsRomanizer(
    private val japaneseTokenizer: JapaneseTokenizer? = null,
    private val transliterator: LyricsTransliterator? = null,
) {
    /** Japanese romaji for [text], or [text] itself when no tokenizer is available. */
    fun romanizeJapanese(text: String): String {
        val tokenizer = japaneseTokenizer ?: return text
        return romanizeJapaneseWithTokenizer(text, tokenizer)
    }

    /**
     * The generic any-to-Latin pass. Null means the engine is unavailable, and a throwing [convert] yields
     * the source text, which [normalizeRomanizedText] then discards.
     */
    internal fun romanizeWithIcu(
        text: String,
        convert: (String) -> String? = { transliterator?.toLatin(it) },
    ): String? =
        try {
            convert(text)
        } catch (_: Exception) {
            text
        }

    /** The whole-line reading for [text], or null when no engine applies or the result adds nothing. */
    fun romanizeLyricsLine(
        text: String,
        preferences: LyricsRomanizationPreferences,
        sourceLanguage: String? = null,
        contextText: String = text,
    ): String? {
        if (!shouldRomanizeLyricsLine(text, preferences, sourceLanguage, contextText)) return null

        val romanized =
            when {
                preferences.romanizeJapanese && LyricsScriptPredicates.looksJapanese(text, sourceLanguage, contextText) ->
                    romanizeJapanese(text)
                preferences.romanizeKorean && LyricsScriptPredicates.isKorean(text) -> romanizeKorean(text)
                preferences.romanizeHindi && LyricsScriptPredicates.isHindi(text) -> romanizeHindi(text)
                preferences.romanizeChinese &&
                    !LyricsScriptPredicates.looksJapanese(text, sourceLanguage, contextText) &&
                    LyricsScriptPredicates.isChinese(text) -> romanizeWithIcu(text)
                preferences.romanizeOther && hasOtherRomanizableScript(text) -> romanizeWithIcu(text)
                else -> null
            }

        return normalizeRomanizedText(text, romanized)
    }

    /**
     * The reading for one word, chosen by the script of its whole line, so a Chinese or Japanese word in a
     * Japanese line follows the line's language.
     */
    fun romanizeLyricsWordWithLineContext(
        word: String,
        lineText: String,
        preferences: LyricsRomanizationPreferences,
        sourceLanguage: String? = null,
        contextText: String = lineText,
    ): String? {
        if (word.isBlank()) return null
        val romanized =
            when {
                preferences.romanizeJapanese && LyricsScriptPredicates.looksJapanese(lineText, sourceLanguage, contextText) ->
                    romanizeJapanese(word)
                preferences.romanizeKorean && LyricsScriptPredicates.isKorean(lineText) -> romanizeKorean(word)
                preferences.romanizeHindi && LyricsScriptPredicates.isHindi(lineText) -> romanizeHindi(word)
                preferences.romanizeChinese &&
                    !LyricsScriptPredicates.looksJapanese(lineText, sourceLanguage, contextText) &&
                    LyricsScriptPredicates.isChinese(lineText) -> romanizeWithIcu(word)
                preferences.romanizeOther && hasOtherRomanizableScript(lineText) -> romanizeWithIcu(word)
                else -> null
            }
        return normalizeRomanizedText(word, romanized)
    }

    /**
     * Readings for the words of one line. Japanese is tokenised once for the whole line, because one pass
     * is much cheaper than one per word.
     */
    fun romanizeWordsForLine(
        words: List<String>,
        lineText: String,
        preferences: LyricsRomanizationPreferences,
        sourceLanguage: String? = null,
        contextText: String = lineText,
    ): List<String?> {
        if (words.isEmpty()) return emptyList()
        if (!preferences.isEnabled) return words.map { null }
        if (preferences.romanizeJapanese && LyricsScriptPredicates.looksJapanese(lineText, sourceLanguage, contextText)) {
            return romanizeJapaneseWordsForLine(words, lineText, japaneseTokenizer)
        }
        return words.map { word ->
            romanizeLyricsWordWithLineContext(word, lineText, preferences, sourceLanguage, contextText)
        }
    }

    /**
     * Phonetics for one row. A provider reading replaces the built-in one when it applies. Word readings
     * are used only when they match the timed words in count; otherwise the row keeps just its line-level
     * reading. A line-only row never receives word readings, because its words have no timing.
     */
    fun phoneticsForLine(
        line: LyricsLine,
        preferences: LyricsRomanizationPreferences,
        contextText: String = line.text,
    ): LyricsPhonetics {
        if (!preferences.isEnabled) return LyricsPhonetics()
        val lineReading =
            providedRomanizedTextForLine(line, preferences)
                ?: romanizeLyricsLine(line.text, preferences, line.providerRomanizedLanguage, contextText)
        val words = line.words?.filter { !it.isBackground }
        val aligned =
            if (words.isNullOrEmpty()) {
                null
            } else {
                providedRomanizedWordsForLine(line, words.size, preferences)
                    ?: romanizeWordsForLine(
                        words = words.map { it.text },
                        lineText = line.text,
                        preferences = preferences,
                        sourceLanguage = line.providerRomanizedLanguage,
                        contextText = contextText,
                    ).takeIf { readings -> readings.any { !it.isNullOrBlank() } }
            }
        return LyricsPhonetics(line = lineReading, words = aligned)
    }
}
