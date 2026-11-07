package moe.rukamori.archivetune.ui.component

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Interlude detection and the three dots. The break is the silence between the furthest point the earlier
 * lines reach and the next line's start; the dots pulse on the lyrics timeline so the loop is seamless and
 * a break of any length keeps the same rhythm.
 */
class EnhancedInterludeTest {
    @Test
    fun aLongSilenceBecomesABreakOwnedByTheLineAfterIt() {
        val breaks =
            enhancedInterludes(
                starts = intArrayOf(1_000, 30_000),
                ends = intArrayOf(5_000, 34_000),
            )
        assertEquals(setOf(1), breaks.keys)
        assertEquals(5_000L, breaks.getValue(1).startMs)
        assertEquals(30_000L, breaks.getValue(1).endMs)
    }

    @Test
    fun ordinaryPhrasingIsNotABreak() {
        val breaks =
            enhancedInterludes(
                starts = intArrayOf(1_000, 6_000),
                ends = intArrayOf(5_000, 9_000),
            )
        assertTrue(breaks.isEmpty())
    }

    @Test
    fun overlappingDuetLinesDoNotHideARealBreak() {
        // The second voice reaches to 20 s; the silence the listener hears runs to the next line at 32 s.
        val breaks =
            enhancedInterludes(
                starts = intArrayOf(1_000, 1_000, 32_000),
                ends = intArrayOf(20_000, 4_000, 36_000),
            )
        assertEquals(setOf(2), breaks.keys)
        assertEquals(20_000L, breaks.getValue(2).startMs)
    }

    @Test
    fun theGateCoversOnlyTheBreakItself() {
        var lyrics = buildSyncedLyrics(
            listOf(
                moe.rukamori.archivetune.lyrics.LyricsEntry(time = 1_000L, text = "a"),
                moe.rukamori.archivetune.lyrics.LyricsEntry(time = 40_000L, text = "b"),
            ),
            emptyMap(),
        )
        // The line-synced hold is deliberately shorter than the break, so the dots own the tail of it.
        val gate = EnhancedInterludeGate { lyrics }
        assertTrue(gate.covers(20_000))
        assertFalse(gate.covers(41_000))
        // A rebuilt document is re-derived on the next ask instead of serving the old breaks.
        lyrics = buildSyncedLyrics(emptyList(), emptyMap())
        assertFalse(gate.covers(20_000))
    }

    @Test
    fun everyDotIsSeamlessAcrossTheCycleBoundary() {
        for (index in 0 until EnhancedInterludeMotion.DOT_COUNT) {
            val atWrap = EnhancedInterludeMotion.dotLight(EnhancedInterludeMotion.PULSE_CYCLE_MS, index, reduceMotion = false)
            val atZero = EnhancedInterludeMotion.dotLight(0L, index, reduceMotion = false)
            assertEquals("dot $index restarts at the wrap", atZero, atWrap, 0.0001f)
            val before = EnhancedInterludeMotion.dotLight(EnhancedInterludeMotion.PULSE_CYCLE_MS - 1L, index, reduceMotion = false)
            assertTrue("dot $index jumps at the wrap", abs(before - atWrap) < 0.02f)
        }
    }

    @Test
    fun theBreathIsSeamlessAndLouderThanTheRest() {
        assertEquals(
            EnhancedInterludeMotion.breath(0L),
            EnhancedInterludeMotion.breath(EnhancedInterludeMotion.BREATH_CYCLE_MS),
            0.0001f,
        )
        val before = EnhancedInterludeMotion.breath(EnhancedInterludeMotion.BREATH_CYCLE_MS - 1L)
        assertTrue(abs(before - EnhancedInterludeMotion.breath(0L)) < 0.02f)
    }

    @Test
    fun theDotsPulseRepeatedlyAndNeverJump() {
        // Sampled every frame over 30 s. A fill tied to the break's own progress steps at every line and
        // stops once it is done; this curve has to stay continuous and keep turning over.
        var previous = EnhancedInterludeMotion.dotLight(0L, 0, reduceMotion = false)
        var turns = 0
        var rising = true
        for (position in 0L..30_000L step 16L) {
            val light = EnhancedInterludeMotion.dotLight(position, 0, reduceMotion = false)
            assertTrue("dot jumped at $position: $previous -> $light", abs(light - previous) < 0.05f)
            val nowRising = light >= previous
            if (nowRising != rising) {
                turns++
                rising = nowRising
            }
            previous = light
        }
        assertTrue("the pulse turned only $turns times in 30 s", turns > 10)
    }

