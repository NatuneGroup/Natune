package moe.rukamori.archivetune.desktop.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import moe.rukamori.archivetune.shared.domain.dsp.bitperfect.BitPerfectRouteFacts

/**
 * The desktop engine has no pitch stage, so the host reports pitch as unsupported. These pin what follows: a pitch value
 * cannot claim the samples were altered, and it cannot withhold bit-perfect output.
 */
class DesktopStagePlanTest {
    @Test
    fun aPitchRequestDoesNotAlterTheSamples() {
        val requested = PLAIN_REQUEST.copy(pitchSemitones = 2.0)
        val plan = desktopStagePlan(StageConfig(request = requested, routeFacts = DIRECT_ROUTE))
        // Every other stage is neutral and the route plays this format directly, so the bypass must stand.
        assertTrue(plan.bypass)
        assertEquals(1f, desktopEffectiveSpeed(plan, requested))
    }

    @Test
    fun anAlreadyNeutralPitchRequestIsHandedOnUntouched() {
        assertEquals(0.0, PLAIN_REQUEST.copy(pitchSemitones = -3.0).neutralPitch().pitchSemitones)
        assertSame(PLAIN_REQUEST, PLAIN_REQUEST.neutralPitch())
    }

    @Test
    fun tempoStillCountsAsAnAlteredSample() {
        val requested = PLAIN_REQUEST.copy(speed = 1.5)
        val plan = desktopStagePlan(StageConfig(request = requested, routeFacts = PLAIN_ROUTE))
        assertFalse(plan.bypass)
        assertTrue(plan.tempoPitch)
        assertEquals(1.5f, desktopEffectiveSpeed(plan, requested))
    }

    private companion object {
        /** A route that answered the direct-playback query and plays this format at the input rate. */
        val DIRECT_ROUTE = BitPerfectRouteFacts(
            bitPerfectRequested = true,
            softwareDspEngaged = false,
            usbExclusive = false,
            directPlaybackQueried = true,
            directPlaybackSupported = true,
            inputSampleRateHz = 44_100,
            inputBitDepth = 16,
        )

        /** The ordinary desktop route: bit-perfect is not requested, so tempo and pitch reach the engine. */
        val PLAIN_ROUTE = UNVERIFIED_ROUTE
    }
}
