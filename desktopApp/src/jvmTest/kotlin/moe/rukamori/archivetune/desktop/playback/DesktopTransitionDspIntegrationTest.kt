/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.desktop.playback

import moe.rukamori.archivetune.shared.domain.dsp.bitperfect.BitPerfectGate
import moe.rukamori.archivetune.shared.domain.dsp.bitperfect.BitPerfectRouteFacts
import moe.rukamori.archivetune.shared.domain.dsp.bitperfect.SoftwareStagePlan
import moe.rukamori.archivetune.shared.domain.dsp.bitperfect.SoftwareStageRequest
import moe.rukamori.archivetune.shared.domain.dsp.chain.DspSettings
import moe.rukamori.archivetune.shared.domain.dsp.chain.PortableDspChain
import moe.rukamori.archivetune.shared.domain.dsp.eq.EqualizerBand
import moe.rukamori.archivetune.shared.domain.dsp.eq.EqualizerBandType
import moe.rukamori.archivetune.shared.domain.dsp.normalization.NormalizationGain
import moe.rukamori.archivetune.shared.domain.transition.EqualPowerEnvelope
import moe.rukamori.archivetune.shared.domain.transition.TransitionDeck
import moe.rukamori.archivetune.shared.domain.transition.TransitionKind
import moe.rukamori.archivetune.shared.domain.transition.TransitionPlan
import moe.rukamori.archivetune.shared.domain.transition.TwoDeckMixer
import moe.rukamori.archivetune.shared.domain.transition.TwoDeckTransitionScheduler
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Runs the shared transition and DSP policies on the ramp fixture's sample values, which are the PCM shape
 * the desktop host decodes. The native engine has no mixer or software chain yet, so these tests cover the
 * shared policies the desktop host will call once that wiring exists.
 */
class DesktopTransitionDspIntegrationTest {
    private val channels = 2
    private val rate = PlaybackFixtures.RAMP_SAMPLE_RATE

    private val request = SoftwareStageRequest(
        normalizationEnabled = true,
        equalizerEnabled = true,
        speed = 1.0,
        pitchSemitones = 0.0,
        silenceSkippingEnabled = false,
        twoDeckMixingEnabled = true,
    )

    /** Interleaved ramp frames from fixture frame [start], with the same value on every channel. */
    private fun ramp(start: Long, frames: Int): FloatArray =
        FloatArray(frames * channels) { PlaybackFixtures.rampSample(start + it / channels) }

    /** Desktop route facts. The desktop host has no platform direct-playback query unless a USB route is latched. */
    private fun routeFacts(
        requested: Boolean,
        usbExclusive: Boolean = false,
        usbClockRateHz: Int = 0,
        usbTransportBits: Int = 0,
    ) = BitPerfectRouteFacts(
        bitPerfectRequested = requested,
        softwareDspEngaged = false,
        usbExclusive = usbExclusive,
        directPlaybackQueried = false,
        directPlaybackSupported = false,
        inputSampleRateHz = rate,
        inputBitDepth = 24,
        usbClockRateHz = usbClockRateHz,
        usbTransportBits = usbTransportBits,
    )

    @Test
    fun gaplessHandoffSplicesTwoRampDecksAtTheCommitFrame() {
        val plan = TransitionPlan(kind = TransitionKind.GAPLESS, startFrame = 1_000L, overlapFrames = 0L)
        val scheduler = TwoDeckTransitionScheduler(initialDeck = TransitionDeck.FIRST)
        assertTrue(scheduler.arm(TransitionDeck.SECOND, plan))

        val frames = 2_000
        val outgoing = ramp(start = 0L, frames = frames)
        val incoming = ramp(start = 50_000L, frames = frames)
        val output = FloatArray(frames * channels)
        TwoDeckMixer.mix(output, outgoing, incoming, channels, frames, firstFrame = 0L, plan = plan)

        for (frame in 0 until frames) {
            val source = if (frame < 1_000) outgoing else incoming
            for (channel in 0 until channels) {
                val index = frame * channels + channel
                assertEquals(source[index], output[index], "frame $frame channel $channel")
            }
        }
        assertFalse(scheduler.advanceTo(999L))
        assertTrue(scheduler.advanceTo(1_000L))
        assertEquals(TransitionDeck.SECOND, scheduler.activeDeck)
    }

