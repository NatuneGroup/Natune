/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.lyrics.language

import moe.rukamori.archivetune.shared.domain.lyrics.SyncedLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Port of the Android `LyricsLanguagePolicyRegressionTest` assertions for source-language validation, hints,
 * exact-metadata eligibility, and dominant-script detection. The romanisation-engine assertions in that file
 * belong to the romanisation phase. The AI-romanisation eligibility assertions are ported in
 * [moe.rukamori.archivetune.shared.domain.lyrics.ai.UnicodeAiTranslationRequestPolicyTest].
 */
class RomanizationLanguagePolicyTest {
    private val policy = LyricsLanguagePolicy

    @Test
    fun validatesBcp47TagsAndPickerLabels() {
        assertEquals("CHINESE_TRADITIONAL", policy.validatedSourceLanguage("zh-Hant"))
        assertEquals("CHINESE_SIMPLIFIED", policy.validatedSourceLanguage("zh-Hans-CN"))
        assertEquals("CHINESE_SIMPLIFIED", policy.validatedSourceLanguage("zh-CN"))
        assertEquals("CHINESE_TRADITIONAL", policy.validatedSourceLanguage("zh_TW"))
        assertEquals("CHINESE", policy.validatedSourceLanguage("zh-Latn"))
        assertEquals("JAPANESE", policy.validatedSourceLanguage("ja-JP"))
        assertEquals("JAPANESE", policy.validatedSourceLanguage("ja_JP"))
        assertEquals("JAPANESE", policy.validatedSourceLanguage("jpn"))
        assertEquals("JAPANESE", policy.validatedSourceLanguage("JAPANESE"))
        assertEquals("SPANISH", policy.validatedSourceLanguage("Spanish"))
        assertEquals("ENGLISH", policy.validatedSourceLanguage("english"))
        assertEquals("ENGLISH", policy.validatedSourceLanguage("en-US-u-ca-buddhist"))
        assertEquals("KOREAN", policy.validatedSourceLanguage("ko-KR"))
        assertEquals("URDU", policy.validatedSourceLanguage("ur"))
        assertEquals("MARATHI", policy.validatedSourceLanguage("mr"))
        assertEquals("HINDI", policy.validatedSourceLanguage("hi"))
        assertEquals("SANSKRIT", policy.validatedSourceLanguage("sa"))
        assertEquals("FILIPINO", policy.validatedSourceLanguage("fil"))
        assertEquals("UIGHUR", policy.validatedSourceLanguage("Uyghur"))
        assertEquals("UIGHUR", policy.validatedSourceLanguage("ug"))
        assertEquals("NORWEGIAN", policy.validatedSourceLanguage("nb"))
        assertEquals("NORWEGIAN", policy.validatedSourceLanguage("no"))
        assertEquals("ODIA_ORIYA", policy.validatedSourceLanguage("ODIA_ORIYA"))
        assertEquals("ODIA_ORIYA", policy.validatedSourceLanguage("or"))
        // Legacy codes that the platform locale rewrote to their current ISO 639 codes.
        assertEquals("HEBREW", policy.validatedSourceLanguage("iw"))
        assertEquals("YIDDISH", policy.validatedSourceLanguage("ji"))
        assertEquals("INDONESIAN", policy.validatedSourceLanguage("in"))
    }

    @Test
    fun rejectsTagsThatAreIllFormedOrNameNothingThePickerStores() {
        listOf(
            "not a tag", "", "x-foo", "und", "123", "en--US", "-en", "en-", "a-b",
            "en-US-a", "abcdefghi", "english-plus-extra-x", "e", "zh-yue",
        ).forEach { hint ->
            assertNull(policy.validatedSourceLanguage(hint), "accepted: [$hint]")
        }
    }

    @Test
    fun sourceHintsReadProviderMetadataAndIgnoreWhatIsNotMetadata() {
        val entry = SyncedLine(1000, "東京", providerRomanizedLanguage = "ja-JP")
        assertEquals(listOf("JAPANESE"), policy.sourceLanguageHints(null, listOf(entry)))
        assertEquals(
            listOf("JAPANESE", "SPANISH"),
            policy.sourceLanguageHints(
                null,
                listOf(
                    SyncedLine(1, "a", providerRomanizedLanguage = "ja"),
                    SyncedLine(2, "b", providerRomanizedLanguage = "es"),
                    SyncedLine(3, "c", providerRomanizedLanguage = "ja-JP"),
                    SyncedLine(4, "d", providerRomanizedLanguage = "not a tag"),
                ),
            ),
        )
        assertEquals(emptyList(), policy.sourceLanguageHints("plain lyrics, not TTML"))
        assertEquals(listOf("JAPANESE"), policy.normalizedSourceLanguageHints(listOf("ja-JP", "jpn", "bogus")))
    }

