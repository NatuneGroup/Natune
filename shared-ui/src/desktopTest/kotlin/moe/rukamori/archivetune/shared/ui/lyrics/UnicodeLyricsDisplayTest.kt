package moe.rukamori.archivetune.shared.ui.lyrics

import moe.rukamori.archivetune.shared.domain.lyrics.unicode.GraphemeClusterSegmenter
import moe.rukamori.archivetune.shared.domain.lyrics.unicode.LyricsScriptPredicates
import moe.rukamori.archivetune.shared.domain.lyrics.unicode.UnicodeBidi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The desktop UI reads lyric lines through the shared Unicode policies: bidi direction picks the line
 * alignment, grapheme clusters keep a family emoji whole, and script checks choose the phonetics.
 */
class UnicodeLyricsDisplayTest {
    @Test
    fun rightToLeftLinesAreDetectedWhileLatinAndKanaStayLeftToRight() {
        assertTrue(UnicodeBidi.isRtlText("مرحبا بالعالم"))
        assertTrue(UnicodeBidi.isRtlText("שלום עולם"))
        assertFalse(UnicodeBidi.isRtlText("hello world"))
        assertFalse(UnicodeBidi.isRtlText("こんにちは"))
    }

    @Test
    fun zwjEmojiSequenceStaysOneDisplayedCluster() {
        val family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67"

        assertEquals(listOf(family, " ", "x"), GraphemeClusterSegmenter.segment("$family x"))
        assertEquals(3, GraphemeClusterSegmenter.graphemeCount("$family x"))
    }

    @Test
    fun scriptChecksSelectTheLineLanguage() {
        assertTrue(LyricsScriptPredicates.isJapanese("こんにちは"))
        assertTrue(LyricsScriptPredicates.isKorean("안녕"))
        assertTrue(LyricsScriptPredicates.isHindi("नमस्ते"))
        assertFalse(LyricsScriptPredicates.isKorean("hello"))
        assertFalse(LyricsScriptPredicates.isHindi("hello"))
    }
}
