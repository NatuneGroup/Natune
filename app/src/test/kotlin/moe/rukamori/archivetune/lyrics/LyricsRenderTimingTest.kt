package moe.rukamori.archivetune.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sync lead must follow the TIMING SHAPE, not the container. Enhanced LRC with inline word
 * stamps is word-timed: giving it the LRC lead (300 ms) plus the 150 ms visual offset ran the
 * karaoke sweep ~450 ms ahead, so a short word was already fully lit when it was sung. Line-only
 * rows are written early relative to the audio and still need the lead.
 */
class LyricsRenderTimingTest {
    private fun wordTimedEntry() =
        LyricsEntry(
            time = 1_000L,
            text = "burst again",
            words =
                listOf(
                    WordTimestamp("burst ", 1.0, 1.6),
                    WordTimestamp("a", 1.6, 1.7),
                    WordTimestamp("gain", 1.7, 2.4),
                ),
        )

    private fun lineOnlyEntry() = LyricsEntry(time = 1_000L, text = "burst again")

    @Test
    fun wordTimedRowsTakeNoLead() {
        assertEquals(
            LyricsRenderTiming.TTML_LEAD_MS,
            LyricsRenderTiming.leadMsFor(listOf(wordTimedEntry())),
        )
        assertTrue(LyricsRenderTiming.hasWordTiming(listOf(wordTimedEntry())))
    }

    @Test
    fun lineOnlyRowsTakeTheLrcLead() {
        assertEquals(
            LyricsRenderTiming.LRC_LEAD_MS,
            LyricsRenderTiming.leadMsFor(listOf(lineOnlyEntry())),
        )
        assertFalse(LyricsRenderTiming.hasWordTiming(listOf(lineOnlyEntry())))
    }

    @Test
    fun enhancedLrcWithWordStampsIsNotPushedAhead() {
        // The regression: an enhanced-LRC document is not TTML by container but its word stamps
        // must
        // take the no-lead path. +300 (LRC) + 150 (visual) would sweep a short word ~450 ms early.
        val parsed =
            LyricsUtils.parseLyrics("[00:01.000]<00:01.000>burst <00:01.300>a <00:01.380>gain")
        assertTrue(LyricsRenderTiming.hasWordTiming(parsed))
        assertEquals(0L, LyricsRenderTiming.leadMsFor(parsed))
    }

    @Test
    fun oneWordTimedRowInAMostlyLineOnlyDocumentStillLinesUp() {
        // A mixed document follows its word-timed rows; the lead cannot be per-row because the
        // whole
        // feed shares one offset, so any real word timing means no lead.
        val mixed = listOf(lineOnlyEntry(), wordTimedEntry(), lineOnlyEntry())
        assertEquals(LyricsRenderTiming.TTML_LEAD_MS, LyricsRenderTiming.leadMsFor(mixed))
    }

    @Test
    fun aPlainSyncedDocumentIsLineOnly() {
        val parsed = LyricsUtils.parseLyrics("[00:01.000]burst again")
        assertFalse(LyricsRenderTiming.hasWordTiming(parsed))
        assertEquals(LyricsRenderTiming.LRC_LEAD_MS, LyricsRenderTiming.leadMsFor(parsed))
    }
}
