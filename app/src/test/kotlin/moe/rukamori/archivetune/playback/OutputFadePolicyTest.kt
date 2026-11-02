package moe.rukamori.archivetune.playback

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The click-free edge rules: which commands the service re-drives and the ramp shapes. */
class OutputFadePolicyTest {
    @Test
    fun pauseAndPlayCommandsAreIntercepted() {
        assertEquals(
            OutputFadePolicy.Intercepted.PAUSE,
            OutputFadePolicy.interceptedCommand(Player.COMMAND_PAUSE, playWhenReady = true),
        )
        assertEquals(
            OutputFadePolicy.Intercepted.PLAY,
            OutputFadePolicy.interceptedCommand(Player.COMMAND_PLAY, playWhenReady = false),
        )
    }

    @Test
    fun theToggleFollowsTheCurrentIntent() {
        assertEquals(
            OutputFadePolicy.Intercepted.PAUSE,
            OutputFadePolicy.interceptedCommand(Player.COMMAND_PLAY_PAUSE, playWhenReady = true),
        )
        assertEquals(
            OutputFadePolicy.Intercepted.PLAY,
            OutputFadePolicy.interceptedCommand(Player.COMMAND_PLAY_PAUSE, playWhenReady = false),
        )
    }

    @Test
    fun seeksAndStopsPassThroughUnmodified() {
        assertNull(OutputFadePolicy.interceptedCommand(Player.COMMAND_SEEK_TO_NEXT, true))
        assertNull(OutputFadePolicy.interceptedCommand(Player.COMMAND_STOP, true))
        assertNull(OutputFadePolicy.interceptedCommand(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, true))
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
