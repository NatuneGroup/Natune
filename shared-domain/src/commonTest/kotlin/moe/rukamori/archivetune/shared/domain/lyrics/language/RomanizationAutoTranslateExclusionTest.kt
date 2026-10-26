package moe.rukamori.archivetune.shared.domain.lyrics.language

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Port of the Android `AutoTranslateExclusionTest` for "Don't auto translate these languages", plus the
 * alias table and the `shouldAutoTranslate` thresholds. The exclusion set reaches the policy as a
 * parameter, so a caller that drops it silently disables the exclusion. Every live caller passes it.
 */
class RomanizationAutoTranslateExclusionTest {
    private val policy = LyricsLanguagePolicy

    // Devanagari consonants. The matras are combining marks and not letters, and the consonants alone
    // make Devanagari the dominant script.
    private val hindi = "मैं तुझको लेकर उड़ जाऊँ"
    private val japanese = "きみのなまえはぼくのなまえ"
    private val chinese = "我的心里只有你"
    private val english = "Just a regular english line"

    @Test
    fun detectsScriptsWithTheCodesThePickerUses() {
        assertEquals("HINDI", policy.detectDominantLanguageCode(hindi))
        assertEquals("JAPANESE", policy.detectDominantLanguageCode(japanese))
        assertEquals("CHINESE", policy.detectDominantLanguageCode(chinese))
    }

    @Test
    fun latinLyricsHaveNoDominantScript() {
        assertNull(policy.detectDominantLanguageCode(english))
        assertNull(policy.detectDominantLanguageCode(""))
    }

    @Test
    fun excludedHindiIsNotAutoTranslated() {
        assertFalse(policy.shouldAutoTranslate(lyrics = hindi, targetLanguage = "ENGLISH", excludedLanguageCodes = setOf("HINDI")))
    }

    @Test
    fun hindiIsAutoTranslatedWhenNotExcluded() {
        assertTrue(policy.shouldAutoTranslate(lyrics = hindi, targetLanguage = "ENGLISH", excludedLanguageCodes = emptySet()))
    }

    @Test
    fun excludingOneLanguageDoesNotExcludeAnother() {
        assertTrue(policy.shouldAutoTranslate(lyrics = japanese, targetLanguage = "ENGLISH", excludedLanguageCodes = setOf("HINDI")))
    }

    @Test
    fun chineseMatchesEitherPickerVariant() {
        // Detection can only say "CHINESE" while the picker stores CHINESE_SIMPLIFIED or CHINESE_TRADITIONAL,
        // so the alias table has to bridge the two spellings.
        assertTrue(policy.matchesExcludedLanguage("CHINESE", setOf("CHINESE_SIMPLIFIED")))
        assertTrue(policy.matchesExcludedLanguage("CHINESE", setOf("CHINESE_TRADITIONAL")))
        assertFalse(policy.shouldAutoTranslate(lyrics = chinese, targetLanguage = "ENGLISH", excludedLanguageCodes = setOf("CHINESE_TRADITIONAL")))
    }

    @Test
    fun kanaIsDistinctButAmbiguousHanConservativelyRespectsJapaneseExclusion() {
        assertFalse(policy.matchesExcludedLanguage("JAPANESE", setOf("CHINESE_SIMPLIFIED")))
        assertTrue(policy.matchesExcludedLanguage("CHINESE", setOf("JAPANESE")))
    }

    @Test
    fun comparisonIgnoresCaseAndSurroundingSpace() {
        assertTrue(policy.matchesExcludedLanguage("HINDI", setOf(" hindi ")))
        assertTrue(policy.matchesExcludedLanguage("hindi", setOf("HINDI")))
    }

    @Test
    fun emptyExclusionSetMatchesNothing() {
        assertFalse(policy.matchesExcludedLanguage("HINDI", emptySet()))
    }

    @Test
    fun aliasTableCoversEachSiblingFamilyInBothDirections() {
        // Arabic script: Persian, Urdu, Pashto, Sindhi and Uyghur share it with Arabic.
        listOf("PERSIAN", "URDU", "PASHTO", "SINDHI", "UYGHUR").forEach { sibling ->
            assertTrue(policy.matchesExcludedLanguage("ARABIC", setOf(sibling)), sibling)
        }
        // Cyrillic: the Slavic and Central Asian siblings.
        listOf("UKRAINIAN", "BELARUSIAN", "BULGARIAN", "SERBIAN", "MACEDONIAN", "KAZAKH", "KYRGYZ", "MONGOLIAN", "TAJIK")
            .forEach { sibling -> assertTrue(policy.matchesExcludedLanguage("RUSSIAN", setOf(sibling)), sibling) }
        // Devanagari, and Hebrew with Yiddish.
        listOf("MARATHI", "NEPALI", "SANSKRIT").forEach { sibling ->
            assertTrue(policy.matchesExcludedLanguage("HINDI", setOf(sibling)), sibling)
        }
        assertTrue(policy.matchesExcludedLanguage("HEBREW", setOf("YIDDISH")))
        // Aliases do not run backwards: excluding Persian does not exclude Arabic-detected lyrics' siblings
        // by name, but it does exclude Arabic-detected lyrics because the family is ambiguous.
        assertTrue(policy.matchesExcludedLanguage("ARABIC", setOf("PERSIAN")))
        assertFalse(policy.matchesExcludedLanguage("PERSIAN", setOf("ARABIC")))
        assertFalse(policy.matchesExcludedLanguage("JAPANESE", setOf("CHINESE")))
    }

    @Test
    fun shouldAutoTranslateThresholdsForAsciiAndAccentedLatin() {
        assertFalse(policy.shouldAutoTranslate("", "ENGLISH", emptySet()))
        assertFalse(policy.shouldAutoTranslate("   ", "ENGLISH", emptySet()))
        // Plain ASCII Latin never triggers, whatever the target.
        assertFalse(policy.shouldAutoTranslate(english, "ENGLISH", emptySet()))
        // Non-ASCII Latin triggers only for an English target.
        assertTrue(policy.shouldAutoTranslate("corazón", "ENGLISH", emptySet(), emptyList()))
        assertTrue(policy.shouldAutoTranslate("corazón", "en", emptySet(), emptyList()))
        assertFalse(policy.shouldAutoTranslate("corazón", "SPANISH", emptySet(), emptyList()))
        assertFalse(policy.shouldAutoTranslate("corazón", "FRENCH", emptySet(), emptyList()))
        // Unset target defaults to English, so accented Latin still triggers.
        assertTrue(policy.shouldAutoTranslate("corazón", "", emptySet(), emptyList()))
    }

    @Test
    fun exclusionDecisionIsMadeBeforeTheScriptThresholdRuns() {
        // Hindi is excluded, so Latin accents do not rescue the line; the exclusion gate runs first.
        assertFalse(policy.shouldAutoTranslate("मैं corazón", "ENGLISH", setOf("HINDI")))
    }
}
