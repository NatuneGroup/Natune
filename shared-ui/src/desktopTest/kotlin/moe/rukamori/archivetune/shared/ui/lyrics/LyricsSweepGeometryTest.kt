package moe.rukamori.archivetune.shared.ui.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The draw-phase sweep share and its feathered edge, in the scalar form the platform renderer reads. */
class LyricsSweepGeometryTest {
    @Test
    fun theSweepShareTravelsTheWordOverItsOwnTiming() {
        // A one-second word is half sung halfway through.
        assertEquals(0.5f, LyricsSweepGeometry.progressAt(1_000L, 2_000L, 1_500L, reduceMotion = false))
        // A 50 ms word fills over its own 50 ms, not a stretched floor.
        assertEquals(0.5f, LyricsSweepGeometry.progressAt(1_000L, 1_050L, 1_025L, reduceMotion = false), 0.01f)
        assertEquals(1f, LyricsSweepGeometry.progressAt(1_000L, 1_050L, 1_050L, reduceMotion = false))
        // A zero-length word still fills, over the one-frame guard.
        val empty = LyricsSweepGeometry.progressAt(1_000L, 1_000L, 1_008L, reduceMotion = false)
        assertEquals(0.5f, empty, 0.01f)
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
        // Before the word starts nothing is lit; once it starts the jump is the whole word.
        assertEquals(0f, LyricsSweepGeometry.progressAt(1_000L, 2_000L, 999L, reduceMotion = true))
        assertEquals(1f, LyricsSweepGeometry.progressAt(1_000L, 2_000L, 1_500L, reduceMotion = true))
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
