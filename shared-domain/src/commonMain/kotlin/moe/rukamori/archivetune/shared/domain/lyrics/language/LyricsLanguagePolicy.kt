package moe.rukamori.archivetune.shared.domain.lyrics.language

import moe.rukamori.archivetune.shared.domain.lyrics.LyricsDetection
import moe.rukamori.archivetune.shared.domain.lyrics.LyricsLine
import moe.rukamori.archivetune.shared.domain.lyrics.LyricsNormalizer
import moe.rukamori.archivetune.shared.domain.lyrics.LyricsParser
import moe.rukamori.archivetune.shared.domain.lyrics.XmlElement
import moe.rukamori.archivetune.shared.domain.lyrics.XmlTreeParser
import moe.rukamori.archivetune.shared.domain.lyrics.unicode.UnicodeScript
import moe.rukamori.archivetune.shared.domain.lyrics.unicode.UnicodeScripts
import moe.rukamori.archivetune.shared.domain.lyrics.unicode.forEachCodePoint

/**
 * Source-language decisions for automatic AI translation and romanisation, ported from the Android
 * `LyricsUtils` helpers. Every threshold and ordering here matches that code. Where the Android code
 * relied on the platform (`Locale`, `android.util.Xml`, `Character`), the replacement is noted at the site.
 *
 * These are conservative by design: exact provider metadata wins over detection, and detection only
 * supplies a legacy label for the dominant non-Latin script family, never an exact language identity.
 */
object LyricsLanguagePolicy {
    private const val XML_NAMESPACE = "http://www.w3.org/XML/1998/namespace"
    private const val CHINESE_SIMPLIFIED = "CHINESE_SIMPLIFIED"
    private const val CHINESE_TRADITIONAL = "CHINESE_TRADITIONAL"

    private val CHINESE_SIMPLIFIED_LABELS = setOf("CHINESE-SIMPLIFIED", "ZH-HANS", "ZH-CN", "ZH-SG")
    private val CHINESE_TRADITIONAL_LABELS = setOf("CHINESE-TRADITIONAL", "ZH-HANT", "ZH-TW", "ZH-HK", "ZH-MO")

    /** Legacy primary-language subtags that the platform locale rewrote to their current ISO 639 codes. */
    private val LEGACY_LANGUAGE_CODES = mapOf("iw" to "he", "ji" to "yi", "in" to "id")

    /**
     * Picker codes that should satisfy an exclusion for a detected script with no exact code of its own.
     * Without source metadata the sibling languages cannot be told apart, so the exclusion covers them too.
     */
    private val EXCLUSION_ALIASES: Map<String, List<String>> =
        mapOf(
            // Han without kana is ambiguous, including kanji-only Japanese.
            "CHINESE" to listOf("CHINESE_SIMPLIFIED", "CHINESE_TRADITIONAL", "JAPANESE"),
            "ARABIC" to listOf("PERSIAN", "URDU", "PASHTO", "SINDHI", "UYGHUR"),
            "RUSSIAN" to
                listOf(
                    "UKRAINIAN",
                    "BELARUSIAN",
                    "BULGARIAN",
                    "SERBIAN",
                    "MACEDONIAN",
                    "KAZAKH",
                    "KYRGYZ",
                    "MONGOLIAN",
                    "TAJIK",
                ),
            "HINDI" to listOf("MARATHI", "NEPALI", "SANSKRIT"),
            "HEBREW" to listOf("YIDDISH"),
        )

    /** Language names and aliases that map onto one script family for [allowedScriptsForLanguage]. */
    private val LANGUAGE_FAMILY_CODES: Map<String, String> =
        mapOf(
            "english" to "en", "en" to "en",
            "japanese" to "ja", "ja" to "ja",
            "korean" to "ko", "ko" to "ko",
            "chinese" to "zh", "zh" to "zh", "mandarin" to "zh", "cmn" to "zh", "cantonese" to "zh", "yue" to "zh",
            "hindi" to "hi", "hi" to "hi", "sanskrit" to "hi", "sa" to "hi", "marathi" to "hi", "mr" to "hi",
            "nepali" to "hi", "ne" to "hi",
            "arabic" to "ar", "ar" to "ar", "persian" to "ar", "fa" to "ar", "urdu" to "ar", "ur" to "ar",
            "russian" to "ru", "ru" to "ru", "ukrainian" to "ru", "uk" to "ru", "belarusian" to "ru", "be" to "ru",
            "bulgarian" to "ru", "bg" to "ru",
            "thai" to "th", "th" to "th",
            "hebrew" to "he", "he" to "he", "yiddish" to "he", "yi" to "he",
            "greek" to "el", "el" to "el",
            "armenian" to "hy", "hy" to "hy",
            "georgian" to "ka", "ka" to "ka",
        )

