package moe.rukamori.archivetune.shared.domain.lyrics.unicode

import moe.rukamori.archivetune.shared.domain.lyrics.LyricsDetection
import moe.rukamori.archivetune.shared.domain.lyrics.LyricsNormalizer
import moe.rukamori.archivetune.shared.domain.lyrics.LyricsParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Semantic joiners (ZWJ, ZWNJ) and variation selectors carry meaning in lyric text. The readers must keep
 * them as content rather than treating them as whitespace or control characters to strip.
 */
class UnicodeSemanticJoinerTest {
    private val zwj = "\u200D"
    private val zwnj = "\u200C"
    private val heartVs16 = "\u2764\uFE0F"
    private val heartVs15 = "\u2764\uFE0E"
    private val singer = "\uD83D\uDC69$zwj\uD83C\uDFA4" // 👩‍🎤
    private val persianWord = "\u0645\u06CC${zwnj}\u0631\u0648\u0645" // می‌روم

    @Test
    fun normalizerKeepsJoinersAndSelectorsWhileTrimmingOnlyWhitespace() {
        assertEquals("$heartVs16$zwj", LyricsNormalizer.normalize("\uFEFF  $heartVs16$zwj \n"))
        assertEquals(zwj, LyricsNormalizer.normalize(zwj))
        assertEquals(zwnj, LyricsNormalizer.normalize(zwnj))
        assertEquals(heartVs15, LyricsNormalizer.normalize(heartVs15))
    }

    @Test
    fun plainTextWithJoinersSurvivesTheNormalizerUnchanged() {
        val text = "$singer $persianWord $heartVs16 ${heartVs15}end"
        assertEquals(text, LyricsNormalizer.normalize(text))
    }

    @Test
    fun lineSyncedLrcKeepsZwjZwnjAndSelectorsInEachLine() {
        val lrc = "[00:01.000]$singer $heartVs16\n[00:02.000]$persianWord$zwj\n[00:03.000]a${heartVs15}b"
        assertTrue(LyricsDetection.isLineSyncedLrc(lrc))
        val lines = LyricsParser.parse(lrc)
        assertEquals(listOf("$singer $heartVs16", "$persianWord$zwj", "a${heartVs15}b"), lines.map { it.text })
        assertEquals(listOf(1_000L, 2_000L, 3_000L), lines.map { it.time })
    }

    @Test
    fun displayTextDoesNotStripJoinersOrSelectors() {
        assertEquals("a${zwj}b$heartVs16", LyricsDetection.displayText("[00:01.000]a${zwj}b$heartVs16"))
        assertEquals("$persianWord\n$singer", LyricsDetection.displayText("[00:01.000]$persianWord\n[00:02.000]$singer"))
    }

    @Test
    fun enhancedLrcWordsKeepJoinersAndSelectorsAsWordContent() {
        val entry = LyricsParser.parse("[00:01.000]<00:01.000>$heartVs16 <00:02.000>$singer").single()
        val words = requireNotNull(entry.words)
        assertEquals(listOf("$heartVs16 ", singer), words.map { it.text })
        assertEquals(listOf(1.0, 2.0), words.map { it.startTime })
    }

    @Test
    fun ttmlSpansKeepJoinersAndSelectors() {
        val raw = """<tt xmlns="http://www.w3.org/ns/ttml"><body><div>""" +
            """<p begin="1s" end="3s"><span begin="1s" end="2s">$persianWord</span> """ +
            """<span begin="2s" end="3s">$singer$heartVs16</span></p></div></body></tt>"""
        val line = LyricsParser.parseTtml(raw).single()
        assertEquals("$persianWord $singer$heartVs16", line.text)
        assertEquals(1_000L, line.time)
    }

    @Test
    fun graphemeSegmentationTreatsJoinedEmojiAsOneCharacter() {
        assertEquals(listOf(singer), GraphemeClusterSegmenter.segment(singer))
        assertEquals(listOf(heartVs16), GraphemeClusterSegmenter.segment(heartVs16))
    }
}
