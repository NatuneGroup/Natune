package moe.rukamori.archivetune.playback.dsp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import moe.rukamori.archivetune.shared.domain.dsp.DspSettings
import moe.rukamori.archivetune.shared.domain.dsp.bitperfect.SoftwareStagePlan
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedDspAudioProcessorTest {
    private fun processor(bypassGate: () -> Boolean = { false }) =
        UnifiedDspAudioProcessor(bypassGate = bypassGate)

    private fun plan(
        bypass: Boolean = false,
        tempoPitch: Boolean = false,
    ) = SoftwareStagePlan(
        bypass = bypass,
        normalization = !bypass,
        equalizer = !bypass,
        tempoPitch = tempoPitch,
        resampling = false,
        twoDeckMixing = false,
        silenceSkipping = false,
        softwareVolume = false,
    )

    private fun floatBuffer(samples: FloatArray): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(samples.size * FLOAT_BYTES).order(ByteOrder.nativeOrder())
        buffer.asFloatBuffer().put(samples)
        buffer.position(0)
        buffer.limit(samples.size * FLOAT_BYTES)
        return buffer
    }

    private fun shortBuffer(samples: ShortArray): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(samples.size * SHORT_BYTES).order(ByteOrder.nativeOrder())
        buffer.asShortBuffer().put(samples)
        buffer.position(0)
        buffer.limit(samples.size * SHORT_BYTES)
        return buffer
    }

    private fun readFloats(buffer: ByteBuffer): FloatArray {
        val copy = buffer.duplicate().order(ByteOrder.nativeOrder())
        val values = FloatArray(copy.remaining() / FLOAT_BYTES)
        copy.asFloatBuffer().get(values)
        return values
    }

    private fun format(encoding: Int) = AudioProcessor.AudioFormat(SAMPLE_RATE, CHANNELS, encoding)

    @Test
    fun `a neutral plan hands float samples back bit for bit`() {
        val processor = processor()
        processor.submit(DspSettings(), plan())
        assertEquals(format(C.ENCODING_PCM_FLOAT), processor.configure(format(C.ENCODING_PCM_FLOAT)))

        val input = floatArrayOf(0.25f, -0.5f, 1e-7f, 0.999f, -1f, 0f)
        processor.queueInput(floatBuffer(input))

        assertArrayEquals(input, readFloats(processor.getOutput()), 0f)
    }

    @Test
    fun `16-bit input is read as float at the same level`() {
        val processor = processor()
        processor.submit(DspSettings(), plan())
        val output = processor.configure(format(C.ENCODING_PCM_16BIT))

        assertEquals(C.ENCODING_PCM_FLOAT, output.encoding)
        assertEquals(SAMPLE_RATE, output.sampleRate)
        assertEquals(CHANNELS, output.channelCount)

        val input = shortArrayOf(0, 8192, -16384, 32767)
        processor.queueInput(shortBuffer(input))
        val floats = readFloats(processor.getOutput())
        assertEquals(input.size, floats.size)
        input.forEachIndexed { index, sample -> assertEquals(sample / SHORT_SCALE, floats[index], 0f) }
    }

    @Test
    fun `a bypassed route leaves the processor inactive`() {
        val processor = processor()
        processor.submit(DspSettings(preampDb = -6.0), plan(bypass = true))

        assertEquals(AudioProcessor.AudioFormat.NOT_SET, processor.configure(format(C.ENCODING_PCM_FLOAT)))
        assertFalse(processor.isEngaged)
    }

    @Test
    fun `the bit-perfect gate hands samples through byte for byte`() {
        val processor = processor(bypassGate = { true })
        processor.submit(DspSettings(preampDb = -6.0), plan())
        processor.configure(format(C.ENCODING_PCM_FLOAT))

        val input = floatArrayOf(0.5f, -0.25f, 0.125f, -0.0625f)
        processor.queueInput(floatBuffer(input))

        // The plan allows the chain and the settings ask for a 6 dB cut; only the gate keeps it exact.
        assertArrayEquals(input, readFloats(processor.getOutput()), 0f)
        assertFalse(processor.isEngaged)
    }

    @Test
    fun `end of stream releases the frames the stretch stage holds back`() {
        val processor = processor()
        processor.submit(DspSettings(speed = 2.0), plan(tempoPitch = true))
        processor.configure(format(C.ENCODING_PCM_FLOAT))

        val frames = 4_096
        val input = FloatArray(frames * CHANNELS) { index -> ((index % 64) - 32) / 64f }
        processor.queueInput(floatBuffer(input))
        val first = readFloats(processor.getOutput()).size

        processor.queueEndOfStream()
        val tail = readFloats(processor.getOutput())

        assertTrue("the stretch stage held frames back", first < frames * CHANNELS * 2)
        assertTrue("end of stream drains the tail", tail.isNotEmpty())
    }

    @Test
    fun `flush clears the stage history before the next block`() {
        val processor = processor()
        processor.submit(DspSettings(speed = 2.0), plan(tempoPitch = true))
        processor.configure(format(C.ENCODING_PCM_FLOAT))
        processor.queueInput(floatBuffer(FloatArray(1_024 * CHANNELS) { 0.5f }))
        readFloats(processor.getOutput())

        processor.flush()
        processor.submit(DspSettings(), plan())
        val input = floatArrayOf(0.125f, -0.125f, 0.25f, -0.25f)
        processor.queueInput(floatBuffer(input))

        assertArrayEquals(input, readFloats(processor.getOutput()), 0f)

        processor.reset()
        assertFalse(processor.isEngaged)
    }

    private companion object {
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 2
        const val FLOAT_BYTES = 4
        const val SHORT_BYTES = 2
        const val SHORT_SCALE = 32_768f
    }
}