    private val FAMILY_SCRIPTS: Map<String, Set<UnicodeScript>> =
        mapOf(
            "ja" to setOf(UnicodeScript.HAN, UnicodeScript.HIRAGANA, UnicodeScript.KATAKANA),
            "ko" to setOf(UnicodeScript.HANGUL),
            "zh" to setOf(UnicodeScript.HAN),
            "hi" to setOf(UnicodeScript.DEVANAGARI),
            "ar" to setOf(UnicodeScript.ARABIC),
            "ru" to setOf(UnicodeScript.CYRILLIC),
            "th" to setOf(UnicodeScript.THAI),
            "he" to setOf(UnicodeScript.HEBREW),
            "el" to setOf(UnicodeScript.GREEK),
            "hy" to setOf(UnicodeScript.ARMENIAN),
            "ka" to setOf(UnicodeScript.GEORGIAN),
        )

    private val FAMILY_BASE_SCRIPTS: Set<UnicodeScript> =
        setOf(UnicodeScript.LATIN, UnicodeScript.COMMON, UnicodeScript.INHERITED)

    /**
     * Validates a language hint (a BCP 47 tag, or a picker label) and returns the picker name it means, or
     * null when it names nothing the picker can store.
     */
    fun validatedSourceLanguage(hint: String): String? {
        val normalized = hint.trim().replace('_', '-')
        val label = normalized.uppercase()
        if (label == "UYGHUR") return "UIGHUR"
        if (label in CHINESE_SIMPLIFIED_LABELS) return CHINESE_SIMPLIFIED
        if (label in CHINESE_TRADITIONAL_LABELS) return CHINESE_TRADITIONAL
        LyricsLanguageNames.canonicalNameForLabel(label)?.let { return it }
        val tag = LanguageTagSyntax.parse(normalized) ?: return null
        if (tag.language == "zh") {
            return when (tag.script?.lowercase()) {
                "hans" -> CHINESE_SIMPLIFIED
                "hant" -> CHINESE_TRADITIONAL
                else -> "CHINESE"
            }
        }
        val language = LEGACY_LANGUAGE_CODES[tag.language] ?: tag.language
        return LyricsLanguageNames.nameForCode(language)
    }

    /**
     * Source-language hints for a whole lyrics document: the TTML root `xml:lang`, plus each row's provider
     * romanisation language. [entries] is used as-is when supplied, so a caller that already parsed the
     * lyrics is not parsed twice. The result is sorted and de-duplicated.
     */
    fun sourceLanguageHints(
        lyrics: String?,
        entries: List<LyricsLine>? = null,
    ): List<String> {
        val text = lyrics?.let(LyricsNormalizer::normalize).orEmpty()
        val ttml = LyricsDetection.isTtml(text)
        val hints = HashSet<String>()
        if (ttml) documentLanguage(text)?.let { hints.add(it) }
        val parsed =
            entries
                ?: if (ttml) runCatching { LyricsParser.parseTtml(text) }.getOrDefault(emptyList()) else emptyList()
        parsed.forEach { line ->
            line.providerRomanizedLanguage?.let { validatedSourceLanguage(it) }?.let { hints.add(it) }
        }
        return hints.sorted()
    }

    /** Every hint that validates, as a sorted distinct list. Used for identity and request coalescing. */
    fun normalizedSourceLanguageHints(hints: List<String>): List<String> =
        hints.mapNotNull { validatedSourceLanguage(it) }.distinct().sorted()

    /**
     * Applies exclusions to a dominant-script label, with the sibling aliases from [EXCLUSION_ALIASES].
     * Comparison ignores case and surrounding space.
     */
    fun matchesExcludedLanguage(
        dominantCode: String,
        excludedLanguageCodes: Set<String>,
    ): Boolean {
        if (excludedLanguageCodes.isEmpty()) return false
        val dominant = dominantCode.trim().uppercase()
        if (dominant.isEmpty()) return false
        val normalized = excludedLanguageCodes.mapTo(HashSet()) { it.trim().uppercase() }
        if (dominant in normalized) return true
        return EXCLUSION_ALIASES[dominant]?.any { it in normalized } == true
    }

    /**
     * Whether the lyrics may be sent for automatic translation or romanisation. Exact hints decide alone
     * when present; otherwise the detected script family decides. A hint that names nothing is ignored.
     */
    fun isAutomaticLanguageEligible(
        text: String,
        excludedLanguageCodes: Set<String>,
        sourceLanguageHints: List<String> = emptyList(),
    ): Boolean {
        val exact = sourceLanguageHints.mapNotNull { validatedSourceLanguage(it) }.distinct()
        if (exact.isNotEmpty()) {
            val excluded = excludedLanguageCodes.mapNotNull { validatedSourceLanguage(it) }.toSet()
            return exact.none { code ->
                code in excluded ||
                    (code.startsWith("CHINESE") && "CHINESE" in excluded) ||
                    (code == "CHINESE" && excluded.any { it.startsWith("CHINESE") })
            }
        }
        val dominant = detectDominantLanguageCode(text)
        return dominant == null || !matchesExcludedLanguage(dominant, excludedLanguageCodes)
    }

