package moe.rukamori.archivetune.shared.ui.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import moe.rukamori.archivetune.shared.domain.lyrics.KaraokeLine
import moe.rukamori.archivetune.shared.domain.lyrics.LyricsWord

/**
 * The trailing background-vocal subline (TTML x-bg, enhanced-LRC bracket run) must render AND sweep
 * in sync with the lead. These tests pin the fractional sweep, the deadline cover that keeps the
 * frame loop awake through the backing words, and the split that feeds the subline its own word
 * timings.
 */
class LyricsBackgroundSweepTest {
    // "(gimme some day)" — two timed words of a backing vocal, ranges located in the BACKGROUND
    // text.
    private val background = "(gimme some day)"
    private val words =
        listOf(
            LyricsWordTiming(startMs = 1_900, endMs = 2_200, range = 0..8),
            LyricsWordTiming(startMs = 2_200, endMs = 2_600, range = 9..15),
        )

    @Test
    fun theSweepFollowsEachBackgroundWordsOwnSpan() {
        val length = background.length
        // Before the first word nothing is sung.
        assertEquals(
            0f,
            LyricsBackgroundSweep.sungFraction(words, length, 1_800, 16L, false),
            0.0001f,
        )
        // Mid first word: a partial share, not the whole line lit at once.
        val mid = LyricsBackgroundSweep.sungFraction(words, length, 2_050, 16L, false)
        assertTrue(mid > 0f && mid < 9f / length, "mid-word fraction was $mid")
        // When the first word ends its whole slice (up to the next word's start) is lit.
        assertEquals(
            9f / length,
            LyricsBackgroundSweep.sungFraction(words, length, 2_200, 16L, false),
            0.0001f,
        )
        // After the last word the whole subline is bright.
        assertEquals(
            1f,
            LyricsBackgroundSweep.sungFraction(words, length, 2_700, 16L, false),
            0.0001f,
        )
    }

    @Test
    fun reducedMotionSnapsEachBackgroundWord() {
        val length = background.length
        // One frame into the first word is fully lit under reduced motion, not a partial fill.
        assertEquals(
            9f / length,
            LyricsBackgroundSweep.sungFraction(words, length, 1_901, 16L, true),
            0.0001f,
        )
    }

    @Test
    fun noWordTimingsMeansNoSweep() {
        assertEquals(
            0f,
            LyricsBackgroundSweep.sungFraction(emptyList(), background.length, 2_100, 16L, false),
            0.0001f,
        )
    }

    @Test
    fun theDeadlineScheduleKeepsFramesLiveThroughBackgroundWords() {
        // A TTML x-bg span and an enhanced-LRC bracket run both arrive as isBackground words; the
        // renderer's frame loop must stay awake across their sweep, not just the lead line's words.
        val line =
            KaraokeLine(
                time = 1_000,
                text = "Hold on (gimme some day)",
                words =
                    listOf(
                        LyricsWord("Hold", 1.0, 1.4),
                        LyricsWord("on", 1.4, 1.8),
                        LyricsWord("(gimme", 1.9, 2.2, isBackground = true),
                        LyricsWord("some", 2.2, 2.4, isBackground = true),
                        LyricsWord("day)", 2.4, 2.6, isBackground = true),
                    ),
            )
        val rows = LyricsRenderRows.build(listOf(line), splitBackground = true)
        val row = rows.single()
        assertEquals("(gimme some day)", row.background)
        assertEquals(3, row.backgroundWords?.size)
        val schedule = LyricsDeadlineSchedule.of(rows, LyricsSweepGeometry.MIN_SWEEP_MS, false)
        // The backing words keep frames live through their own sweep, not just the lead line's.
        assertTrue(
            (2_000L..2_600L step 16).any { schedule.isLive(it) },
            "background sweep has no live frames",
        )
        // The loop wakes somewhere in the background window after the lead words end (~1.8 s).
        val next = schedule.nextChangeAfter(1_850)
        assertTrue(next != null && next <= 2_600, "no deadline covers the backing words: $next")
    }
}
