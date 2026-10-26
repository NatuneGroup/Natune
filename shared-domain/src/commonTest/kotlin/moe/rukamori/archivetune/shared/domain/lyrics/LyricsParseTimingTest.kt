/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LyricsParseTimingTest {
    private fun line(time: Long, text: String = "line $time", durationMs: Long = 0L): LyricsLine =
        SyncedLine(time = time, text = text, durationMs = durationMs)

    @Test
    fun emptyListHasNoCurrentLine() {
        assertEquals(-1, LyricsTiming.findCurrentLineIndex(emptyList(), positionMs = 0L))
    }

    @Test
    fun positionBeforeTheFirstLineSelectsTheFirstLine() {
        assertEquals(0, LyricsTiming.findCurrentLineIndex(listOf(line(1_000L)), positionMs = 0L))
    }

    @Test
    fun backToBackLinesGetTheDefaultLeadBeforeTheNextStart() {
        val lines = listOf(line(1_000L), line(2_000L))

        assertEquals(0, LyricsTiming.findCurrentLineIndex(lines, positionMs = 1_600L))
        assertEquals(1, LyricsTiming.findCurrentLineIndex(lines, positionMs = 1_800L))
    }

    @Test
    fun leadIsConfigurable() {
        val lines = listOf(line(1_000L), line(2_000L))

        assertEquals(0, LyricsTiming.findCurrentLineIndex(lines, positionMs = 1_900L, leadMs = 0L))
        assertEquals(1, LyricsTiming.findCurrentLineIndex(lines, positionMs = 1_900L, leadMs = 100L))
    }

    @Test
    fun noLeadAcrossAGapOfMoreThanTwoSeconds() {
        val lines = listOf(line(1_000L), line(5_000L))

        assertEquals(0, LyricsTiming.findCurrentLineIndex(lines, positionMs = 4_700L))
        assertEquals(0, LyricsTiming.findCurrentLineIndex(lines, positionMs = 4_999L))
        assertEquals(1, LyricsTiming.findCurrentLineIndex(lines, positionMs = 5_000L))
    }

    @Test
    fun durationBearingLineSuppressesTheLeadIntoABackToBackNextLine() {
        // The next row starts 500 ms after this one, so a plain 300 ms lead would hand over at 1200 ms.
        // The explicit duration of the current row keeps it current until the next row actually starts.
        val lines = listOf(line(1_000L, durationMs = 3_000L), line(1_500L))

        assertEquals(0, LyricsTiming.findCurrentLineIndex(lines, positionMs = 1_450L))
        assertEquals(0, LyricsTiming.findCurrentLineIndex(lines, positionMs = 1_500L))
        assertEquals(1, LyricsTiming.findCurrentLineIndex(lines, positionMs = 1_501L))
    }

    @Test
    fun introInstrumentalIsInsertedForALateFirstVocal() {
        val lines = listOf(line(10_000L, text = "Hi"))

        val result = LyricsTiming.insertInstrumentalBreaks(lines)

        assertEquals(2, result.size)
        val intro = result.first()
        assertTrue(intro.isInstrumental)
        assertEquals("", intro.text)
        assertEquals(1_000L, intro.time)
        assertEquals(9_000L, intro.durationMs)
    }

    @Test
    fun noIntroForAShortLeadIn() {
        val lines = listOf(line(3_000L, text = "Hi"))

        assertEquals(lines, LyricsTiming.insertInstrumentalBreaks(lines))
    }

    @Test
    fun outroInstrumentalNeedsASongDuration() {
        val lines = listOf(line(10_000L, text = "Hi"))

        assertEquals(2, LyricsTiming.insertInstrumentalBreaks(lines, songDurationMs = 0L).size)
        val withOutro = LyricsTiming.insertInstrumentalBreaks(lines, songDurationMs = 30_000L)
        assertEquals(3, withOutro.size)
        val outro = withOutro.last()
        assertTrue(outro.isInstrumental)
        assertEquals(12_500L, outro.time)
        assertEquals(17_500L, outro.durationMs)
    }

    @Test
    fun emptyLyricsStayEmptyWhenInsertingBreaks() {
        assertTrue(LyricsTiming.insertInstrumentalBreaks(emptyList(), songDurationMs = 60_000L).isEmpty())
    }
}
