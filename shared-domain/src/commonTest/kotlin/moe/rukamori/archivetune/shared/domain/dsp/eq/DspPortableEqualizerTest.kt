package moe.rukamori.archivetune.shared.domain.dsp.eq

import moe.rukamori.archivetune.shared.domain.dsp.DspTestSignals
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DspPortableEqualizerTest {
    private val rate = 48_000

    @Test
    fun flatBandsLeaveTheSamplesUntouched() {
        val equalizer = PortableEqualizer(rate, channels = 2)
        equalizer.setBands(listOf(EqualizerBand(EqualizerBandType.PEAKING, 1_000.0, gainDb = 0.0)))
        assertFalse(equalizer.isEngaged)

        val samples = DspTestSignals.sine(1_000.0, rate, frames = 1_024, channels = 2)
        val before = samples.copyOf()
        equalizer.process(samples, 1_024)
        assertContentEquals(before, samples)
    }

    @Test
    fun boostedBandRaisesItsCentreFrequencyByTheRequestedGain() {
        val equalizer = PortableEqualizer(rate, channels = 1)
        equalizer.setBands(listOf(EqualizerBand(EqualizerBandType.PEAKING, 1_000.0, gainDb = 6.0)))
        assertTrue(equalizer.isEngaged)

        val frames = 24_000
        val samples = DspTestSignals.sine(1_000.0, rate, frames, channels = 1, amplitude = 0.1)
        val inputRms = DspTestSignals.rms(samples, channels = 1, fromFrame = frames / 2, toFrame = frames)
        equalizer.process(samples, frames)
        val outputRms = DspTestSignals.rms(samples, channels = 1, fromFrame = frames / 2, toFrame = frames)
        // The second half is steady state, after the filter transient has died away.
        assertEquals(10.0.pow(6.0 / 20.0), outputRms / inputRms, 1e-3)
    }

    @Test
    fun zeroGainBandsDoNotChangeTheCascade() {
        val withFlatBand = PortableEqualizer(rate, channels = 2)
        withFlatBand.setBands(
            listOf(
                EqualizerBand(EqualizerBandType.PEAKING, 300.0, gainDb = 0.0),
                EqualizerBand(EqualizerBandType.HIGH_SHELF, 4_000.0, gainDb = -4.0),
            ),
        )
        val withoutFlatBand = PortableEqualizer(rate, channels = 2)
        withoutFlatBand.setBands(listOf(EqualizerBand(EqualizerBandType.HIGH_SHELF, 4_000.0, gainDb = -4.0)))

        val expected = DspTestSignals.sine(5_000.0, rate, frames = 512, channels = 2)
        val actual = expected.copyOf()
        withoutFlatBand.process(expected, 512)
        withFlatBand.process(actual, 512)
        assertContentEquals(expected, actual)
    }

    @Test
    fun resetForgetsTheFilterMemory() {
        val equalizer = PortableEqualizer(rate, channels = 1)
        equalizer.setBands(listOf(EqualizerBand(EqualizerBandType.LOW_SHELF, 200.0, gainDb = 9.0)))
        equalizer.process(DspTestSignals.sine(100.0, rate, frames = 512, channels = 1), 512)

        equalizer.reset()
        val silence = FloatArray(512)
        equalizer.process(silence, 512)
        DspTestSignals.assertAllZero(silence)
    }

    @Test
    fun bandsOutsideTheSupportedRangeAreRejected() {
        assertFailsWith<IllegalArgumentException> { EqualizerBand(EqualizerBandType.PEAKING, 1_000.0, gainDb = 30.0) }
        assertFailsWith<IllegalArgumentException> { EqualizerBand(EqualizerBandType.PEAKING, 0.0, gainDb = 3.0) }
        assertFailsWith<IllegalArgumentException> { EqualizerBand(EqualizerBandType.PEAKING, 1_000.0, gainDb = 3.0, q = 0.0) }
    }

    @Test
    fun aSameGainFrequencyChangeRetunesTheBand() {
        val equalizer = warmedUp(EqualizerBand(EqualizerBandType.PEAKING, 1_000.0, gainDb = 6.0))
        equalizer.setBands(listOf(EqualizerBand(EqualizerBandType.PEAKING, 4_000.0, gainDb = 6.0)))

        // A cache keyed on the gain alone would keep the 1 kHz design and leave the 4 kHz sine at about 1.1x.
        val stale = BiquadDesign.magnitude(BiquadDesign.peaking(rate, 1_000.0, 6.0, 1.0), rate, 4_000.0)
        val expected = BiquadDesign.magnitude(BiquadDesign.peaking(rate, 4_000.0, 6.0, 1.0), rate, 4_000.0)
        assertTrue(stale < expected - 0.5, "the two designs must differ at the measured frequency")
        assertEquals(expected, gainAt(equalizer, 4_000.0), STEADY_STATE_TOLERANCE)
    }

    @Test
    fun aSameGainQChangeRetunesTheBand() {
        val equalizer = warmedUp(EqualizerBand(EqualizerBandType.PEAKING, 1_000.0, gainDb = -12.0, q = 6.0))
        equalizer.setBands(listOf(EqualizerBand(EqualizerBandType.PEAKING, 1_000.0, gainDb = -12.0, q = 0.4)))

        val stale = BiquadDesign.magnitude(BiquadDesign.peaking(rate, 1_000.0, -12.0, 6.0), rate, 500.0)
        val expected = BiquadDesign.magnitude(BiquadDesign.peaking(rate, 1_000.0, -12.0, 0.4), rate, 500.0)
        assertTrue(stale > expected + 0.2, "the two designs must differ at the measured frequency")
        assertEquals(expected, gainAt(equalizer, 500.0), STEADY_STATE_TOLERANCE)
    }

    @Test
    fun aSameGainTypeChangeRetunesTheBand() {
        val equalizer = warmedUp(EqualizerBand(EqualizerBandType.PEAKING, 1_000.0, gainDb = 12.0))
        equalizer.setBands(listOf(EqualizerBand(EqualizerBandType.LOW_SHELF, 1_000.0, gainDb = 12.0)))

        // A shelf reaches half its gain at its corner; the peaking band reaches all of it.
        val stale = BiquadDesign.magnitude(BiquadDesign.peaking(rate, 1_000.0, 12.0, 1.0), rate, 1_000.0)
        val expected = BiquadDesign.magnitude(BiquadDesign.lowShelf(rate, 1_000.0, 12.0, 1.0), rate, 1_000.0)
        assertTrue(stale > expected + 0.5, "the two shapes must differ at the measured frequency")
        assertEquals(expected, gainAt(equalizer, 1_000.0), STEADY_STATE_TOLERANCE)
    }

    @Test
    fun aSameGainRightChannelOverrideRetunesTheRightChannel() {
        val equalizer = warmedUp(EqualizerBand(EqualizerBandType.PEAKING, 1_000.0, gainDb = 6.0, q = 1.0), channels = 2)
        equalizer.setBands(
            listOf(
                EqualizerBand(
                    type = EqualizerBandType.PEAKING,
                    frequencyHz = 1_000.0,
                    gainDb = 6.0,
                    q = 1.0,
                    rightFrequencyHz = 4_000.0,
                    rightGainDb = 6.0,
                    rightQ = 1.0,
                ),
            ),
        )

        val frames = STEADY_STATE_FRAMES
        val samples = stereo(1_000.0, 4_000.0, frames)
        val leftBefore = DspTestSignals.rms(samples, channels = 2, fromFrame = frames / 2, toFrame = frames)
        val rightBefore = DspTestSignals.rms(samples, channels = 2, fromFrame = frames / 2, toFrame = frames, channel = 1)
        equalizer.process(samples, frames)
        val left = DspTestSignals.rms(samples, channels = 2, fromFrame = frames / 2, toFrame = frames) / leftBefore
        val right = DspTestSignals.rms(samples, channels = 2, fromFrame = frames / 2, toFrame = frames, channel = 1) / rightBefore

        assertEquals(
            BiquadDesign.magnitude(BiquadDesign.peaking(rate, 1_000.0, 6.0, 1.0), rate, 1_000.0),
            left,
            STEADY_STATE_TOLERANCE,
        )
        // Without the override in the cache key the right channel keeps the 1 kHz design and stays near unity.
        assertEquals(
            BiquadDesign.magnitude(BiquadDesign.peaking(rate, 4_000.0, 6.0, 1.0), rate, 4_000.0),
            right,
            STEADY_STATE_TOLERANCE,
        )
    }

    /**
     * A stereo graphic preset can boost only the right driver. The left band is at 0 dB, so a cascade that
     * decides engagement from the left gain alone returns from [PortableEqualizer.process] and never renders
     * the right correction.
     */
    @Test
    fun aRightOnlyBoostEngagesTheCascadeAndRendersTheRightChannel() {
        val equalizer = PortableEqualizer(rate, channels = 2)
        equalizer.setBands(listOf(EqualizerBand(EqualizerBandType.PEAKING, 1_000.0, gainDb = 0.0, rightGainDb = 6.0)))
        assertTrue(equalizer.isEngaged, "the right channel's 6 dB is not the identity design")

        val frames = STEADY_STATE_FRAMES
        val samples = stereo(1_000.0, 1_000.0, frames)
        val leftBefore = DspTestSignals.rms(samples, channels = 2, fromFrame = frames / 2, toFrame = frames)
        val rightBefore = DspTestSignals.rms(samples, channels = 2, fromFrame = frames / 2, toFrame = frames, channel = 1)
        equalizer.process(samples, frames)
        val left = DspTestSignals.rms(samples, channels = 2, fromFrame = frames / 2, toFrame = frames) / leftBefore
        val right =
            DspTestSignals.rms(samples, channels = 2, fromFrame = frames / 2, toFrame = frames, channel = 1) / rightBefore

        assertEquals(1.0, left, STEADY_STATE_TOLERANCE)
        assertEquals(10.0.pow(6.0 / 20.0), right, STEADY_STATE_TOLERANCE)
    }

    /**
     * A glide can land the left gain at 0 dB while the right keep its boost. The cascade stays engaged on the
     * right design instead of treating the band as flat once the left glide finishes.
     */
    @Test
    fun aGlideThatLandsTheLeftAtFlatKeepsRenderingTheRight() {
        val boosted = EqualizerBand(EqualizerBandType.PEAKING, 1_000.0, gainDb = 6.0, rightGainDb = 6.0)
        val equalizer = PortableEqualizer(rate, channels = 2)
        equalizer.setBands(listOf(boosted))
        equalizer.process(stereo(1_000.0, 1_000.0, 1_024), 1_024)

        equalizer.setBands(listOf(boosted.copy(gainDb = 0.0)), glideFrames = 512)
        val frames = STEADY_STATE_FRAMES
        val samples = stereo(1_000.0, 1_000.0, frames)
        val leftBefore = DspTestSignals.rms(samples, channels = 2, fromFrame = frames / 2, toFrame = frames)
        val rightBefore = DspTestSignals.rms(samples, channels = 2, fromFrame = frames / 2, toFrame = frames, channel = 1)
        equalizer.process(samples, frames)

        assertFalse(equalizer.isGliding)
        assertTrue(equalizer.isEngaged, "the right channel still needs its 6 dB after the left landed flat")
        val left = DspTestSignals.rms(samples, channels = 2, fromFrame = frames / 2, toFrame = frames) / leftBefore
        val right =
            DspTestSignals.rms(samples, channels = 2, fromFrame = frames / 2, toFrame = frames, channel = 1) / rightBefore
        assertEquals(1.0, left, STEADY_STATE_TOLERANCE)
        assertEquals(10.0.pow(6.0 / 20.0), right, STEADY_STATE_TOLERANCE)
    }

    /**
     * Removing a right-channel override glides the right gain down from its old value, so the shared band no longer
     * carries the large gain the secondary design existed for. The channel has to keep that design while the fade
     * runs: a design chosen only from the new band's override flag would jump the right channel to the primary
     * coefficients on the first block instead of walking down. The two designs meet only when the glide lands.
     */
    @Test
    fun removingARightOverrideKeepsTheSecondaryDesignUntilTheGlideLands() {
        val band = EqualizerBand(EqualizerBandType.PEAKING, 1_000.0, gainDb = 0.0, q = 1.0, rightGainDb = 6.0)
        val equalizer = PortableEqualizer(rate, channels = 2)
        equalizer.setBands(listOf(band))
        equalizer.process(stereo(1_000.0, 1_000.0, 1_024), 1_024)

        equalizer.setBands(listOf(band.copy(rightGainDb = null)), glideFrames = 4_096)
        assertTrue(equalizer.isGliding, "removing the override glides the right gain down")

        // Two successive 512-frame blocks inside the 4,096-frame glide: the right channel is still corrected, and
        // the correction keeps falling, instead of both reading unity from a dropped secondary design.
        val frames = 512
        val first = stereo(1_000.0, 1_000.0, frames)
        val firstBefore = rightRms(first, frames)
        equalizer.process(first, frames)
        val firstGain = rightRms(first, frames) / firstBefore

        val second = stereo(1_000.0, 1_000.0, frames)
        val secondBefore = rightRms(second, frames)
        equalizer.process(second, frames)
        val secondGain = rightRms(second, frames) / secondBefore

        assertTrue(equalizer.isGliding, "the glide is longer than the two measured blocks")
        assertTrue(firstGain > 1.4, "the first block after the override is removed keeps its fading boost, was $firstGain")
        assertTrue(secondGain > 1.4, "the next block still fades instead of using the primary design, was $secondGain")
        assertTrue(secondGain < firstGain, "the fade keeps walking down: $firstGain then $secondGain")

        // Once the glide lands the effective gains meet, the secondary design goes away and the cascade is flat again.
        equalizer.process(stereo(1_000.0, 1_000.0, 4_096), 4_096)
        assertFalse(equalizer.isGliding)
        assertFalse(equalizer.isEngaged, "a removed override leaves nothing for the right channel to correct")
    }

    /** An equalizer that has already processed audio, so a stale cached design would be reused. */
    private fun warmedUp(band: EqualizerBand, channels: Int = 1): PortableEqualizer =
        PortableEqualizer(rate, channels).also {
            it.setBands(listOf(band))
            it.process(DspTestSignals.sine(1_000.0, rate, frames = 512, channels = channels), 512)
        }

    private fun stereo(leftHz: Double, rightHz: Double, frames: Int): FloatArray {
        val left = DspTestSignals.sine(leftHz, rate, frames, channels = 1, amplitude = 0.1)
        val right = DspTestSignals.sine(rightHz, rate, frames, channels = 1, amplitude = 0.1)
        val samples = FloatArray(frames * 2)
        for (frame in 0 until frames) {
            samples[frame * 2] = left[frame]
            samples[frame * 2 + 1] = right[frame]
        }
        return samples
    }

    /** The right channel's RMS over the settled second half of a block, so a moving glide is read near one value. */
    private fun rightRms(samples: FloatArray, frames: Int): Double =
        DspTestSignals.rms(samples, channels = 2, fromFrame = frames / 2, toFrame = frames, channel = 1)

    /** The steady-state gain a sine at [frequencyHz] sees through [equalizer]. */
    private fun gainAt(equalizer: PortableEqualizer, frequencyHz: Double): Double {
        val frames = STEADY_STATE_FRAMES
        val samples = DspTestSignals.sine(frequencyHz, rate, frames, channels = 1, amplitude = 0.1)
        val input = DspTestSignals.rms(samples, channels = 1, fromFrame = frames / 2, toFrame = frames)
        equalizer.process(samples, frames)
        val output = DspTestSignals.rms(samples, channels = 1, fromFrame = frames / 2, toFrame = frames)
        return output / input
    }

    private companion object {
        const val STEADY_STATE_FRAMES = 48_000
        const val STEADY_STATE_TOLERANCE = 5e-3
    }
}
