package moe.rukamori.archivetune.shared.ui.lyrics

import moe.rukamori.archivetune.shared.domain.lyrics.KaraokeLine
import moe.rukamori.archivetune.shared.domain.lyrics.LyricsWord
import moe.rukamori.archivetune.shared.domain.lyrics.romanization.IcuLyricsTransliterator
import moe.rukamori.archivetune.shared.domain.lyrics.romanization.KuromojiJapaneseTokenizer
import moe.rukamori.archivetune.shared.domain.lyrics.romanization.LyricsRomanizationPreferences
import moe.rukamori.archivetune.shared.domain.lyrics.romanization.LyricsRomanizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The desktop host supplies the JVM engines: Kuromoji for Japanese and ICU for other scripts. These tests
 * check that the desktop UI classpath loads both engines and that the shared romanizer applies them to a line.
 */
class RomanizationLyricsDisplayTest {
    @Test
    fun japaneseLineGetsAlignedWordReadingsFromKuromoji() {
        val romanizer = LyricsRomanizer(
            japaneseTokenizer = KuromojiJapaneseTokenizer.create(),
            transliterator = IcuLyricsTransliterator(),
        )
        val japanese = preferences(romanizeJapanese = true)
        val line = KaraokeLine(
            time = 0L,
            text = "東京",
            providerRomanizedLanguage = "ja",
            words = listOf(LyricsWord("東京", 0.0, 1.0)),
        )

        assertEquals("toukyou", romanizer.romanizeLyricsLine("東京", japanese, sourceLanguage = "ja"))
        assertEquals(listOf("toukyou"), romanizer.phoneticsForLine(line, japanese).words)
    }

    @Test
    fun cyrillicLineGetsIcuTransliterationAndLatinStaysUntouched() {
        val romanizer = LyricsRomanizer(transliterator = IcuLyricsTransliterator())
        val other = preferences(romanizeOther = true)

        assertEquals("Rossia", romanizer.romanizeLyricsLine("Россия", other))
        assertNull(romanizer.romanizeLyricsLine("Hello", other))
    }

    @Test
    fun koreanLineUsesTheBuiltInHangulTable() {
        assertEquals("annyeong", LyricsRomanizer().romanizeLyricsLine("안녕", preferences(romanizeKorean = true)))
    }

    private fun preferences(
        romanizeJapanese: Boolean = false,
        romanizeKorean: Boolean = false,
        romanizeOther: Boolean = false,
    ) = LyricsRomanizationPreferences(
        romanizeJapanese = romanizeJapanese,
        romanizeKorean = romanizeKorean,
        romanizeChinese = false,
        romanizeHindi = false,
        romanizeOther = romanizeOther,
    )
}
