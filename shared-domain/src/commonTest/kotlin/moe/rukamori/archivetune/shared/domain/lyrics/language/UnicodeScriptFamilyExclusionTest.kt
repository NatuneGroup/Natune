/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.lyrics.language

import moe.rukamori.archivetune.shared.domain.lyrics.unicode.UnicodeScript
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the conservative script families that a translation into a target may contain. A family always
 * includes the neutral scripts, and a sibling language shares the family of its script, so a Persian
 * target allows Arabic script just as an Arabic one does.
 */
class UnicodeScriptFamilyExclusionTest {
    private val base = setOf(UnicodeScript.LATIN, UnicodeScript.COMMON, UnicodeScript.INHERITED)

    @Test
    fun japaneseAllowsHanAndKanaButNoOtherNonLatinScript() {
        val allowed = LyricsLanguagePolicy.allowedScriptsForLanguage("JAPANESE")
        assertEquals(base + setOf(UnicodeScript.HAN, UnicodeScript.HIRAGANA, UnicodeScript.KATAKANA), allowed)
        assertEquals(allowed, LyricsLanguagePolicy.allowedScriptsForLanguage("ja"))
        assertEquals(allowed, LyricsLanguagePolicy.allowedScriptsForLanguage("ja-JP"))
        assertFalse(UnicodeScript.CYRILLIC in allowed)
        assertFalse(UnicodeScript.HANGUL in allowed)
    }

    @Test
    fun koreanAndChineseFamiliesAreSingleScript() {
        assertEquals(base + UnicodeScript.HANGUL, LyricsLanguagePolicy.allowedScriptsForLanguage("KOREAN"))
        assertEquals(base + UnicodeScript.HAN, LyricsLanguagePolicy.allowedScriptsForLanguage("CHINESE_SIMPLIFIED"))
        assertEquals(base + UnicodeScript.HAN, LyricsLanguagePolicy.allowedScriptsForLanguage("CHINESE_TRADITIONAL"))
        assertEquals(base + UnicodeScript.HAN, LyricsLanguagePolicy.allowedScriptsForLanguage("zh-Hant"))
        assertEquals(base + UnicodeScript.HAN, LyricsLanguagePolicy.allowedScriptsForLanguage("Cantonese"))
    }

    @Test
    fun devanagariFamilyCoversItsSiblings() {
        listOf("HINDI", "hi", "SANSKRIT", "MARATHI", "NEPALI", "mr").forEach { language ->
            assertEquals(base + UnicodeScript.DEVANAGARI, LyricsLanguagePolicy.allowedScriptsForLanguage(language), language)
        }
    }

    @Test
    fun arabicFamilyCoversPersianAndUrdu() {
        listOf("ARABIC", "ar", "PERSIAN", "fa", "URDU", "ur").forEach { language ->
            assertEquals(base + UnicodeScript.ARABIC, LyricsLanguagePolicy.allowedScriptsForLanguage(language), language)
        }
    }

    @Test
    fun cyrillicFamilyCoversUkrainianBelarusianAndBulgarian() {
        listOf("RUSSIAN", "ru", "UKRAINIAN", "uk", "BELARUSIAN", "BULGARIAN", "bg").forEach { language ->
            assertEquals(base + UnicodeScript.CYRILLIC, LyricsLanguagePolicy.allowedScriptsForLanguage(language), language)
        }
    }

    @Test
    fun remainingFamiliesMapToTheirOwnScript() {
        assertEquals(base + UnicodeScript.THAI, LyricsLanguagePolicy.allowedScriptsForLanguage("THAI"))
        assertEquals(base + UnicodeScript.HEBREW, LyricsLanguagePolicy.allowedScriptsForLanguage("YIDDISH"))
        assertEquals(base + UnicodeScript.GREEK, LyricsLanguagePolicy.allowedScriptsForLanguage("el"))
        assertEquals(base + UnicodeScript.ARMENIAN, LyricsLanguagePolicy.allowedScriptsForLanguage("hy"))
        assertEquals(base + UnicodeScript.GEORGIAN, LyricsLanguagePolicy.allowedScriptsForLanguage("GEORGIAN"))
    }

    @Test
    fun latinAndUnknownTargetsAllowOnlyNeutralScripts() {
        assertEquals(base, LyricsLanguagePolicy.allowedScriptsForLanguage("ENGLISH"))
        assertEquals(base, LyricsLanguagePolicy.allowedScriptsForLanguage("en"))
        assertEquals(base, LyricsLanguagePolicy.allowedScriptsForLanguage("SPANISH"))
        assertEquals(base, LyricsLanguagePolicy.allowedScriptsForLanguage("KLINGON"))
        assertEquals(base, LyricsLanguagePolicy.allowedScriptsForLanguage("chinese simplified"))
    }

    @Test
    fun separatorsAndCaseDoNotChangeTheFamily() {
        assertEquals(LyricsLanguagePolicy.allowedScriptsForLanguage("CHINESE_TRADITIONAL"), LyricsLanguagePolicy.allowedScriptsForLanguage("chinese-traditional"))
        assertEquals(LyricsLanguagePolicy.allowedScriptsForLanguage("ja"), LyricsLanguagePolicy.allowedScriptsForLanguage(" JA "))
    }

    @Test
    fun englishTargetIsRecognisedWhenUnsetOrSpelledAnyWay() {
        listOf("", "ENGLISH", "English", "en", "en-US", "en_GB", " en ").forEach { target ->
            assertTrue(LyricsLanguagePolicy.isEnglishTarget(target), target)
        }
        listOf("SPANISH", "fr", "japanese").forEach { target ->
            assertFalse(LyricsLanguagePolicy.isEnglishTarget(target), target)
        }
    }

    @Test
    fun scriptFamilyDecidesWhetherAForeignScriptIsWorthTranslating() {
        // Hangul is foreign to a Japanese target, so it is translated.
        assertTrue(LyricsLanguagePolicy.shouldAutoTranslate("한국어 가사", "JAPANESE", emptySet(), emptyList()))
        // Kana and Han are in the Japanese family, so they are not.
        assertFalse(LyricsLanguagePolicy.shouldAutoTranslate("東京の空", "JAPANESE", emptySet(), emptyList()))
        // Han belongs to the Chinese family too, so a Chinese target does not translate Han-only lyrics.
        assertFalse(LyricsLanguagePolicy.shouldAutoTranslate("我的心里只有你", "CHINESE_SIMPLIFIED", emptySet(), emptyList()))
        // Kana is foreign to a Chinese target.
        assertTrue(LyricsLanguagePolicy.shouldAutoTranslate("きみのなまえ", "CHINESE_SIMPLIFIED", emptySet(), emptyList()))
        // Accented Latin is not foreign to a Japanese target, which keeps Latin in the family.
        assertFalse(LyricsLanguagePolicy.shouldAutoTranslate("corazón", "JAPANESE", emptySet(), emptyList()))
    }

    @Test
    fun supplementaryLettersCountAsLettersAndMarksDoNotCountAtAll() {
        // U+1EE00 is an Arabic-script letter above the BMP. It is only seen when the letter table is consulted.
        assertEquals("ARABIC", LyricsLanguagePolicy.detectDominantLanguageCode("\uD83B\uDE00\uD83B\uDE01"))
        // Devanagari vowel signs and candrabindu are marks, not letters, so they never set a language alone.
        assertEquals(null, LyricsLanguagePolicy.detectDominantLanguageCode("\u093F\u0901"))
    }
}
