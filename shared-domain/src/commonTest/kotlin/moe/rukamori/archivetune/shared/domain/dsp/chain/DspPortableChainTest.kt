package moe.rukamori.archivetune.shared.domain.dsp.chain

import moe.rukamori.archivetune.shared.domain.dsp.DspTestSignals
import moe.rukamori.archivetune.shared.domain.dsp.bitperfect.BitPerfectGate
import moe.rukamori.archivetune.shared.domain.dsp.bitperfect.BitPerfectRouteFacts
import moe.rukamori.archivetune.shared.domain.dsp.bitperfect.SoftwareStagePlan
import moe.rukamori.archivetune.shared.domain.dsp.bitperfect.SoftwareStageRequest
import moe.rukamori.archivetune.shared.domain.dsp.eq.EqualizerBand
import moe.rukamori.archivetune.shared.domain.dsp.eq.EqualizerBandType
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DspPortableChainTest {
    private val rate = 48_000
    private val channels = 2

    private val bypassPlan: SoftwareStagePlan = SoftwareStagePlan.resolve(
        decision = BitPerfectGate.evaluate(
            BitPerfectRouteFacts(
                bitPerfectRequested = true,
                softwareDspEngaged = false,
                usbExclusive = false,
                directPlaybackQueried = true,
                directPlaybackSupported = true,
                inputSampleRateHz = rate,
                inputBitDepth = 16,
            ),
        ),
        request = SoftwareStageRequest(
            normalizationEnabled = true,
            equalizerEnabled = true,
            speed = 1.5,
            pitchSemitones = 2.0,
            silenceSkippingEnabled = true,
            twoDeckMixingEnabled = true,
        ),
    )

    /** A plan that runs only the requested stages, as an unbypassed track would. */
    private fun plan(normalization: Boolean = false, equalizer: Boolean = false, tempoPitch: Boolean = false) =
        SoftwareStagePlan(
            bypass = false,
            normalization = normalization,
            equalizer = equalizer,
            tempoPitch = tempoPitch,
            resampling = false,
            twoDeckMixing = false,
            silenceSkipping = false,
            softwareVolume = false,
        )

    @Test
    fun bypassedPlanReturnsTheCallersSamplesUnchanged() {
        assertTrue(bypassPlan.bypass)
        val chain = PortableDspChain(rate, channels)
        chain.apply(
            DspSettings(
                linearGain = 0.5f,
                equalizerBands = listOf(EqualizerBand(EqualizerBandType.PEAKING, 1_000.0, gainDb = 6.0)),
                speed = 1.5,
                pitchSemitones = 2.0,
            ),
            bypassPlan,
        )
        assertTrue(chain.isBypassed)

        val input = DspTestSignals.sine(1_000.0, rate, frames = 4_096, channels = channels)
        assertContentEquals(input, chain.process(input, 4_096))
        assertEquals(0, chain.finish().size)
    }

    @Test
    fun engagedPlanAppliesNormalizationThenClampsToFullScale() {
        val chain = PortableDspChain(rate, channels)
        chain.apply(DspSettings(linearGain = 2f), plan(normalization = true))
        assertFalse(chain.isBypassed)

        assertContentEquals(FloatArray(8) { 0.5f }, chain.process(FloatArray(8) { 0.25f }, frames = 4))
        assertContentEquals(FloatArray(8) { 1f }, chain.process(FloatArray(8) { 0.75f }, frames = 4))
        assertContentEquals(FloatArray(8) { -1f }, chain.process(FloatArray(8) { -0.75f }, frames = 4))
    }

    @Test
    fun planWithoutNormalizationLeavesSamplesAlone() {
        val chain = PortableDspChain(rate, channels)
        chain.apply(DspSettings(linearGain = 0.5f), plan())
        val input = DspTestSignals.sine(440.0, rate, frames = 512, channels = channels)
        assertContentEquals(input, chain.process(input, 512))
    }

    @Test
    fun bypassClearsFilterHistoryBeforeTheNextEngagedBlock() {
        val settings = DspSettings(equalizerBands = listOf(EqualizerBand(EqualizerBandType.PEAKING, 500.0, gainDb = 9.0, q = 0.7)))
        val engaged = plan(equalizer = true)

        val chain = PortableDspChain(rate, channels)
        chain.apply(settings, engaged)
        chain.process(DspTestSignals.sine(500.0, rate, frames = 2_048, channels = channels, amplitude = 0.8), 2_048)
        chain.apply(settings, bypassPlan)
        chain.process(FloatArray(256 * channels), 256)
        chain.apply(settings, engaged)

        val next = DspTestSignals.sine(1_000.0, rate, frames = 1_024, channels = channels, amplitude = 0.3)
        val actual = chain.process(next, 1_024)

        val freshChain = PortableDspChain(rate, channels)
        freshChain.apply(settings, engaged)
        assertContentEquals(freshChain.process(next, 1_024), actual)
    }

    @Test
    fun tempoStageRunsOnlyWhenThePlanEnablesIt() {
        val frames = 2 * rate
        val input = DspTestSignals.sine(440.0, rate, frames, channels = channels)

        val disabled = PortableDspChain(rate, channels)
        disabled.apply(DspSettings(speed = 2.0), plan(tempoPitch = false))
        val disabledFrames = (disabled.process(input.copyOf(), frames).size + disabled.finish().size) / channels
        assertEquals(frames, disabledFrames)

        val enabled = PortableDspChain(rate, channels)
        enabled.apply(DspSettings(speed = 2.0), plan(tempoPitch = true))
        val enabledFrames = (enabled.process(input.copyOf(), frames).size + enabled.finish().size) / channels
        assertTrue(abs(enabledFrames - frames / 2) <= 960, "2x speed produced $enabledFrames frames")
    }
}