    /**
     * True when [lyrics] should be sent for automatic AI translation into [targetLanguage]: some letter lies
     * outside the target's script family, or, for an English target, a non-ASCII Latin letter appears
     * (so Spanish or French lyrics still translate to English).
     */
    fun shouldAutoTranslate(
        lyrics: String,
        targetLanguage: String,
        excludedLanguageCodes: Set<String>,
        sourceLanguageHints: List<String> = LyricsLanguagePolicy.sourceLanguageHints(lyrics),
    ): Boolean {
        if (lyrics.isBlank()) return false
        if (!isAutomaticLanguageEligible(lyrics, excludedLanguageCodes, sourceLanguageHints)) return false
        val allowedScripts = allowedScriptsForLanguage(targetLanguage)
        // The UTF-16 char loop mirrors the Android code: surrogates are never letters here.
        val targetIsEnglish = isEnglishTarget(targetLanguage)
        return lyrics.any { char ->
            if (!char.isLetter()) return@any false
            val script = UnicodeScripts.scriptOf(char.code)
            script !in allowedScripts ||
                (targetIsEnglish && script == UnicodeScript.LATIN && char.code > 127)
        }
    }

    /**
     * Returns the legacy uppercase label for the dominant non-Latin script family, not an exact language
     * detector. Kana supplies Japanese context for Han; Han alone stays ambiguous. Latin-only or
     * unrecognised scripts return null rather than an invented identity.
     *
     * The Android code took the maximum over a HashMap, so a tie resolved in an unspecified order. Here a
     * tie goes to the script that appears first in the text, which is deterministic.
     */
    fun detectDominantLanguageCode(lyrics: String): String? {
        if (lyrics.isBlank()) return null
        val counts = LinkedHashMap<UnicodeScript, Int>()
        lyrics.forEachCodePoint { codePoint ->
            if (!isLetterCodePoint(codePoint)) return@forEachCodePoint
            val script = UnicodeScripts.scriptOf(codePoint)
            if (script == UnicodeScript.LATIN || script == UnicodeScript.COMMON || script == UnicodeScript.INHERITED) {
                return@forEachCodePoint
            }
            counts[script] = (counts[script] ?: 0) + 1
        }
        val dominant = counts.maxByOrNull { it.value }?.key ?: return null
        return when (dominant) {
            UnicodeScript.HAN ->
                if ((counts[UnicodeScript.HIRAGANA] ?: 0) + (counts[UnicodeScript.KATAKANA] ?: 0) > 0) {
                    "JAPANESE"
                } else {
                    "CHINESE"
                }
            UnicodeScript.HIRAGANA, UnicodeScript.KATAKANA -> "JAPANESE"
            UnicodeScript.HANGUL -> "KOREAN"
            UnicodeScript.DEVANAGARI -> "HINDI"
            UnicodeScript.ARABIC -> "ARABIC"
            UnicodeScript.CYRILLIC -> "RUSSIAN"
            UnicodeScript.THAI -> "THAI"
            UnicodeScript.HEBREW -> "HEBREW"
            UnicodeScript.GREEK -> "GREEK"
            UnicodeScript.ARMENIAN -> "ARMENIAN"
            UnicodeScript.GEORGIAN -> "GEORGIAN"
            else -> null
        }
    }

    /**
     * Scripts a translation into [language] may contain without being worth translating. The families are
     * deliberately conservative: Persian and Urdu share Arabic script, so both are allowed for either.
     */
    fun allowedScriptsForLanguage(language: String): Set<UnicodeScript> {
        val normalized = language.trim().lowercase().replace('_', '-').substringBefore('-')
        val family = LANGUAGE_FAMILY_CODES[normalized] ?: normalized
        val extra = FAMILY_SCRIPTS[family] ?: return FAMILY_BASE_SCRIPTS
        return FAMILY_BASE_SCRIPTS + extra
    }

    internal fun isEnglishTarget(language: String): Boolean {
        val normalized = language.trim().lowercase().replace('_', '-').substringBefore('-')
        return normalized.isEmpty() || normalized == "english" || normalized == "en"
    }

    /** Letter test for a whole code point; see [CodePointLetters] for why the BMP and the rest differ. */
    internal fun isLetterCodePoint(codePoint: Int): Boolean =
        if (codePoint < 0x10000) Char(codePoint).isLetter() else CodePointLetters.contains(codePoint)

    /**
     * Reads the `lang` attribute of the TTML root. The attribute counts when it is `xml:lang`, or when its
     * prefix is declared on the root as the XML namespace. Namespace-aware parsing on Android accepted both.
     */
    private fun documentLanguage(text: String): String? {
        val root = runCatching { XmlTreeParser.parse(text).documentElement }.getOrNull() ?: return null
        if (root.tagName.substringAfter(':') != "tt") return null
        val lang = xmlLangAttribute(root) ?: return null
        return validatedSourceLanguage(lang)
    }

    private fun xmlLangAttribute(element: XmlElement): String? {
        val prefixes = mutableListOf("xml")
        element.attributes.forEach { attribute ->
            if (attribute.name.startsWith("xmlns:") && attribute.value == XML_NAMESPACE) {
                prefixes += attribute.name.removePrefix("xmlns:")
            }
        }
        for (prefix in prefixes) {
            val name = "$prefix:lang"
            element.attributes.firstOrNull { it.name == name }?.let { return it.value }
        }
        return null
    }
}
