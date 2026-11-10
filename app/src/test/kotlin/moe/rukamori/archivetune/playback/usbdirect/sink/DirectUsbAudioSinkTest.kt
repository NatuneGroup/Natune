package moe.rukamori.archivetune.playback.usbdirect

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioSink
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DirectUsbAudioSinkTest {
    private class Harness(transportDrain: Boolean = false) {
        var opens = 0
        var releases = 0
        var flushes = 0
        var plays = 0
        var normalConfigurations = 0
        var playedUs = 0L
        var failWrite = false
        var failPosition = false
        var failConfiguration = false
        var reportedError: Exception? = null
        var pendingFrames = true
        val format = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setSampleRate(48000).setChannelCount(2).setPcmEncoding(C.ENCODING_PCM_16BIT).build()
        val normal = Proxy.newProxyInstance(AudioSink::class.java.classLoader, arrayOf(AudioSink::class.java)) { _, method, args ->
            when (method.name) {
                "configure" -> {
                    normalConfigurations++
                    if (failConfiguration) throw AudioSink.ConfigurationException("unsupported fallback", args!![0] as Format)
                    null
                }
                "hasPendingData", "getSkipSilenceEnabled" -> false
                "isEnded", "supportsFormat" -> true
                "getFormatSupport" -> AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
                "getCurrentPositionUs" -> 0L
                "getPlaybackParameters" -> PlaybackParameters.DEFAULT
                "handleBuffer" -> { (args!![0] as ByteBuffer).position((args[0] as ByteBuffer).limit()); true }
                else -> null
            }
        } as AudioSink
        val output = Proxy.newProxyInstance(AudioOutput::class.java.classLoader, (if (transportDrain) arrayOf(AudioOutput::class.java, DirectUsbPendingOutput::class.java)
                else arrayOf(AudioOutput::class.java))) { _, method, args ->
            when (method.name) {
                "write" -> {
                    if (failWrite) throw AudioOutput.WriteException(-5, true)
                    (args!![0] as ByteBuffer).position((args[0] as ByteBuffer).limit()); true
                }
                "play" -> { plays++; null }
                "release" -> { releases++; null }
                "flush" -> { flushes++; playedUs = 0L; null }
                "hasPendingData" -> pendingFrames
                "getPositionUs" -> { if (failPosition) throw AudioOutput.WriteException(-5, true); playedUs }
                "getSampleRate" -> 48000
                "getBufferSizeInFrames" -> 1024L
                else -> null
            }
        } as AudioOutput
        val sink = DirectUsbAudioSink(normal, { true }, { _, _, _ -> opens++; output })
        init {
            val listener = Proxy.newProxyInstance(AudioSink.Listener::class.java.classLoader, arrayOf(AudioSink.Listener::class.java)) { _, method, args ->
                if (method.name == "onAudioSinkError") reportedError = args!![0] as Exception
                null
            } as AudioSink.Listener
            sink.setListener(listener)
        }
        fun write(timeUs: Long = 0L): Boolean = sink.handleBuffer(ByteBuffer.allocateDirect(4), timeUs, 1)
        fun open() { sink.configure(format, 0, null); assertTrue(write()) }
    }

    @Test fun seekFlushRetainsTheDeviceAndResetsItsTimeline() {
        val h = Harness()
        h.open()
        h.sink.play()
        h.sink.flush()
        assertEquals(1, h.flushes)
        assertEquals(2, h.plays)
        assertEquals(0, h.releases)
        assertTrue(h.write(5_000_000L))
        assertEquals(1, h.opens)
        assertEquals(5_000_000L, h.sink.getCurrentPositionUs(false))
        h.sink.reset()
        assertEquals(1, h.releases)
    }
    @Test fun samePcmFormatRetainsQueuedAudioAndTheDevice() {
        val h = Harness()
        h.open()
        h.sink.configure(h.format.buildUpon().setId("next-track").build(), 4096, intArrayOf(0, 1))
        assertTrue(h.write(20L))
        assertEquals(1, h.opens)
        assertEquals(0, h.releases)
        assertEquals(0, h.flushes)
        h.playedUs = 41L
        assertEquals(41L, h.sink.getCurrentPositionUs(false))
    }

    @Test fun aNewRateDrainsAndReopensTheDevice() {
        val h = Harness()
        h.open()
        h.sink.configure(h.format.buildUpon().setSampleRate(96000).build(), 0, null)
        assertFalse(h.write())
        h.playedUs = 1000L
        assertTrue(h.write())
        assertEquals(2, h.opens)
        assertEquals(1, h.releases)
    }

    @Test fun sameFormatWithTrimOrChannelRemappingUsesThePlatform() {
        for (trim in listOf(false, true)) {
            val h = Harness()
            h.open()
            h.playedUs = 1000L
            val next = if (trim) h.format.buildUpon().setEncoderPadding(1).build() else h.format
            h.sink.configure(next, 0, if (trim) null else intArrayOf(1, 0))
            assertTrue(h.write())
            assertEquals(1, h.opens)
            assertEquals(1, h.releases)
            assertEquals(1, h.normalConfigurations)
        }
    }

    @Test fun droppedPacketsDoNotKeepEndOfStreamPending() {
        val h = Harness(transportDrain = true)
        h.open()
        h.sink.playToEndOfStream()
        assertEquals(0, h.releases)
        h.pendingFrames = false
        h.sink.playToEndOfStream()
        assertEquals(0L, h.playedUs)
        assertTrue(h.sink.isEnded)
        assertEquals(1, h.releases)
    }

    @Test fun aFailedDirectWriteFallsBackAndRetriesTheSameBuffer() {
        val h = Harness()
        h.open()
        h.failWrite = true
        val buffer = ByteBuffer.allocateDirect(4)
        assertFalse(h.sink.handleBuffer(buffer, 20L, 1))
        assertEquals(0, buffer.position())
        assertEquals(1, h.releases)
        assertEquals(1, h.normalConfigurations)
        assertTrue(h.sink.handleBuffer(buffer, 20L, 1))
        assertEquals(4, buffer.position())
        assertEquals(1, h.opens)
    }

    @Test fun failedFallbackConfigurationIsReportedAsASinkWriteError() {
        val h = Harness()
        h.open()
        h.failWrite = true
        h.failConfiguration = true
        val error = runCatching { h.write() }.exceptionOrNull()
        assertTrue(error is AudioSink.WriteException)
        assertTrue(error!!.cause is AudioSink.ConfigurationException)
        assertSame(error, h.reportedError)
        assertFalse(h.sink.isEnded)
    }

    @Test fun pollingDefersFailedFallbackToTheNextWriteOrDrain() {
        for (pollPosition in listOf(false, true)) {
            val h = Harness()
            h.open()
            h.sink.playToEndOfStream()
            h.failPosition = true
            h.failConfiguration = true
            if (pollPosition) h.sink.getCurrentPositionUs(false) else assertFalse(h.sink.isEnded)
            assertTrue(h.reportedError is AudioSink.WriteException)
            assertTrue(runCatching { h.write() }.exceptionOrNull() is AudioSink.WriteException)
            assertTrue(runCatching { h.sink.playToEndOfStream() }.exceptionOrNull() is AudioSink.WriteException)
        }
    }

}
