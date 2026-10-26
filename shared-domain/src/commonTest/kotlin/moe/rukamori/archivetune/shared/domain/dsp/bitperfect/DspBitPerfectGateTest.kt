package moe.rukamori.archivetune.shared.domain.dsp.bitperfect

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DspBitPerfectGateTest {
    private fun facts(
        requested: Boolean = true,
        softwareDsp: Boolean = false,
        usbExclusive: Boolean = false,
        directQueried: Boolean = true,
        directSupported: Boolean = true,
        inputRateHz: Int = 44_100,
        inputBitDepth: Int = 16,
        outputRateHz: Int = 0,
        volume: Float = 1f,
        usbClockRateHz: Int = 0,
        usbTransportBits: Int = 0,
    ) = BitPerfectRouteFacts(
        bitPerfectRequested = requested,
        softwareDspEngaged = softwareDsp,
        usbExclusive = usbExclusive,
        directPlaybackQueried = directQueried,
        directPlaybackSupported = directSupported,
        inputSampleRateHz = inputRateHz,
        inputBitDepth = inputBitDepth,
        outputSampleRateHz = outputRateHz,
        effectiveVolume = volume,
        usbClockRateHz = usbClockRateHz,
        usbTransportBits = usbTransportBits,
    )

    @Test
    fun aRequestOnADirectRouteWithoutSoftwareStagesBypassesAndVerifies() {
        val decision = BitPerfectGate.evaluate(facts())
        assertTrue(decision.requested)
        assertTrue(decision.bypass)
        assertTrue(decision.verifiedBitPerfect)
        assertFalse(decision.resamplerActive)
        assertFalse(decision.softwareVolumeActive)
        assertNull(decision.failureReason)
    }

    @Test
    fun withoutARequestNothingIsBypassedOrClaimed() {
        val decision = BitPerfectGate.evaluate(facts(requested = false))
        assertFalse(decision.requested)
        assertFalse(decision.bypass)
        assertFalse(decision.verifiedBitPerfect)
        assertNull(decision.failureReason)
    }

    @Test
    fun anEngagedSoftwareStageKeepsTheChainAndWithholdsTheClaim() {
        val decision = BitPerfectGate.evaluate(facts(softwareDsp = true))
        assertFalse(decision.bypass)
        assertFalse(decision.verifiedBitPerfect)
    }

    @Test
    fun anUnqueryableDirectRouteIsReportedNotAssumed() {
        val decision = BitPerfectGate.evaluate(facts(directQueried = false))
        assertFalse(decision.bypass)
        assertFalse(decision.verifiedBitPerfect)
        assertEquals("direct playback support could not be queried", decision.failureReason)
    }

    @Test
    fun aRouteThatCannotPlayTheFormatDirectlyDoesNotBypass() {
        val decision = BitPerfectGate.evaluate(facts(directSupported = false))
        assertFalse(decision.bypass)
        assertFalse(decision.verifiedBitPerfect)
    }

    @Test
    fun resamplingToAnotherRateDisablesBypass() {
        val decision = BitPerfectGate.evaluate(facts(inputRateHz = 44_100, outputRateHz = 48_000))
        assertTrue(decision.resamplerActive)
        assertFalse(decision.bypass)
        assertFalse(decision.verifiedBitPerfect)
    }

    @Test
    fun anUnknownOutputRateIsTreatedAsTheInputRate() {
        val decision = BitPerfectGate.evaluate(facts(inputRateHz = 44_100, outputRateHz = 0))
        assertFalse(decision.resamplerActive)
        assertTrue(decision.bypass)
    }

    @Test
    fun softwareVolumeBypassesTheStagesButWithholdsTheClaim() {
        val decision = BitPerfectGate.evaluate(facts(volume = 0.5f))
        assertTrue(decision.bypass)
        assertTrue(decision.softwareVolumeActive)
        assertFalse(decision.verifiedBitPerfect)
    }

    @Test
    fun usbExclusiveClaimNeedsTheLatchedClockAndTransportToMatch() {
        fun usb(clockHz: Int, transportBits: Int) = BitPerfectGate.evaluate(
            facts(usbExclusive = true, inputRateHz = 48_000, inputBitDepth = 24, usbClockRateHz = clockHz, usbTransportBits = transportBits),
        )

        val matched = usb(clockHz = 48_000, transportBits = 24)
        assertTrue(matched.verifiedBitPerfect)
        assertNull(matched.failureReason)

        val clockMismatch = usb(clockHz = 44_100, transportBits = 24)
        assertFalse(clockMismatch.verifiedBitPerfect)
        assertEquals("USB clock locked at 44100Hz", clockMismatch.failureReason)

        val narrowTransport = usb(clockHz = 48_000, transportBits = 16)
        assertFalse(narrowTransport.verifiedBitPerfect)
        assertEquals("USB transport carries 16bit", narrowTransport.failureReason)

        val unlatched = usb(clockHz = 0, transportBits = 0)
        assertFalse(unlatched.verifiedBitPerfect)
        assertNull(unlatched.failureReason)
    }
}
