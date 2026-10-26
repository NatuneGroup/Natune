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
import moe.rukamori.archivetune.shared.domain.lyrics.SyncedLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RomanizationPhoneticsAlignmentTest {
    private val japanese =
        LyricsRomanizationPreferences(romanizeJapanese = true, romanizeKorean = false, romanizeChinese = false, romanizeHindi = false, romanizeOther = false)
    private val off = LyricsRomanizationPreferences(false, false, false, false, false)
    private val words = listOf(LyricsWord("東", 1.0, 1.5), LyricsWord("京", 1.5, 2.0))
    private val romanizer = LyricsRomanizer()

    private fun karaoke(
        provided: List<String>? = listOf("tou", "kyou"),
        providedLine: String? = "toukyou",
        language: String? = "ja",
        timed: List<LyricsWord> = words,
    ) = KaraokeLine(
        time = 1000L,
        text = "東京",
        providerRomanizedText = providedLine,
        providerRomanizedLanguage = language,
        providerRomanizedWords = provided,
        words = timed,
    )

    @Test fun providerWordReadingsAreUsedWhenTheirCountMatchesTheTimedWords() {
        val phonetics = romanizer.phoneticsForLine(karaoke(), japanese)
        assertEquals("toukyou", phonetics.line)
        assertEquals(listOf("tou", "kyou"), phonetics.words)
        assertEquals(words, karaoke().words)
    }

    @Test fun countMismatchFallsBackToTheLineReadingWithoutWordReadings() {
        val mismatched = karaoke(provided = listOf("toukyou"))
        assertNull(providedRomanizedWordsForLine(mismatched, 2, japanese))
        val phonetics = romanizer.phoneticsForLine(mismatched, japanese)
        assertEquals("toukyou", phonetics.line)
        assertNull(phonetics.words)
    }

    @Test fun missingProviderWordReadingsFallBackToTheLineReading() {
        val phonetics = romanizer.phoneticsForLine(karaoke(provided = null), japanese)
        assertEquals("toukyou", phonetics.line)
        assertNull(phonetics.words)
    }

    @Test fun lineOnlyRowNeverReceivesWordReadingsBecauseItHasNoWordTiming() {
        val synced =
            SyncedLine(
                time = 1000L,
                text = "東京",
                providerRomanizedText = "toukyou",
                providerRomanizedLanguage = "ja",
                providerRomanizedWords = listOf("tou", "kyou"),
            )
        assertEquals(LyricsPhonetics(line = "toukyou", words = null), romanizer.phoneticsForLine(synced, japanese))
    }

    @Test fun backgroundWordsDoNotCountTowardsAlignment() {
        val timed = words + LyricsWord("aside", 2.0, 2.5, isBackground = true)
        val phonetics = romanizer.phoneticsForLine(karaoke(timed = timed), japanese)
        assertEquals(listOf("tou", "kyou"), phonetics.words)
    }

    @Test fun blankProviderWordReadingsAreDroppedAndTrimmedBeforeCounting() {
        val phonetics = romanizer.phoneticsForLine(karaoke(provided = listOf(" tou ", "  ", " kyou")), japanese)
        assertEquals(listOf("tou", "kyou"), phonetics.words)
    }

    @Test fun disabledPreferencesGiveEmptyPhonetics() {
        assertEquals(LyricsPhonetics(), romanizer.phoneticsForLine(karaoke(), off))
    }

    @Test fun providerReadingForAnUnselectedLanguageIsIgnored() {
        val korean = karaoke(language = "ko", providedLine = "hangeul", provided = null)
        assertEquals(LyricsPhonetics(), romanizer.phoneticsForLine(korean, japanese))
    }

    @Test fun providerReadingThatOnlyRepeatsTheSourceIsIgnored() {
        assertNull(providedRomanizedTextForLine(karaoke(providedLine = "東京"), japanese))
    }

    @Test fun providerTranslationThatRepeatsTheSourceIsSuppressed() {
        val repeated = SyncedLine(time = 0L, text = "東京", providerTranslationText = "  東京 ")
        val distinct = SyncedLine(time = 0L, text = "東京", providerTranslationText = "Tokyo")
        assertNull(providedTranslationTextForLine(repeated))
        assertEquals("Tokyo", providedTranslationTextForLine(distinct))
    }

    @Test fun perWordHangulReadingsFollowTheLineScriptWhenNoProviderReadingExists() {
        val line =
            KaraokeLine(
                time = 4500L,
                text = "\uD55C\uAE00",
                words = listOf(LyricsWord("\uD55C", 4.5, 5.0), LyricsWord("\uAE00", 5.0, 5.5)),
            )
        val korean = LyricsRomanizationPreferences(false, romanizeKorean = true, false, false, false)
        val phonetics = romanizer.phoneticsForLine(line, korean)
        assertEquals("hangeul", phonetics.line)
        assertEquals(listOf("han", "geul"), phonetics.words)
    }
}