    @Test
    fun theDotsAreVisibleOnlyInsideTheirBreak() {
        val breakWindow = EnhancedInterlude(10_000L, 20_000L)
        assertEquals(0f, EnhancedInterludeMotion.visibility(breakWindow, 9_999), 0.0001f)
        assertEquals(0f, EnhancedInterludeMotion.visibility(breakWindow, 20_000), 0.0001f)
        // A 400 ms fade in, full strength in the middle, a 400 ms fade out.
        assertEquals(0.5f, EnhancedInterludeMotion.visibility(breakWindow, 10_200), 0.01f)
        assertEquals(1f, EnhancedInterludeMotion.visibility(breakWindow, 15_000), 0.0001f)
        assertEquals(0.5f, EnhancedInterludeMotion.visibility(breakWindow, 19_800), 0.01f)
    }

    @Test
    fun theDotsKeepArchiveTunesSize() {
        // KaraokeBreathingDotsDefaults: `size = 16.dp`, `margin = 12.dp`, three dots. The previous
        // 14 dp dots 8 dp apart read as three specks under a 26 sp line, which is what was reported.
        assertEquals(16, DOT_DIAMETER_DP)
        assertEquals(12, DOT_GAP_DP)
        assertEquals(3 * DOT_DIAMETER_DP + 2 * DOT_GAP_DP, DOTS_WIDTH_DP)
        // Tall enough for the 1.3x end swell, so the dots never clip as they grow into the next line.
        assertTrue(DOTS_HEIGHT_DP * 1f >= DOT_DIAMETER_DP * EnhancedInterludeMotion.END_SWELL_TO)
    }

    @Test
    fun theEndSwellLeavesAndReentersTheBreathWithoutAJump() {
        val breakWindow = EnhancedInterlude(10_000L, 20_000L)
        val swellStart = breakWindow.endMs - EnhancedInterludeMotion.END_SWELL_MS
        // Neutral outside its window: the swell is a factor of 1, not a second scale to take a max of.
        assertEquals(1f, EnhancedInterludeMotion.endSwell(breakWindow, 9_000L), 0.0001f)
        assertEquals(1f, EnhancedInterludeMotion.endSwell(breakWindow, swellStart), 0.0001f)
        // One frame inside the window the change is under a percent — no 5% snap on the first frame.
        assertTrue(
            abs(EnhancedInterludeMotion.endSwell(breakWindow, swellStart + 16L) - 1f) < 0.01f,
        )
        assertEquals(
            EnhancedInterludeMotion.END_SWELL_TO,
            EnhancedInterludeMotion.endSwell(breakWindow, breakWindow.endMs - 1L),
            0.01f,
        )
    }

    @Test
    fun theDotsNeverJumpAtAnyFrameOfABreak() {
        // Every frame the break is drawn on, including the frames where the end swell opens and closes.
        // The break owns [startMs, endMs): at endMs the indicator's own visibility is 0, so the swell's
        // return to 1 there is a step in a value nothing draws, not a jump the viewer can see.
        val breakWindow = EnhancedInterlude(8_000L, 20_000L)
        var previous = EnhancedInterludeMotion.scale(breakWindow, 8_000L, reduceMotion = false)
        for (position in 8_000L until breakWindow.endMs step 16L) {
            val scale = EnhancedInterludeMotion.scale(breakWindow, position, reduceMotion = false)
            assertTrue("scale jumped at $position: $previous -> $scale", abs(scale - previous) < 0.02f)
            previous = scale
        }
    }

    @Test
    fun reducedMotionKeepsTheDotsAtTheirSeatedSize() {
        val breakWindow = EnhancedInterlude(8_000L, 20_000L)
        assertEquals(
            1f,
            EnhancedInterludeMotion.scale(breakWindow, 9_000L, reduceMotion = true),
            0.0001f,
        )
        // The end swell is motion too: under reduced motion the dots stay at 1 into the arriving line
        // instead of growing 1.3x over the last 1.5 s of the break.
        assertEquals(
            1f,
            EnhancedInterludeMotion.scale(
                breakWindow,
                breakWindow.endMs - 1L,
                reduceMotion = true,
            ),
            0.0001f,
        )
    }

    @Test
    fun reducedMotionLightsOneDotAtATime() {
        val lit = (0 until EnhancedInterludeMotion.DOT_COUNT).map { EnhancedInterludeMotion.dotLight(100L, it, true) }
        assertEquals(1, lit.count { it == 1f })
    }
}
