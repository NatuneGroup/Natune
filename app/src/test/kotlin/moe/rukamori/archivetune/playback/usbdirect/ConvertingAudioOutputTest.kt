package moe.rukamori.archivetune.playback.usbdirect

import android.media.AudioDeviceInfo
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioOutput
import moe.rukamori.archivetune.playback.ConvertingAudioOutput
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class ConvertingAudioOutputTest {
    private class Output(private val take: Int) : AudioOutput {
        val bytes = mutableListOf<Byte>()
        override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean {
            repeat(minOf(take, buffer.remaining())) { bytes += buffer.get() }
            return !buffer.hasRemaining()
        }
        override fun play() = Unit
        override fun pause() = Unit
        override fun stop() = Unit
        override fun flush() { bytes.clear() }
        override fun release() = Unit
        override fun setVolume(volume: Float) = Unit
        override fun getAudioSessionId() = 0
        override fun getSampleRate() = 48000
        override fun getBufferSizeInFrames() = 1024L
        override fun getPositionUs() = 0L
        override fun isOffloadedPlayback() = false
        override fun isStalled() = false
        override fun getPlaybackParameters() = PlaybackParameters.DEFAULT
        override fun setPlaybackParameters(playbackParams: PlaybackParameters) = Unit
        override fun addListener(listener: AudioOutput.Listener) = Unit
        override fun removeListener(listener: AudioOutput.Listener) = Unit
        override fun setOffloadDelayPadding(delayInFrames: Int, paddingInFrames: Int) = Unit
        override fun setOffloadEndOfStream() = Unit
        override fun attachAuxEffect(effectId: Int) = Unit
        override fun setAuxEffectSendLevel(level: Float) = Unit
        override fun setPreferredDevice(preferredDevice: AudioDeviceInfo?) = Unit
    }
    private fun source(vararg samples: Int): ByteBuffer = ByteBuffer.allocate(samples.size * 2)
        .order(ByteOrder.LITTLE_ENDIAN).apply { samples.forEach { putShort(it.toShort()) }; flip() }

    @Test fun shortWritesAdvanceOnlyAcceptedInputFramesAndDoNotReplayPcm() {
        val inner = Output(8)
        val output = ConvertingAudioOutput(inner, C.ENCODING_PCM_16BIT, C.ENCODING_PCM_32BIT, 2)
        val input = source(-32768, 1, 32767, -1)
        assertFalse(output.write(input, 1, 0))
        assertEquals(4, input.position())
        assertTrue(output.write(input, 1, 0))
        assertEquals(8, input.position())
        val bytes = ByteBuffer.wrap(inner.bytes.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(Int.MIN_VALUE, bytes.int)
        assertEquals(1 shl 16, bytes.int)
        assertEquals(32767 shl 16, bytes.int)
        assertEquals(-1 shl 16, bytes.int)
    }
    @Test fun flushDiscardsConvertedTailBeforeTheNextTrack() {
        val inner = Output(4)
        val output = ConvertingAudioOutput(inner, C.ENCODING_PCM_16BIT, C.ENCODING_PCM_32BIT, 1)
        assertFalse(output.write(source(1, 2), 1, 0))
        output.flush()
        assertTrue(output.write(source(3), 1, 0))
        assertEquals(3 shl 16, ByteBuffer.wrap(inner.bytes.toByteArray()).order(ByteOrder.LITTLE_ENDIAN).int)
    }
    @Test fun explicitMuteZerosPendingSamplesWithoutChangingTheInput() {
        val inner = Output(4)
        var muted = false
        val output = ConvertingAudioOutput(inner, C.ENCODING_PCM_16BIT, C.ENCODING_PCM_32BIT, 1, muted = { muted })
        val input = source(1, 2)
        assertFalse(output.write(input, 1, 0))
        muted = true
        assertTrue(output.write(input, 1, 0))
        val result = ByteBuffer.wrap(inner.bytes.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1 shl 16, result.int)
        assertEquals(0, result.int)
        assertEquals(2, input.getShort(2).toInt())
    }
}
