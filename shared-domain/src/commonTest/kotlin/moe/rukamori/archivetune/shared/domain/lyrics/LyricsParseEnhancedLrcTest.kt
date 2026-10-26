package moe.rukamori.archivetune.shared.domain.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Enhanced LRC word timing (`[00:12.000]<00:12.000>Hello <00:12.500>world`). Enhanced LRC marks each
 * word START only; the separator space must survive, doubled spaces collapse, and the last word must
 * not run past the next line.
 */
class LyricsParseEnhancedLrcTest {
    @Test
    fun keepsTheSeparatorSpaceOnTheWordThatCarriesIt() {
        val entries = LyricsParser.parse("[00:12.000]<00:12.000>Hello <00:12.500>world")

        assertEquals(1, entries.size)
        val line = assertIs<KaraokeLine>(entries.single())
        assertEquals(listOf("Hello ", "world"), line.words.map { it.text })
        assertEquals("Hello world", line.words.joinToString("") { it.text })
    }

    @Test
    fun collapsesADoubledSeparatorToASingleSpace() {
        val words = LyricsParser.parse("[00:12.000]<00:12.000>Hello  <00:12.500>world").single().words

        assertEquals("Hello world", words?.joinToString("") { it.text })
    }

    @Test
    fun keepsCompactEnhancedLrcCompact() {
        // No separator in the source means no separator in the output: a CJK line must not gain
        // invented spaces between its words.
        val words = LyricsParser.parse("[00:12.000]<00:12.000>Hello<00:12.500>world").single().words

        assertEquals("Helloworld", words?.joinToString("") { it.text })
    }

    @Test
    fun stampsAreSecondsAndMonotonic() {
        val words = LyricsParser.parse("[00:12.000]<00:12.000>Hello <00:12.500>world").single().words!!

        assertEquals(12.0, words[0].startTime, 0.001)
        assertEquals(12.5, words[1].startTime, 0.001)
        assertTrue(words[1].endTime > words[1].startTime)
    }

    @Test
    fun clampsTheLastWordToTheNextLine() {
        val entries =
            LyricsParser.parse(
                "[00:12.000]<00:12.000>Hello <00:12.500>world\n[00:13.000]<00:13.000>again",
            )

        val lastWordOfFirstLine = entries.first().words!!.last()
        assertEquals("world", lastWordOfFirstLine.text)
        assertEquals(13.0, lastWordOfFirstLine.endTime, 0.001)
    }

    @Test
    fun lineTextDropsTheStampsButKeepsTheSpaces() {
        assertEquals(
            "Hello world",
            LyricsParser.parse("[00:12.000]<00:12.000>Hello <00:12.500>world").single().text,
        )
    }

    @Test
    fun plainLineSyncedLrcHasNoWords() {
        val entries = LyricsParser.parse("[00:12.000]Hello world")

        assertEquals("Hello world", entries.single().text)
        assertNull(entries.single().words)
        assertIs<SyncedLine>(entries.single())
    }

    @Test
    fun aSingleInlineStampIsNotWordSync() {
        // One stamp says nothing about which word is sung, so the row stays line-only.
        val line = LyricsParser.parse("[00:12.000]<00:12.000>Hello world").single()

        assertIs<SyncedLine>(line)
        assertEquals("Hello world", line.text)
        assertNull(line.words)
    }

    @Test
    fun wordSyncedDetectionNeedsALineStampAndAnInlineStamp() {
        assertTrue(LyricsDetection.hasWordSyncedLyrics("[00:12.000]<00:12.000>Hello <00:12.500>world"))
        assertTrue(!LyricsDetection.hasWordSyncedLyrics("[00:12.000]Hello world"))
    }
}
