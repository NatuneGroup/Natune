/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.lyrics.romanization

import moe.rukamori.archivetune.shared.domain.lyrics.LyricsLine
import moe.rukamori.archivetune.shared.domain.lyrics.unicode.LyricsScriptPredicates
import moe.rukamori.archivetune.shared.domain.lyrics.unicode.UnicodeScript
import moe.rukamori.archivetune.shared.domain.lyrics.unicode.UnicodeScripts

/**
 * Scripts that the generic transliterator must not claim. Each has a dedicated engine, or needs no
 * romanisation at all.
 */
private val OTHER_ROMANIZATION_EXCLUDED_SCRIPTS: Set<UnicodeScript> =
    setOf(
        UnicodeScript.LATIN,
        UnicodeScript.COMMON,
        UnicodeScript.INHERITED,
        UnicodeScript.HAN,
        UnicodeScript.HIRAGANA,
        UnicodeScript.KATAKANA,
        UnicodeScript.HANGUL,
        UnicodeScript.DEVANAGARI,
    )

/**
 * Whether a built-in engine should romanise [text] under [preferences]. The language hint and the
 * surrounding [contextText] decide between Japanese and Chinese, because Han alone is ambiguous.
 */
fun shouldRomanizeLyricsLine(
    text: String,
    preferences: LyricsRomanizationPreferences,
    sourceLanguage: String? = null,
    contextText: String = text,
): Boolean {
    if (!preferences.isEnabled || text.isBlank()) return false

    return when {
        preferences.romanizeJapanese && LyricsScriptPredicates.looksJapanese(text, sourceLanguage, contextText) -> true
        preferences.romanizeKorean && LyricsScriptPredicates.isKorean(text) -> true
        preferences.romanizeHindi && LyricsScriptPredicates.isHindi(text) -> true
        preferences.romanizeChinese &&
            !LyricsScriptPredicates.looksJapanese(text, sourceLanguage, contextText) &&
            LyricsScriptPredicates.isChinese(text) -> true
        preferences.romanizeOther && hasOtherRomanizableScript(text) -> true
        else -> false
    }
}

/**
 * Whether [text] has any script a romanisation could apply to, whichever engines are enabled. The AI path
 * uses this, because it has one on/off switch and must not spend a request on Latin-only lyrics.
 */
fun hasRomanizableScript(text: String): Boolean {
    if (text.isBlank()) return false
    return LyricsScriptPredicates.looksJapanese(text) ||
        LyricsScriptPredicates.isKorean(text) ||
        LyricsScriptPredicates.isHindi(text) ||
        LyricsScriptPredicates.isChinese(text) ||
        hasOtherRomanizableScript(text)
}

/** True when a letter belongs to a script outside [OTHER_ROMANIZATION_EXCLUDED_SCRIPTS]. */
fun hasOtherRomanizableScript(text: String): Boolean =
    text.any { char ->
        char.isLetter() && UnicodeScripts.scriptOf(char.code) !in OTHER_ROMANIZATION_EXCLUDED_SCRIPTS
    }

/**
 * Whether a provider-supplied romanisation should replace the built-in one. The provider's language tag
 * selects the matching engine toggle; an unknown tag falls back to the generic engine.
 */
fun shouldUseProvidedRomanization(
    originalText: String,
    providerRomanizedText: String?,
    providerRomanizedLanguage: String?,
    preferences: LyricsRomanizationPreferences,
): Boolean {
    if (!preferences.isEnabled || originalText.isBlank()) return false
    val normalized =
        providerRomanizedText
            ?.let { collapseAsciiWhitespace(it).trim() }
            ?.takeIf { it.isNotEmpty() }
            ?: return false
    if (normalized.equals(originalText.trim(), ignoreCase = true)) return false

    val language =
        providerRomanizedLanguage
            ?.substringBefore("-")
            ?.substringBefore("_")
            ?.lowercase()

    return when (language) {
        "ja" -> preferences.romanizeJapanese
        "ko" -> preferences.romanizeKorean
        "zh", "cmn", "yue" -> preferences.romanizeChinese
        "hi", "sa", "mr", "ne" -> preferences.romanizeHindi
        null, "" -> shouldRomanizeLyricsLine(originalText, preferences)
        else -> preferences.romanizeOther || shouldRomanizeLyricsLine(originalText, preferences)
    }
}

/** The provider's line romanisation, whitespace-normalised, when it should replace the built-in one. */
fun providedRomanizedTextForLine(
    line: LyricsLine,
    preferences: LyricsRomanizationPreferences,
): String? =
    line.providerRomanizedText
        ?.let { collapseAsciiWhitespace(it).trim() }
        ?.takeIf {
            shouldUseProvidedRomanization(
                originalText = line.text,
                providerRomanizedText = it,
                providerRomanizedLanguage = line.providerRomanizedLanguage,
                preferences = preferences,
            )
        }

/** The provider's translation, or null when it is blank or only repeats the source line. */
fun providedTranslationTextForLine(line: LyricsLine): String? =
    line.providerTranslationText
        ?.let { collapseAsciiWhitespace(it).trim() }
        ?.takeIf { it.isNotEmpty() && !it.equals(line.text.trim(), ignoreCase = true) }

/**
 * The provider's per-word romanisation, but only when it is usable and gives exactly one reading per
 * timed word. A count mismatch means the readings cannot be attached to the words, so null is returned
 * and the caller uses the line-level reading instead.
 */
fun providedRomanizedWordsForLine(
    line: LyricsLine,
    expectedWordCount: Int,
    preferences: LyricsRomanizationPreferences,
): List<String>? {
    if (expectedWordCount <= 0) return null
    if (providedRomanizedTextForLine(line, preferences) == null) return null

    return line.providerRomanizedWords
        ?.map { word -> collapseAsciiWhitespace(word).trim() }
        ?.filter { it.isNotEmpty() }
        ?.takeIf { it.size == expectedWordCount }
}
