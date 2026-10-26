package moe.rukamori.archivetune.shared.domain.transition

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TransitionEnvelopeTest {
    private val crossfade = TransitionPlan(kind = TransitionKind.CROSSFADE, startFrame = 100L, overlapFrames = 400L)

    @Test
    fun equalPowerHoldsUnitPowerAcrossEveryOverlapFrame() {
        for (frame in 100L until 500L) {
            val gains = EqualPowerEnvelope.gainsAt(crossfade, frame)
            val power = gains.outgoing * gains.outgoing + gains.incoming * gains.incoming
            assertEquals(1f, power, 1e-5f, "power at frame $frame")
        }
    }

    @Test
    fun midpointIsEqualPowerNotTheLinearDip() {
        val mid = EqualPowerEnvelope.gainsAt(crossfade, 300L)
        assertEquals(sqrt(0.5).toFloat(), mid.outgoing, 1e-5f)
        assertEquals(sqrt(0.5).toFloat(), mid.incoming, 1e-5f)
        // A linear ramp would give 0.5 here, about 3 dB quieter than the equal-power midpoint.
        assertTrue(mid.outgoing > 0.5f)
    }

    @Test
    fun gainsAreUnityOutsideTheOverlapAndSilentBeforeIt() {
        assertEquals(TransitionGains(outgoing = 1f, incoming = 0f), EqualPowerEnvelope.gainsAt(crossfade, 99L))
        assertEquals(TransitionGains(outgoing = 1f, incoming = 0f), EqualPowerEnvelope.gainsAt(crossfade, 100L))
        assertEquals(TransitionGains(outgoing = 0f, incoming = 1f), EqualPowerEnvelope.gainsAt(crossfade, 500L))
        assertEquals(TransitionGains(outgoing = 0f, incoming = 1f), EqualPowerEnvelope.gainsAt(crossfade, 10_000L))
    }

    @Test
    fun gaplessHandoffSplicesAtOneFrameWithoutOverlap() {
        val gapless = TransitionPlan(kind = TransitionKind.GAPLESS, startFrame = 200L, overlapFrames = 0L)
        assertEquals(200L, gapless.endFrame)
        assertEquals(TransitionGains(outgoing = 1f, incoming = 0f), EqualPowerEnvelope.gainsAt(gapless, 199L))
        assertEquals(TransitionGains(outgoing = 0f, incoming = 1f), EqualPowerEnvelope.gainsAt(gapless, 200L))
    }

    @Test
    fun planRejectsOverlapThatContradictsItsKind() {
        assertFailsWith<IllegalArgumentException> { TransitionPlan(TransitionKind.GAPLESS, 200L, 4L) }
        assertFailsWith<IllegalArgumentException> { TransitionPlan(TransitionKind.CROSSFADE, 200L, 0L) }
        assertFailsWith<IllegalArgumentException> { TransitionPlan(TransitionKind.AUTOMIX, -1L, 10L) }
    }

    @Test
    fun mixedOutputDoesNotDependOnHowTheCallerSplitsBlocks() {
        val channels = 2
        val frames = 64
        val outgoing = FloatArray(frames * channels) { (it % 7) * 0.1f - 0.3f }
        val incoming = FloatArray(frames * channels) { (it % 5) * 0.05f + 0.1f }
        val plan = TransitionPlan(kind = TransitionKind.CROSSFADE, startFrame = 10L, overlapFrames = 40L)

        val whole = FloatArray(frames * channels)
        TwoDeckMixer.mix(whole, outgoing, incoming, channels, frames, firstFrame = 0L, plan = plan)

        val split = FloatArray(frames * channels)
        var first = 0
        for (block in listOf(7, 25, 32)) {
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
    }

    @Test
    fun idleMixerPassesOutgoingAtUnityAndSilentDeckAsSilence() {
        val outgoing = floatArrayOf(0.5f, -0.5f, 0.25f, 0.25f)
        val output = FloatArray(4)
        TwoDeckMixer.mix(output, outgoing, null, channels = 2, frames = 2, firstFrame = 0L, plan = null)
        assertContentEquals(outgoing, output)

        TwoDeckMixer.mix(output, null, null, channels = 2, frames = 2, firstFrame = 0L, plan = null)
        assertContentEquals(FloatArray(4), output)
    }

    @Test
    fun mixerRejectsBlocksShorterThanRequested() {
        assertFailsWith<IllegalArgumentException> { TwoDeckMixer.mix(FloatArray(3), null, null, 2, 2, 0L, null) }
    }

    @Test
    fun volumeCurvesRespectBaseVolumeAndMaxGain() {
        assertEquals(0.8f, EqualPowerEnvelope.outgoingVolume(0.0, 0.8f, 1.414f), 0.001f)
        assertEquals(0.8f, EqualPowerEnvelope.incomingVolume(1.0, 0.8f, 1.414f), 0.001f)
        assertEquals(1.414f, EqualPowerEnvelope.incomingVolume(1.0, 2.0f, 1.414f), 0.001f)
        assertEquals(0f, EqualPowerEnvelope.incomingVolume(0.0, 0.8f, 1.414f), 0.001f)
    }
}
