package moe.rukamori.archivetune.shared.domain.dsp.bitperfect

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DspSoftwareStagePlanTest {
    private val everythingOn = SoftwareStageRequest(
        normalizationEnabled = true,
        equalizerEnabled = true,
        speed = 1.25,
        pitchSemitones = 2.0,
        silenceSkippingEnabled = true,
        twoDeckMixingEnabled = true,
    )

    private fun decision(
        requested: Boolean,
        softwareDsp: Boolean = false,
        inputRateHz: Int = 44_100,
        outputRateHz: Int = 0,
        volume: Float = 1f,
    ): BitPerfectDecision = BitPerfectGate.evaluate(
        BitPerfectRouteFacts(
            bitPerfectRequested = requested,
            softwareDspEngaged = softwareDsp,
            usbExclusive = false,
            directPlaybackQueried = true,
            directPlaybackSupported = true,
            inputSampleRateHz = inputRateHz,
            inputBitDepth = 16,
            outputSampleRateHz = outputRateHz,
            effectiveVolume = volume,
        ),
    )

    @Test
    fun bypassTurnsEverySoftwareStageOff() {
        val plan = SoftwareStagePlan.resolve(decision(requested = true), everythingOn)
        assertTrue(plan.bypass)
        assertFalse(plan.normalization)
        assertFalse(plan.equalizer)
        assertFalse(plan.tempoPitch)
        assertFalse(plan.resampling)
        assertFalse(plan.twoDeckMixing)
        assertFalse(plan.silenceSkipping)
        assertFalse(plan.softwareVolume)
    }

    @Test
    fun bypassStillReportsSoftwareVolumeWhenTheRouteIsAttenuated() {
        val plan = SoftwareStagePlan.resolve(decision(requested = true, volume = 0.5f), everythingOn)
        assertTrue(plan.bypass)
        assertTrue(plan.softwareVolume)
    }

    @Test
    fun requestedButUnverifiedRouteKeepsEqAndNormalizationOnly() {
        val plan = SoftwareStagePlan.resolve(decision(requested = true, softwareDsp = true), everythingOn)
        assertFalse(plan.bypass)
        assertTrue(plan.normalization)
        assertTrue(plan.equalizer)
        assertFalse(plan.tempoPitch)
        assertFalse(plan.silenceSkipping)
        assertFalse(plan.twoDeckMixing)
        assertFalse(plan.softwareVolume)
    }

    @Test
    fun unrequestedRouteKeepsEveryStageTheUserEnabled() {
        val plan = SoftwareStagePlan.resolve(decision(requested = false), everythingOn)
        assertFalse(plan.bypass)
        assertTrue(plan.normalization)
        assertTrue(plan.equalizer)
        assertTrue(plan.tempoPitch)
        assertTrue(plan.silenceSkipping)
        assertTrue(plan.twoDeckMixing)
        assertFalse(plan.resampling)
    }

    @Test
    fun resamplingIsReportedWhenTheRouteRunsAtAnotherRate() {
        val plan = SoftwareStagePlan.resolve(
            decision(requested = false, inputRateHz = 44_100, outputRateHz = 48_000),
            everythingOn,
        )
        assertTrue(plan.resampling)
        assertFalse(plan.bypass)
    }
}
