package moe.rukamori.archivetune.acoustics

import moe.rukamori.archivetune.shared.domain.dsp.acoustic.AcousticAuditResult
import moe.rukamori.archivetune.shared.domain.dsp.acoustic.AcousticAssessment
import kotlin.math.log10
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AcousticSpectrumTest {
    private fun grid(
        width: Int,
        height: Int,
        heat: FloatArray,
    ) = AcousticAuditResult(
        mediaId = "synthetic",
        sourceLabel = "TEST",
        sampleRate = 44_100,
        channelCount = 2,
        startMs = 0L,
        durationMs = 20_000L,
        cutoffHz = null,
        nyquistHz = 22_050f,
        width = width,
        height = height,
        magnitudeDb = heat,
        assessment = AcousticAssessment.INCONCLUSIVE,
        limitations = emptyList(),
    )

    @Test
    fun mismatchedGridGivesAnEmptySpectrum() = runTest {
        val spectrum = AcousticSpectrum.of(grid(4, 4, FloatArray(8)))
        assertEquals(0, spectrum.bands.size)
        assertEquals(22_050f, spectrum.nyquistHz)
    }

    @Test
    fun averagingIsDoneInLinearPowerNotInDecibels() = runTest {
        // Every row identical [0 dB, -120 dB]: the linear-power mean is
        // 10*log10((1 + 1e-12)/2) ~= -3.0103 dB, not the -60 dB a dB-domain mean would give.
        // Uniform bands are unchanged by smoothing.
        val heat = FloatArray(8) { column -> if (column % 2 == 0) 0f else AcousticHeatPalette.FLOOR_DB }
        val bands = AcousticSpectrum.of(grid(2, 4, heat), bandCount = 4).bands
        val expectedDb = (10.0 * log10((1 + 1e-12) / 2)).toFloat()
        val expected = AcousticHeatPalette.intensity(expectedDb)
        assertTrue(bands.all { it == expected })
    }

    @Test
    fun aShelfStaysVisibleAfterSmoothing() = runTest {
        // Bottom half of the rows loud, top half at the floor: a 50% cutoff shelf.
        val width = 4
        val height = 100
        val heat = FloatArray(width * height) { index ->
            val row = index / width
            if (row < height / 2) 0f else AcousticHeatPalette.FLOOR_DB
        }
        val bands = AcousticSpectrum.of(grid(width, height, heat), bandCount = 20).bands
        assertTrue(bands[0] > 0.9f && bands[8] > 0.9f)
        assertTrue(bands[19] < 0.05f)
        // The drop happens at the 50% shelf, give or take the smoothing kernel.
        val dropIndex = bands.indices.firstOrNull { bands[it] < 0.5f } ?: -1
        assertTrue("drop index $dropIndex", dropIndex in 9..12)
    }

    @Test
    fun nonFiniteCellsAreSkipped() = runTest {
        val heat = floatArrayOf(0f, Float.NaN, 0f, Float.POSITIVE_INFINITY)
        val bands = AcousticSpectrum.of(grid(2, 2, heat), bandCount = 2).bands
        assertTrue(bands.all { it == AcousticHeatPalette.intensity(0f) })
    }
}
