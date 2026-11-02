package moe.rukamori.archivetune.shared.domain.transition

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The shared fade shapes must stay identical to the shipped Android CrossfadePolicyTest values. */
class CrossfadeFadeCurveTest {
    @Test
    fun exponentialOutAndLogarithmicInHitTheirEndpoints() {
        assertEquals(1f, CrossfadePolicy.exponentialFadeOutGain(0f), 0.0001f)
        assertEquals(0f, CrossfadePolicy.exponentialFadeOutGain(1f), 0.0001f)
        assertEquals(0f, CrossfadePolicy.logarithmicFadeInGain(0f), 0.0001f)
        assertEquals(1f, CrossfadePolicy.logarithmicFadeInGain(1f), 0.0001f)
    }

    @Test
    fun theCurvePairFrontLoadsTheIncomingVoice() {
        assertTrue(CrossfadePolicy.exponentialFadeOutGain(0.5f) < 0.1f)
        assertTrue(CrossfadePolicy.logarithmicFadeInGain(0.5f) > 0.5f)
    }

    @Test
    fun equalPowerMatchesTheEnvelopeObject() {
        listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { p ->
            assertEquals(
                EqualPowerEnvelope.outgoingGain(p.toDouble()),
                CrossfadePolicy.outgoingGain(CrossfadePolicy.FadeCurve.EQUAL_POWER, p),
                0.0001f,
            )
        }
    }

    @Test
    fun fadePacingTargetsTwentyStepsWithinBounds() {
        assertEquals(300L, CrossfadePolicy.fadeStepIntervalMs(6_000L))
        assertEquals(CrossfadePolicy.FADE_VOLUME_STEP_MIN_MS, CrossfadePolicy.fadeStepIntervalMs(2_000L))
        assertEquals(CrossfadePolicy.FADE_VOLUME_STEP_MAX_MS, CrossfadePolicy.fadeStepIntervalMs(30_000L))
        assertEquals(CrossfadePolicy.FADE_VOLUME_STEP_MIN_MS, CrossfadePolicy.fadeStepIntervalMs(0L))
    }

    @Test
    fun aBlendNeedsPlaybackAndNoContextChange() {
        assertTrue(CrossfadePolicy.mayStartBlend(playbackRequested = true, contextChanged = false))
        assertFalse(CrossfadePolicy.mayStartBlend(playbackRequested = false, contextChanged = false))
        assertFalse(CrossfadePolicy.mayStartBlend(playbackRequested = true, contextChanged = true))
    }
}