    @Test
    fun ttmlDocumentLanguageComesFromXmlLangOnTheRootOnly() {
        val ttml =
            "<tt xmlns=\"http://www.w3.org/ns/ttml\" xml:lang=\"es\"><body><div>" +
                "<p begin=\"00:00:01.000\" end=\"00:00:02.000\">Hola</p></div></body></tt>"
        assertEquals(listOf("SPANISH"), policy.sourceLanguageHints(ttml))

        // A prefix bound to the XML namespace on the root counts as xml:lang.
        val prefixed =
            "<tt xmlns=\"http://www.w3.org/ns/ttml\" xmlns:x=\"http://www.w3.org/XML/1998/namespace\" x:lang=\"ko\">" +
                "<body><div><p begin=\"00:00:01.000\" end=\"00:00:02.000\">한</p></div></body></tt>"
        assertEquals(listOf("KOREAN"), policy.sourceLanguageHints(prefixed))

        // An unqualified lang attribute is not xml:lang, and Android's namespace-aware reader ignored it too.
        val unqualified =
            "<tt xmlns=\"http://www.w3.org/ns/ttml\" lang=\"ko\">" +
                "<body><div><p begin=\"00:00:01.000\" end=\"00:00:02.000\">한</p></div></body></tt>"
        assertEquals(emptyList(), policy.sourceLanguageHints(unqualified))
    }

    @Test
    fun exactMetadataDoesNotOverblockSiblingLanguagesButUnknownScriptsDo() {
        val cases =
            listOf(
                Triple("corazón", "es", "SPANISH"), Triple("東京", "ja", "JAPANESE"),
                Triple("中文", "zh", "CHINESE_SIMPLIFIED"), Triple("Україна", "uk", "UKRAINIAN"),
                Triple("Россия", "ru", "RUSSIAN"), Triple("اردو", "ur", "URDU"),
                Triple("فارسی", "fa", "PERSIAN"), Triple("العربية", "ar", "ARABIC"),
                Triple("मराठी", "mr", "MARATHI"), Triple("हिन्दी", "hi", "HINDI"),
            )
        cases.forEach { (text, hint, excluded) ->
            assertFalse(policy.isAutomaticLanguageEligible(text, setOf(excluded), listOf(hint)), "$text / $excluded")
        }
        listOf(
            Triple("東京", "ja", "CHINESE_SIMPLIFIED"), Triple("中文", "zh", "JAPANESE"),
            Triple("Україна", "uk", "RUSSIAN"), Triple("Россия", "ru", "UKRAINIAN"),
            Triple("اردو", "ur", "PERSIAN"), Triple("فارسی", "fa", "ARABIC"),
            Triple("العربية", "ar", "URDU"), Triple("मराठी", "mr", "HINDI"),
            Triple("हिन्दी", "hi", "MARATHI"),
        ).forEach { (text, hint, sibling) ->
            assertTrue(policy.isAutomaticLanguageEligible(text, setOf(sibling), listOf(hint)), "$text / $sibling")
            // Without metadata the script family alone decides, and it does exclude the sibling.
            assertFalse(policy.isAutomaticLanguageEligible(text, setOf(sibling)), "$text / $sibling detection")
        }
        assertFalse(policy.shouldAutoTranslate("corazón", "ENGLISH", setOf("SPANISH"), listOf("es")))
        assertTrue(policy.shouldAutoTranslate("corazón", "ENGLISH", setOf("SPANISH"), listOf("not a tag")))
    }

    @Test
    fun dominantScriptDetectionUsesScriptFamiliesNotLanguageIdentity() {
        assertEquals("HINDI", policy.detectDominantLanguageCode("मैं तुझको लेकर उड़ जाऊँ"))
        assertEquals("JAPANESE", policy.detectDominantLanguageCode("きみのなまえはぼくのなまえ"))
        assertEquals("JAPANESE", policy.detectDominantLanguageCode("東京の空"))
        assertEquals("CHINESE", policy.detectDominantLanguageCode("我的心里只有你"))
        assertEquals("KOREAN", policy.detectDominantLanguageCode("한글 한"))
        assertEquals("RUSSIAN", policy.detectDominantLanguageCode("Россия"))
        assertEquals("ARABIC", policy.detectDominantLanguageCode("العربية"))
        assertNull(policy.detectDominantLanguageCode("Just a regular english line"))
        assertNull(policy.detectDominantLanguageCode(""))
        assertNull(policy.detectDominantLanguageCode("123 !?"))
    }

    @Test
    fun aiRomanizationEligibilityAndSessionIdentityFollowSourceHints() {
        val entry = SyncedLine(1000, "東京", providerRomanizedLanguage = "ja-JP")
        val hints = policy.sourceLanguageHints(null, listOf(entry))
        assertEquals(listOf("JAPANESE"), hints)
        assertEquals("CHINESE_TRADITIONAL", policy.validatedSourceLanguage("zh-Hant"))
        assertNull(policy.validatedSourceLanguage("not a tag"))
    }
}
