/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.lyrics.romanization

import moe.rukamori.archivetune.shared.domain.lyrics.SyncedLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RomanizationKanaTest {
    private val japanese = LyricsRomanizationPreferences(romanizeJapanese = true, romanizeKorean = false, romanizeChinese = false, romanizeHindi = false, romanizeOther = false)

    @Test fun digraphsDakutenAndSpecialKanaUseTheMappedRomaji() {
        assertEquals("kya", katakanaToRomaji("キャ"))
        assertEquals("ja", katakanaToRomaji("ジャ"))
        assertEquals("ja", katakanaToRomaji("ヂャ"))
        assertEquals("shi", katakanaToRomaji("シ"))
        assertEquals("tsu", katakanaToRomaji("ツ"))
        assertEquals("fu", katakanaToRomaji("フ"))
        assertEquals("o", katakanaToRomaji("ヲ"))
        assertEquals("n", katakanaToRomaji("ン"))
        assertEquals("po", katakanaToRomaji("ポ"))
    }

    @Test fun androidTokenReadingsRomaniseAsAndroidAsserts() {
        assertEquals("nihongo", katakanaToRomaji("ニホンゴ"))
        assertEquals("toukyou", katakanaToRomaji("トウキョウ"))
    }

    @Test fun hiraganaIsConvertedToKatakanaBeforeRomanisation() {
        assertEquals("nihongo", katakanaToRomaji(hiraganaToKatakana("にほんご")))
        assertEquals("toukyou", katakanaToRomaji(hiraganaToKatakana("とうきょう")))
    }

    @Test fun sokuonGeminatesTheFollowingConsonantFromTheSameString() {
        assertEquals("kka", katakanaToRomaji("ッカ"))
        assertEquals("nippon", katakanaToRomaji("ニッポン"))
        assertEquals("aa", katakanaToRomaji("ッア"))
    }

    @Test fun sokuonAtTheEndTakesTheNextTokensFirstKana() {
        assertEquals("k", katakanaToRomaji("ッ", "カ"))
        assertEquals("", katakanaToRomaji("ッ"))
    }

    @Test fun chouonpuExtendsThePreviousVowelAndDropsAtStart() {
        assertEquals("kaa", katakanaToRomaji("カー"))
        assertEquals("keeki", katakanaToRomaji("ケーキ"))
        assertEquals("tookyoo", katakanaToRomaji("トーキョー"))
        assertEquals("", katakanaToRomaji("ー"))
    }

    @Test fun unmappedCharactersPassThroughLowercased() {
        assertEquals("abc", katakanaToRomaji("ABC"))
        assertEquals("", katakanaToRomaji(null))
        assertEquals("", katakanaToRomaji(""))
    }

    @Test fun withoutATokenizerJapaneseLineIsLeftUnchangedAndAddsNoPhonetics() {
        val romanizer = LyricsRomanizer()
        assertEquals("トウキョウ", romanizer.romanizeJapanese("トウキョウ"))
        assertNull(romanizer.romanizeLyricsLine("トウキョウ", japanese, "ja"))
        assertEquals(listOf<String?>(null, null), romanizer.romanizeWordsForLine(listOf("トウ", "キョウ"), "トウキョウ", japanese))
        assertEquals(LyricsPhonetics(), romanizer.phoneticsForLine(SyncedLine(0L, "トウキョウ"), japanese))
    }

    @Test fun kanaLineStaysUnromanisedWhenNoTokenizerIsInjected() {
        assertNull(LyricsRomanizer(japaneseTokenizer = null).romanizeLyricsLine("日本語", japanese, "ja"))
    }
}
