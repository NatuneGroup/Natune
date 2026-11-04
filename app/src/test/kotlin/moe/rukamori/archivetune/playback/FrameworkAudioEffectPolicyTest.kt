package moe.rukamori.archivetune.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameworkAudioEffectPolicyTest {
    private fun settings(
        enabled: Boolean = true,
        bandLevelsMb: List<Int> = emptyList(),
        outputGainEnabled: Boolean = false,
        outputGainMb: Int = 0,
        bassBoostEnabled: Boolean = false,
        bassBoostStrength: Int = 0,
        virtualizerEnabled: Boolean = false,
        virtualizerStrength: Int = 0,
        autoHeadroomEnabled: Boolean = false,
    ) = EqSettings(
        enabled = enabled,
        bandLevelsMb = bandLevelsMb,
        outputGainEnabled = outputGainEnabled,
        outputGainMb = outputGainMb,
        bassBoostEnabled = bassBoostEnabled,
        bassBoostStrength = bassBoostStrength,
        virtualizerEnabled = virtualizerEnabled,
        virtualizerStrength = virtualizerStrength,
        autoHeadroomEnabled = autoHeadroomEnabled,
    )

    @Test
    fun `the session equalizer never renders the stored curve`() {
        val plan = FrameworkAudioEffectPolicy.plan(settings(enabled = true, bandLevelsMb = listOf(600, -300, 900)))

        assertFalse("the software chain owns the equalizer switch", plan.equalizerEnabled)
        assertTrue("the software chain owns the band levels", plan.bandLevelsMb.isEmpty())
    }

    @Test
    fun `bass boost and the virtualizer keep their stored switches`() {
        val plan =
            FrameworkAudioEffectPolicy.plan(
                settings(bassBoostEnabled = true, bassBoostStrength = 800, virtualizerEnabled = true, virtualizerStrength = 400),
            )

        assertTrue(plan.bassBoostEnabled)
        assertEquals(800, plan.bassBoostStrength)
        assertTrue(plan.virtualizerEnabled)
        assertEquals(400, plan.virtualizerStrength)
    }

    @Test
    fun `the equalizer switch still gates the session effects`() {
        val plan =
            FrameworkAudioEffectPolicy.plan(
                settings(enabled = false, bassBoostEnabled = true, virtualizerEnabled = true, autoHeadroomEnabled = true),
            )

        assertFalse(plan.bassBoostEnabled)
        assertFalse(plan.virtualizerEnabled)
        assertFalse(plan.loudnessEnabled)
    }

    @Test
    fun `the output gain stays with the software chain`() {
        val plan =
            FrameworkAudioEffectPolicy.plan(
                settings(bandLevelsMb = listOf(300, 600), outputGainEnabled = true, outputGainMb = -600),
            )

        assertFalse("the chain's preamp renders the output gain", plan.loudnessEnabled)
        assertEquals(0, plan.loudnessTargetGainMb)
    }

    @Test
    fun `automatic headroom keeps attenuating the session gain`() {
        val plan =
            FrameworkAudioEffectPolicy.plan(
                settings(bandLevelsMb = listOf(300, 900, -200), outputGainEnabled = true, outputGainMb = -600, autoHeadroomEnabled = true),
            )

        assertTrue(plan.loudnessEnabled)
        assertEquals("the highest stored boost", -900, plan.loudnessTargetGainMb)
    }

    @Test
    fun `strengths are bounded to the platform range`() {
        val plan =
            FrameworkAudioEffectPolicy.plan(
                settings(bassBoostEnabled = true, bassBoostStrength = 4_000, virtualizerEnabled = true, virtualizerStrength = -20),
            )

        assertEquals(1_000, plan.bassBoostStrength)
        assertEquals(0, plan.virtualizerStrength)
    }
}
