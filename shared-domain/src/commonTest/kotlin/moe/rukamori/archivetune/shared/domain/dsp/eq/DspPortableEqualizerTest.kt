/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

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
}
