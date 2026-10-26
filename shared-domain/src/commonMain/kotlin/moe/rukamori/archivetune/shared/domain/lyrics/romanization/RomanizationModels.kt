package moe.rukamori.archivetune.shared.domain.lyrics.romanization

/**
 * Which phonetic engines the user enabled. [aiHandled] means an AI provider supplies the romanisation
 * instead, which switches the built-in engines off for every line, as on Android.
 */
data class LyricsRomanizationPreferences(
    val romanizeJapanese: Boolean,
    val romanizeKorean: Boolean,
    val romanizeChinese: Boolean,
    val romanizeHindi: Boolean,
    val romanizeOther: Boolean,
    val aiHandled: Boolean = false,
) {
    val isEnabled: Boolean
        get() =
            !aiHandled &&
                (romanizeJapanese || romanizeKorean || romanizeChinese || romanizeHindi || romanizeOther)

    /** True when romanisation should be rendered at all, whichever engine produced it. */
    val showsRomanization: Boolean
        get() = aiHandled || isEnabled
}

/**
 * Phonetic readings for one row. [line] is the whole-row reading. [words] holds one entry per timed
 * word, and only when the word readings aligned with the timed words; a null entry means that word has
 * no reading.
 */
data class LyricsPhonetics(
    val line: String? = null,
    val words: List<String?>? = null,
)

/**
 * One morphological token. [position] is the UTF-16 offset of [surface] in the analysed line, which is
 * how word timings are matched back to tokens. [reading] is the dictionary reading, or null when unknown.
 */
data class JapaneseToken(
    val surface: String,
    val reading: String?,
    val position: Int,
)

/**
 * Japanese morphological analysis seam. Common code needs only surface, reading and offset. A host
 * without a dictionary passes no tokenizer, and Japanese then stays unromanised, as on Android when the
 * language pack is absent.
 */
interface JapaneseTokenizer {
    fun tokenize(text: String): List<JapaneseToken>
}

/**
 * Any-to-Latin transliteration, the role the Android `Any-Latin; Latin-ASCII` transform plays. Returns
 * null when the engine is unavailable or fails; the caller then produces no phonetic line, which is what
 * Android does when its transform fails.
 */
interface LyricsTransliterator {
    fun toLatin(text: String): String?
}
