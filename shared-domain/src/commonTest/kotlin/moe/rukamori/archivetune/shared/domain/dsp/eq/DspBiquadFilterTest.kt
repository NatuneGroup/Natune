/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.shared.domain.dsp.eq

import moe.rukamori.archivetune.shared.domain.dsp.DspTestSignals
import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DspBiquadFilterTest {
    private val rate = 48_000

    @Test
    fun lowPassKeepsBassAndAttenuatesTreble() {
        val coefficients = BiquadDesign.lowPass(rate, frequencyHz = 1_000.0)
        assertEquals(1.0, BiquadDesign.magnitude(coefficients, rate, 100.0), 0.01)
        assertTrue(BiquadDesign.magnitude(coefficients, rate, 10_000.0) < 0.02)
    }

    @Test
    fun highPassRemovesBassAndKeepsTreble() {
        val coefficients = BiquadDesign.highPass(rate, frequencyHz = 1_000.0)
        assertTrue(BiquadDesign.magnitude(coefficients, rate, 100.0) < 0.02)
        assertEquals(1.0, BiquadDesign.magnitude(coefficients, rate, 10_000.0), 0.01)
    }

    @Test
    fun peakingGainIsExactAtTheCentreFrequency() {
        val boost = BiquadDesign.peaking(rate, 1_000.0, gainDb = 6.0, q = 1.0)
        assertEquals(10.0.pow(6.0 / 20.0), BiquadDesign.magnitude(boost, rate, 1_000.0), 1e-9)

        val cut = BiquadDesign.peaking(rate, 1_000.0, gainDb = -6.0, q = 1.0)
        assertEquals(10.0.pow(-6.0 / 20.0), BiquadDesign.magnitude(cut, rate, 1_000.0), 1e-9)
    }

    @Test
    fun zeroGainPeakingIsTransparentAtEveryFrequency() {
        val flat = BiquadDesign.peaking(rate, 1_000.0, gainDb = 0.0, q = 1.0)
        for (frequency in listOf(50.0, 1_000.0, 9_000.0)) {
            assertEquals(1.0, BiquadDesign.magnitude(flat, rate, frequency), 1e-9, "magnitude at $frequency Hz")
        }
    }

    @Test
    fun shelvesReachTheirFullGainAtTheExtremes() {
        val low = BiquadDesign.lowShelf(rate, 200.0, gainDb = 6.0, q = BiquadDesign.BUTTERWORTH_Q)
        assertEquals(10.0.pow(6.0 / 20.0), BiquadDesign.magnitude(low, rate, 20.0), 0.01)
        assertEquals(1.0, BiquadDesign.magnitude(low, rate, 20_000.0), 0.02)

        val high = BiquadDesign.highShelf(rate, 4_000.0, gainDb = -6.0, q = BiquadDesign.BUTTERWORTH_Q)
        assertEquals(1.0, BiquadDesign.magnitude(high, rate, 20.0), 0.02)
        assertEquals(10.0.pow(-6.0 / 20.0), BiquadDesign.magnitude(high, rate, 20_000.0), 0.01)
    }

    @Test
    fun cutoffsAtOrAboveNyquistClampToTheLargestStableFrequency() {
        assertEquals(23_999.0, BiquadDesign.clampFrequency(rate, 1e9))
        assertEquals(BiquadDesign.MIN_FREQUENCY_HZ, BiquadDesign.clampFrequency(rate, 1.0))
        assertFailsWith<IllegalArgumentException> { BiquadDesign.clampFrequency(4_000, 1_000.0) }
        assertEquals(BiquadDesign.lowPass(8_000, 3_999.0), BiquadDesign.lowPass(8_000, 1e9))
    }

    @Test
    fun filterKeepsEachChannelsHistoryApart() {
        val coefficients = BiquadDesign.lowPass(rate, 500.0)
        val frames = 2_000
        val left = DspTestSignals.sine(1_000.0, rate, frames, channels = 1)
        val right = DspTestSignals.sine(7_000.0, rate, frames, channels = 1)
        val expectedLeft = filterMono(coefficients, left)
        val expectedRight = filterMono(coefficients, right)

        val interleaved = FloatArray(frames * 2)
        for (frame in 0 until frames) {
            interleaved[frame * 2] = left[frame]
            interleaved[frame * 2 + 1] = right[frame]
        }
        val stereo = BiquadFilter(channels = 2)
        stereo.setCoefficients(coefficients)
        stereo.process(interleaved, frames)

        for (frame in 0 until frames) {
            assertEquals(expectedLeft[frame], interleaved[frame * 2], "left frame $frame")
            assertEquals(expectedRight[frame], interleaved[frame * 2 + 1], "right frame $frame")
        }
    }

    @Test
    fun resetForgetsTheHistory() {
        val filter = BiquadFilter(channels = 1)
        filter.setCoefficients(BiquadDesign.lowPass(rate, 2_000.0))
        filter.process(DspTestSignals.sine(440.0, rate, frames = 512, channels = 1), 512)

        filter.reset()
        val silence = FloatArray(512)
        filter.process(silence, 512)
        DspTestSignals.assertAllZero(silence)
    }

    @Test
    fun designedMagnitudeMatchesTheToneMeasuredThroughTheFilter() {
        // The analysis helper and the time-domain filter must agree, or the magnitude checks above prove nothing.
        val coefficients = BiquadDesign.highPass(rate, 2_000.0)
        val frames = 24_000
        val tone = DspTestSignals.sine(8_000.0, rate, frames, channels = 1, amplitude = 0.2)
        val inputRms = DspTestSignals.rms(tone, channels = 1, fromFrame = frames / 2, toFrame = frames)
        val outputRms = DspTestSignals.rms(filterMono(coefficients, tone), channels = 1, fromFrame = frames / 2, toFrame = frames)
        val expected = BiquadDesign.magnitude(coefficients, rate, 8_000.0)
        assertTrue(abs(outputRms / inputRms - expected) < 1e-3, "measured ${outputRms / inputRms}, designed $expected")
    }

    private fun filterMono(coefficients: BiquadCoefficients, input: FloatArray): FloatArray {
        val samples = input.copyOf()
        val filter = BiquadFilter(channels = 1)
        filter.setCoefficients(coefficients)
        filter.process(samples, samples.size)
        return samples
    }
}