    @Test
    fun equalPowerCrossfadeOverRampDecksIsIndependentOfBlockSplits() {
        val plan = TransitionPlan(kind = TransitionKind.CROSSFADE, startFrame = 1_000L, overlapFrames = 2_000L)
        val frames = 4_000
        val outgoing = ramp(start = 0L, frames = frames)
        val incoming = ramp(start = 2_048L, frames = frames)

        val whole = FloatArray(frames * channels)
        TwoDeckMixer.mix(whole, outgoing, incoming, channels, frames, firstFrame = 0L, plan = plan)

        val split = FloatArray(frames * channels)
        var first = 0
        for (block in listOf(333, 1_000, 2_667)) {
            val blockOutput = FloatArray(block * channels)
            TwoDeckMixer.mix(
                output = blockOutput,
                outgoing = outgoing.copyOfRange(first * channels, (first + block) * channels),
                incoming = incoming.copyOfRange(first * channels, (first + block) * channels),
                channels = channels,
                frames = block,
                firstFrame = first.toLong(),
                plan = plan,
            )
            blockOutput.copyInto(split, first * channels)
            first += block
        }
        assertContentEquals(whole, split)

        for (frame in 1_000L until 3_000L) {
            val gains = EqualPowerEnvelope.gainsAt(plan, frame)
            val power = gains.outgoing * gains.outgoing + gains.incoming * gains.incoming
            assertEquals(1f, power, 1e-5f, "power at frame $frame")
        }
    }

    @Test
    fun normalizedRampMatchesTheGainExactlyAndStaysWithinFullScale() {
        val frames = 4_096
        val input = ramp(start = 0L, frames = frames)
        val gain = NormalizationGain.linearGain(trackGainDb = 6.0, trackPeak = 0.45)
        val plan = SoftwareStagePlan.resolve(BitPerfectGate.evaluate(routeFacts(requested = false)), request)
        assertFalse(plan.bypass)
        assertTrue(plan.normalization)

        val chain = PortableDspChain(rate, channels)
        chain.apply(DspSettings(linearGain = gain), plan)
        val output = chain.process(input, frames)

        assertContentEquals(FloatArray(input.size) { (input[it] * gain).coerceIn(-1f, 1f) }, output)
    }

    @Test
    fun desktopRouteWithoutAPlatformQueryCannotClaimBitPerfect() {
        val decision = BitPerfectGate.evaluate(routeFacts(requested = true))
        assertFalse(decision.bypass)
        assertFalse(decision.verifiedBitPerfect)
        assertEquals("direct playback support could not be queried", decision.failureReason)

        val plan = SoftwareStagePlan.resolve(decision, request)
        assertFalse(plan.bypass)
        assertTrue(plan.normalization)
        assertTrue(plan.equalizer)
    }

    @Test
    fun latchedUsbExclusiveRouteBypassesTheChainAndPassesTheRampUntouched() {
        val decision = BitPerfectGate.evaluate(
            routeFacts(requested = true, usbExclusive = true, usbClockRateHz = rate, usbTransportBits = 24),
        )
        assertTrue(decision.verifiedBitPerfect)
        assertNull(decision.failureReason)

        val plan = SoftwareStagePlan.resolve(decision, request)
        assertTrue(plan.bypass)
        assertFalse(plan.normalization)
        assertFalse(plan.equalizer)

        val chain = PortableDspChain(rate, channels)
        chain.apply(
            DspSettings(
                linearGain = 0.5f,
                equalizerBands = listOf(EqualizerBand(EqualizerBandType.PEAKING, 1_000.0, gainDb = 6.0)),
            ),
            plan,
        )
        val input = ramp(start = 0L, frames = 2_048)
        assertContentEquals(input, chain.process(input, 2_048))
        assertEquals(0, chain.finish().size)
    }
}
