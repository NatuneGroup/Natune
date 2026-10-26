/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.lyrics.romanization

import moe.rukamori.archivetune.shared.domain.lyrics.KaraokeLine
import moe.rukamori.archivetune.shared.domain.lyrics.LyricsWord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A fixed analysis table, so the alignment rules are tested without a dictionary. */
private class RomanizationFixedTokenizer(
    private val analyses: Map<String, List<JapaneseToken>>,
) : JapaneseTokenizer {
    override fun tokenize(text: String): List<JapaneseToken> = analyses[text].orEmpty()
}

class RomanizationJapaneseAlignmentTest {
    private val japanese =
        LyricsRomanizationPreferences(romanizeJapanese = true, romanizeKorean = false, romanizeChinese = false, romanizeHindi = false, romanizeOther = false)
    private val tokyo = JapaneseToken("東京", "トウキョウ", 0)
    private val tokenizer =
        RomanizationFixedTokenizer(
            mapOf(
                "東京" to listOf(tokyo),
                "日本語" to listOf(JapaneseToken("日本", "ニホン", 0), JapaneseToken("語", "ゴ", 2)),
                "切手" to listOf(JapaneseToken("切", "キッ", 0), JapaneseToken("手", "テ", 1)),
                "🦄" to listOf(JapaneseToken("🦄", null, 0)),
            ),
        )

    @Test fun wholeLineRomajiUsesTokenReadingsAndSokuonAcrossTokenBoundaries() {
        assertEquals("toukyou", romanizeJapaneseWithTokenizer("東京", tokenizer))
        assertEquals("nihon go", romanizeJapaneseWithTokenizer("日本語", tokenizer))
        assertEquals("kit te", romanizeJapaneseWithTokenizer("切手", tokenizer))
    }

    @Test fun wordThatSpansOneTokenGetsItsRomaji() {
        assertEquals(listOf("toukyou"), romanizeJapaneseWordsForLine(listOf("東京"), "東京", tokenizer))
    }

    @Test fun wordsSplitInsideOneTokenGetNoReadingAndNoFakeAlignment() {
        assertEquals(listOf<String?>(null, null), romanizeJapaneseWordsForLine(listOf("東", "京"), "東京", tokenizer))
    }

    @Test fun tokenStraddlingAWordBoundaryNullFillsTheWholeLine() {
        assertEquals(listOf<String?>(null, null), romanizeJapaneseWordsForLine(listOf("日", "本語"), "日本語", tokenizer))
    }

    @Test fun wordsWholeTokensInsideOneLineAreJoinedInOrder() {
        assertEquals(listOf("nihongo"), romanizeJapaneseWordsForLine(listOf("日本語"), "日本語", tokenizer))
    }

    @Test fun wordAbsentFromTheLineNullFillsEverything() {
        assertEquals(listOf<String?>(null), romanizeJapaneseWordsForLine(listOf("語学"), "日本語", tokenizer))
    }

    @Test fun symbolWithoutReadingPassesThroughUnchanged() {
        assertEquals("🦄", romanizeJapaneseWithTokenizer("🦄", tokenizer))
        assertEquals(listOf("🦄"), romanizeJapaneseWordsForLine(listOf("🦄"), "🦄", tokenizer))
    }

    @Test fun unknownLineWithNoTokensNullFillsAndNeverInventsReadings() {
        assertEquals(listOf<String?>(null), romanizeJapaneseWordsForLine(listOf("雨"), "雨", tokenizer))
        assertNull(LyricsRomanizer(tokenizer).romanizeLyricsLine("雨", japanese, "ja"))
        assertEquals("", romanizeJapaneseWithTokenizer("", tokenizer))
    }

    @Test fun lineRomanizerNeedsAJapaneseSignalAndAnInjectedTokenizer() {
        val romanizer = LyricsRomanizer(tokenizer)
        assertEquals("toukyou", romanizer.romanizeLyricsLine("東京", japanese, sourceLanguage = "ja"))
        // Han alone is ambiguous, so without a language hint or kana the line is not treated as Japanese.
        assertNull(romanizer.romanizeLyricsLine("東京", japanese))
    }

    @Test fun phoneticsForAStraddlingWordKeepOnlyTheLineReading() {
        val line = KaraokeLine(
            time = 0L,
            text = "東京",
            providerRomanizedLanguage = "ja",
            words = listOf(LyricsWord("東", 0.0, 0.5), LyricsWord("京", 0.5, 1.0)),
        )
        val phonetics = LyricsRomanizer(tokenizer).phoneticsForLine(line, japanese)
        assertEquals("toukyou", phonetics.line)
        assertNull(phonetics.words)
    }
}
