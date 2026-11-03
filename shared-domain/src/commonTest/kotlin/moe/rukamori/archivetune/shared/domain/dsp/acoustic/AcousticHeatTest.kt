package moe.rukamori.archivetune.shared.domain.dsp.acoustic

import kotlin.math.log10
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

class AcousticHeatAxisTest {
    @Test
    fun frequencyTicksStayAtOrBelowSixWithNiceSteps() {
        assertEquals(2, AcousticHeatAxis.niceKHzStep(8f))
        assertEquals(5, AcousticHeatAxis.niceKHzStep(22.05f))
        assertEquals(10, AcousticHeatAxis.niceKHzStep(48f))
        assertEquals(1, AcousticHeatAxis.niceKHzStep(6f))
        assertEquals(50, AcousticHeatAxis.niceKHzStep(300f))
        assertEquals(100, AcousticHeatAxis.niceKHzStep(301f))
    }

    @Test
    fun timeTicksStayAtOrBelowSixWithNiceSteps() {
        assertEquals(5, AcousticHeatAxis.niceSecondsStep(20.0))
        assertEquals(10, AcousticHeatAxis.niceSecondsStep(31.0))
        assertEquals(30, AcousticHeatAxis.niceSecondsStep(180.0))
        assertEquals(600, AcousticHeatAxis.niceSecondsStep(3_600.0))
        assertEquals(1_200, AcousticHeatAxis.niceSecondsStep(7_200.0))
    }

    @Test
    fun clockRoundsHalfAwayFromZeroAndDoesNotWrapMinutes() {
        assertEquals("0:00", AcousticHeatAxis.formatClock(0.0))
        assertEquals("1:00", AcousticHeatAxis.formatClock(59.5))
        assertEquals("59:59", AcousticHeatAxis.formatClock(3_599.4))
        assertEquals("60:00", AcousticHeatAxis.formatClock(3_600.0))
    }
}

class AcousticHeatPaletteTest {
    @Test
    fun intensityIsLinearInDbBetweenFloorAndCeilingAndClamps() {
        assertEquals(0f, AcousticHeatPalette.intensity(-120f), 0f)
        assertEquals(0.5f, AcousticHeatPalette.intensity(-60f), 1e-6f)
        assertEquals(1f, AcousticHeatPalette.intensity(0f), 0f)
        assertEquals(0f, AcousticHeatPalette.intensity(-200f), 0f)
        assertEquals(1f, AcousticHeatPalette.intensity(10f), 0f)
        assertEquals(0f, AcousticHeatPalette.intensity(Float.NaN), 0f)
    }

    @Test
    fun ffmpegIntensityAnchorsGiveTheReferenceColours() {
        val anchors = listOf(
            0.00 to 0x000000,
            0.13 to 0x000050,
            0.30 to 0x6E0180,
            0.60 to 0xF00000,
            0.73 to 0xFF9E00,
            0.78 to 0xFFCA00,
            0.91 to 0xFFFF96,
            1.00 to 0xFFFFFF,
        )
        for ((intensity, rgb) in anchors) {
            assertEquals("intensity $intensity", (0xFF shl 24) or rgb, AcousticHeatPalette.argb(intensity))
        }
    }

    @Test
    fun interiorDbValuesMatchTheReferenceRamp() {
        assertEquals((0xFF shl 24) or 0x000132, AcousticHeatPalette.argb(10.0 / 120.0)) // -110 dB
        assertEquals((0xFF shl 24) or 0xC50129, AcousticHeatPalette.argb(60.0 / 120.0)) // -60 dB
        assertEquals((0xFF shl 24) or 0xFEE13D, AcousticHeatPalette.argb(100.0 / 120.0)) // -20 dB
    }

    @Test
    fun legendRunsFromBlackToWhite() {
        val stops = AcousticHeatPalette.legendColors(20)
        assertEquals(20, stops.size)
        assertEquals(0xFF shl 24, stops.first())
        assertEquals(-1, stops.last())
    }
}

class AcousticHeatRasterTest {
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

    private fun brightness(argb: Int): Int =
        ((argb shr 16) and 0xFF) + ((argb shr 8) and 0xFF) + (argb and 0xFF)

    @Test
    fun silenceRendersEveryPixelBlack() = runTest {
        val pixels = AcousticHeatRaster.render(grid(4, 4, FloatArray(16) { AcousticHeatScale.FLOOR_DB }), 8, 4)
        assertEquals(32, pixels.size)
        assertTrue(pixels.all { it == (0xFF shl 24) })
    }

    @Test
    fun averagingIsDoneInLinearPowerNotInDecibels() = runTest {
        // Two source cells averaged into one pixel: 0 dB and the -120 dB floor.
        // The linear-power mean is 10*log10((1 + 1e-12)/2) ~= -3.0103 dB, mapped to its palette stop;
        // a dB-domain mean would be -60 dB (much darker) and a max reducer would be 0 dB (white).
        val expectedDb = (10.0 * log10((1 + 1e-12) / 2)).toFloat()
        val pixel = AcousticHeatRaster.render(grid(2, 1, floatArrayOf(0f, AcousticHeatScale.FLOOR_DB)), 1, 1).single()
        assertEquals(AcousticHeatPalette.argbForDb(expectedDb), pixel)
        assertNotEquals(AcousticHeatPalette.argbForDb(-60f), pixel)
        assertNotEquals(AcousticHeatPalette.argbForDb(0f), pixel)
    }

    @Test
    fun zeroHertzIsAtTheBottomOfThePicture() = runTest {
        // Source row 0 is 0 Hz and loud; source row 1 is the quiet top of the spectrum.
        val pixels = AcousticHeatRaster.render(grid(1, 2, floatArrayOf(0f, AcousticHeatScale.FLOOR_DB)), 1, 2)
        assertTrue(brightness(pixels[1]) > brightness(pixels[0]))
    }

    @Test
    fun nonSquareUpscaleKeepsAUniformSource() = runTest {
        // 2 x 2 at 0 dB, up-sampled to non-square 3 x 4: every output pixel averages the same 0 dB.
        val pixels = AcousticHeatRaster.render(grid(2, 2, FloatArray(4) { 0f }), 3, 4)
        assertEquals(12, pixels.size)
        assertTrue(pixels.all { it == AcousticHeatPalette.argbForDb(0f) })
    }

    @Test
    fun mismatchedGridRendersNothing() = runTest {
        assertEquals(0, AcousticHeatRaster.render(grid(2, 2, FloatArray(3)), 2, 2).size)
    }

    @Test
    fun cancelledScopeStopsRendering() = runTest {
        // A dismissed or re-laid-out graph cancels the producing coroutine; the raster checks per row.
        val parent = Job().apply { cancel() }
        var finished = false
        launch(Dispatchers.Unconfined + parent, start = CoroutineStart.UNDISPATCHED) {
            try {
                AcousticHeatRaster.render(grid(1, 4, FloatArray(4)), 1, 4)
                finished = true
            } catch (_: CancellationException) {
            }
        }
        assertFalse(finished)
    }
}
