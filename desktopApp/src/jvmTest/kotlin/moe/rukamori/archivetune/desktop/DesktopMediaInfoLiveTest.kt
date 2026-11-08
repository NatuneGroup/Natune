package moe.rukamori.archivetune.desktop

import moe.rukamori.archivetune.desktop.playback.DesktopOutputState
import moe.rukamori.archivetune.desktop.playback.DesktopPcmFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The media-info DAC card reads the native sink, not the Android mixer. A direct lane reports the decoded
 * source and the wider output, while a refused exclusive request must not claim a bit-perfect signal path.
 */
class DesktopMediaInfoLiveTest {
    @Test
    fun anActiveDirectLaneReportsSourceAndWidenedOutput() {
        val state = DesktopOutputState(
            requested = true,
            exclusiveActive = true,
            directPcm = true,
            inputRateHz = 96_000,
            inputBits = 24,
            inputChannels = 2,
            inputFormat = DesktopPcmFormat.S24,
            outputRateHz = 96_000,
            outputBits = 32,
            outputChannels = 2,
            outputFormat = DesktopPcmFormat.S32,
            deviceName = "USB DAC",
            backend = "ALSA",
        )
        val live = desktopMediaInfoLive(technical = null, output = state)
        val pipeline = assertNotNull(live.pipeline)
        assertEquals("USB DAC", pipeline.routedName)
        assertEquals("ALSA", pipeline.engineName)
        assertTrue(pipeline.hostDesktop)
        assertTrue(pipeline.exclusiveDirect)
        assertEquals(96_000, pipeline.halSampleRateHz)
        assertEquals(24, pipeline.decodedBits)
        assertEquals(2, pipeline.chainChannels)
        val bitPerfect = assertNotNull(live.bitPerfect)
        assertEquals(96_000, bitPerfect.sourceSampleRateHz)
        assertEquals(24, bitPerfect.sourceBitDepth)
        assertEquals(32, bitPerfect.outputBitDepth)
        assertTrue(bitPerfect.mixerBitPerfectActive)
    }

    @Test
    fun aRefusedExclusiveRequestKeepsSharedOutputFacts() {
        val state = DesktopOutputState(
            requested = true,
            directPcm = false,
            inputRateHz = 44_100,
            inputBits = 16,
            inputChannels = 2,
            inputFormat = DesktopPcmFormat.S16,
            outputRateHz = 48_000,
            outputBits = 16,
            outputChannels = 2,
            outputFormat = DesktopPcmFormat.S16,
            deviceName = "Speakers",
            backend = "WASAPI",
        )
        val live = desktopMediaInfoLive(technical = null, output = state)
        val pipeline = assertNotNull(live.pipeline)
        assertEquals("Speakers", pipeline.routedName)
        assertFalse(pipeline.exclusiveDirect)
        assertEquals(48_000, pipeline.halSampleRateHz)
        assertNull(live.bitPerfect, "shared output must not publish a bit-perfect pipeline row")
    }
}
