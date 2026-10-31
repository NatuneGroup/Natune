package moe.rukamori.archivetune.shared.ui.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LyricsLineShareTest {
    private fun line(
        text: String,
        background: String? = null,
        phonetic: String? = null,
        translation: String? = null,
        instrumental: Boolean = false,
    ) = NativeLyricsLine(
        index = 0,
        timeMs = 0,
        text = text,
        spans = emptyList(),
        background = background,
        backgroundSpans = emptyList(),
        phonetic = phonetic,
        translation = translation,
        secondVoice = false,
        rightToLeft = false,
        instrumental = instrumental,
        seekable = true,
    )

    @Test
    fun theSungLineIsShared() {
        assertEquals("Hello world", line("  Hello world ").shareText())
    }

    @Test
    fun backgroundAndTranslationFollowOnTheirOwnLinesWithoutThePhoneticReading() {
        assertEquals(
            "\u541b\u306e\u58f0\n(ooh)\nYour voice",
            line("\u541b\u306e\u58f0", background = "(ooh)", phonetic = "kimi no koe", translation = "Your voice").shareText(),
        )
    }

    @Test
    fun aTranslationEqualToTheLineIsNotRepeated() {
        assertEquals("Same", line("Same", translation = "Same").shareText())
    }

    @Test
    fun instrumentalAndEmptyRowsOfferNoShare() {
        assertNull(line("\u266a", instrumental = true).shareText())
        assertNull(line("   ").shareText())
    }
}
