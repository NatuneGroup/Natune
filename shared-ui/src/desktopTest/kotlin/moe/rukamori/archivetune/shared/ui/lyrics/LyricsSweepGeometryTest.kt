package moe.rukamori.archivetune.shared.ui.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The draw-phase sweep share and its feathered edge, in the scalar form the platform renderer reads. */
class LyricsSweepGeometryTest {
    @Test
    fun theSweepShareTravelsTheWordWithTheMinimumSweep() {
        // A one-second word is half sung halfway through.
        assertEquals(0.5f, LyricsSweepGeometry.progressAt(1_000L, 2_000L, 1_500L, reduceMotion = false))
        // A word shorter than the minimum sweep still takes the minimum to cross.
        val tiny = LyricsSweepGeometry.progressAt(1_000L, 1_050L, 1_075L, reduceMotion = false)
        assertEquals(0.5f, tiny, 0.01f)
    }

    @Test
    fun nothingLightsBeforeTheWordAndItIsDoneAtItsEnd() {
        assertEquals(0f, LyricsSweepGeometry.progressAt(1_000L, 2_000L, 999L, reduceMotion = false))
        assertEquals(0f, LyricsSweepGeometry.progressAt(1_000L, 2_000L, 1_000L, reduceMotion = false))
        assertEquals(1f, LyricsSweepGeometry.progressAt(1_000L, 2_000L, 2_000L, reduceMotion = false))
        assertEquals(1f, LyricsSweepGeometry.progressAt(1_000L, 2_000L, 5_000L, reduceMotion = false))
    }

    @Test
    fun reducedMotionJumpsWordToWord() {
        assertEquals(0f, LyricsSweepGeometry.progressAt(1_000L, 2_000L, 1_500L, reduceMotion = true))
        assertEquals(1f, LyricsSweepGeometry.progressAt(1_000L, 2_000L, 1_000L, reduceMotion = true))
    }

    @Test
    fun theBoxShareIsTheScalarShare() {
        val box = LyricsWordBox(startMs = 500L, endMs = 1_500L, leftPx = 0f, rightPx = 80f, line = 0)
        assertEquals(
            LyricsSweepGeometry.progressAt(500L, 1_500L, 750L, reduceMotion = false),
            LyricsSweepGeometry.progressAt(box, 750L, reduceMotion = false),
        )
    }

    @Test
    fun aFinishedWordNeedsNoFeather() {
        assertFalse(LyricsSweepGeometry.sweepComplete(0f))
        assertFalse(LyricsSweepGeometry.sweepComplete(0.5f))
        assertFalse(LyricsSweepGeometry.sweepComplete(0.999f))
        assertTrue(LyricsSweepGeometry.sweepComplete(1f))
    }
}
