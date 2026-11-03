package moe.rukamori.archivetune.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The click-free edge rules: which transport edges earn a fade and the ramp shapes. */
class OutputFadePolicyTest {
    @Test
    fun aPauseEdgeFadesOnlyWhileOutputIsLive() {
        // The session-facing wrapper reports PAUSE for pause() and setPlayWhenReady(false);
        // it only crosses a boundary when playback was actually wanted.
        assertTrue(OutputFadePolicy.hasAudibleEdge(OutputFadePolicy.Edge.PAUSE, playWhenReady = true))
        assertFalse(OutputFadePolicy.hasAudibleEdge(OutputFadePolicy.Edge.PAUSE, playWhenReady = false))
    }

    @Test
    fun aPlayEdgeFadesOnlyWhileOutputIsMuted() {
        // play() and setPlayWhenReady(true) only dip when the player was muted; an already
        // live edge forwards straight through so a repeated tap never flickers the level.
        assertTrue(OutputFadePolicy.hasAudibleEdge(OutputFadePolicy.Edge.PLAY, playWhenReady = false))
        assertFalse(OutputFadePolicy.hasAudibleEdge(OutputFadePolicy.Edge.PLAY, playWhenReady = true))
    }

    @Test
    fun fadeOutReachesZeroAndFadeInReachesTheTarget() {
        val steps = OutputFadePolicy.FADE_STEPS
        assertEquals(
            0f,
            OutputFadePolicy.fadeOutLevel(1f, steps - 1),
            0.0001f,
        )
        assertEquals(
            1f,
            OutputFadePolicy.fadeInLevel(1f, steps - 1),
            0.0001f,
        )
        // The first dip keeps most of the level — a hard mute is the click being avoided.
        assertTrue(OutputFadePolicy.fadeOutLevel(1f, 0) > 0.5f)
        assertTrue(OutputFadePolicy.fadeInLevel(1f, 0) > 0f)
    }

    @Test
    fun rampsAreMonotoneAndBounded() {
        var last = 2f
        repeat(OutputFadePolicy.FADE_STEPS) { step ->
            val level = OutputFadePolicy.fadeOutLevel(0.9f, step)
            assertTrue(level in 0f..1f)
            assertTrue(level <= last)
            last = level
        }
        last = -1f
        repeat(OutputFadePolicy.FADE_STEPS) { step ->
            val level = OutputFadePolicy.fadeInLevel(0.9f, step)
            assertTrue(level in 0f..0.9f)
            assertTrue(level >= last)
            last = level
        }
    }
}
