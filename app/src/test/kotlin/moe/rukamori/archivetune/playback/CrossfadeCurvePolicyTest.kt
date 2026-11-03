package moe.rukamori.archivetune.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The studied fade shapes and pacing Apple Music applies to its manual crossfade. */
class CrossfadeCurvePolicyTest {
    @Test
    fun exponentialOutAndLogarithmicInHitTheirEndpoints() {
        assertEquals(1f, CrossfadePolicy.exponentialFadeOutGain(0f), 0.0001f)
        assertEquals(0f, CrossfadePolicy.exponentialFadeOutGain(1f), 0.0001f)
        assertEquals(0f, CrossfadePolicy.logarithmicFadeInGain(0f), 0.0001f)
        assertEquals(1f, CrossfadePolicy.logarithmicFadeInGain(1f), 0.0001f)
    }

    @Test
    fun exponentialOutDecaysFastAndLogarithmicInClimbsFast() {
        // The hand-over is front-loaded onto the incoming track: at the midpoint the outgoing
        // voice is already deep into its tail while the incoming one is past halfway.
        assertTrue(CrossfadePolicy.exponentialFadeOutGain(0.5f) < 0.1f)
        assertTrue(CrossfadePolicy.logarithmicFadeInGain(0.5f) > 0.5f)
        // Monotone across the whole fade — a step back would read as a swell.
        var lastOut = Float.MAX_VALUE
        var lastIn = Float.NEGATIVE_INFINITY
        (0..20).forEach {
            val t = it / 20f
            val out = CrossfadePolicy.exponentialFadeOutGain(t)
            val into = CrossfadePolicy.logarithmicFadeInGain(t)
            assertTrue(out <= lastOut)
            assertTrue(into >= lastIn)
            lastOut = out
            lastIn = into
        }
    }

    @Test
    fun equalPowerCurveDelegatesToTheEqualPowerEnvelope() {
        listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { p ->
            assertEquals(
                CrossfadePolicy.outgoingGain(p),
                CrossfadePolicy.outgoingGain(CrossfadePolicy.FadeCurve.EQUAL_POWER, p),
                0.0001f,
            )
            assertEquals(
                CrossfadePolicy.incomingGain(p),
                CrossfadePolicy.incomingGain(CrossfadePolicy.FadeCurve.EQUAL_POWER, p),
                0.0001f,
            )
        }
    }

    @Test
    fun fadePacingTargetsTwentyStepsWithinBounds() {
        assertEquals(300L, CrossfadePolicy.fadeStepIntervalMs(6_000L))
        assertEquals(
            CrossfadePolicy.FADE_VOLUME_STEP_MIN_MS,
            CrossfadePolicy.fadeStepIntervalMs(2_000L),
        )
        assertEquals(
            CrossfadePolicy.FADE_VOLUME_STEP_MAX_MS,
            CrossfadePolicy.fadeStepIntervalMs(30_000L),
        )
        assertEquals(
            CrossfadePolicy.FADE_VOLUME_STEP_MIN_MS,
            CrossfadePolicy.fadeStepIntervalMs(0L),
        )
    }

    @Test
    fun aBlendNeedsPlaybackAndNoContextChange() {
        assertTrue(CrossfadePolicy.mayStartBlend(playbackRequested = true, contextChanged = false))
        assertFalse(CrossfadePolicy.mayStartBlend(playbackRequested = false, contextChanged = false))
        assertFalse(CrossfadePolicy.mayStartBlend(playbackRequested = true, contextChanged = true))
    }
}
