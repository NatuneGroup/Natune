package moe.rukamori.archivetune.playback.dsp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SilenceSkippingAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import moe.rukamori.archivetune.shared.domain.dsp.DspFormat
import moe.rukamori.archivetune.shared.domain.dsp.DspGraph
import moe.rukamori.archivetune.shared.domain.dsp.DspSettings
import moe.rukamori.archivetune.shared.domain.dsp.bitperfect.SoftwareStagePlan
import moe.rukamori.archivetune.shared.domain.dsp.eq.EqualizerBand
import moe.rukamori.archivetune.shared.domain.dsp.eq.EqualizerBandType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
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

    private fun readShorts(buffer: ByteBuffer): ShortArray {
        val copy = buffer.duplicate().order(ByteOrder.nativeOrder())
        val values = ShortArray(copy.remaining() / SHORT_BYTES)
        copy.asShortBuffer().get(values)
        return values
    }

    private fun format(encoding: Int) = AudioProcessor.AudioFormat(SAMPLE_RATE, CHANNELS, encoding)

    /** A float sample as this adapter writes it: the full-scale grid the shared TPDF dither uses. */
    private fun pcm16(value: Float): Short =
        kotlin.math.round(value.coerceIn(-1f, 1f) * SHORT_FULL_SCALE).toInt().toShort()

    @Test
    fun `the chain always publishes 16-bit PCM`() {
        val processor = processor()
        processor.submit(DspSettings(), plan())

        assertEquals(C.ENCODING_PCM_16BIT, processor.configure(format(C.ENCODING_PCM_16BIT)).encoding)
        assertEquals(C.ENCODING_PCM_16BIT, processor.configure(format(C.ENCODING_PCM_FLOAT)).encoding)
    }

    @Test
    fun `a neutral plan renders the input at the same level`() {
        val processor = processor()
        processor.submit(DspSettings(), plan())
        processor.configure(format(C.ENCODING_PCM_16BIT))

        // Levels whose 16-bit round trip is exact on the full-scale grid the adapter quantizes to.
        val input = shortArrayOf(0, 8192, -8192, 16384, 16_000, -16_000)
        processor.queueInput(shortBuffer(input))

        assertArrayEquals(input, readShorts(processor.getOutput()))
    }

    @Test
    fun `float input is reduced onto the 16-bit grid`() {
        val processor = processor()
        processor.submit(DspSettings(), plan())
        processor.configure(format(C.ENCODING_PCM_FLOAT))

        val input = floatArrayOf(0.25f, -0.25f, 0.5f, -0.5f)
        processor.queueInput(floatBuffer(input))

        assertArrayEquals(input.map(::pcm16).toShortArray(), readShorts(processor.getOutput()))
    }

    @Test
    fun `a bypassed route leaves the processor inactive`() {
        val processor = processor()
        processor.submit(DspSettings(preampDb = -6.0), plan(bypass = true))

        assertEquals(AudioProcessor.AudioFormat.NOT_SET, processor.configure(format(C.ENCODING_PCM_FLOAT)))
        assertFalse(processor.isEngaged)
    }

    @Test
    fun `the bit-perfect gate keeps the chain engaged and transparent`() {
        val processor = processor(bypassGate = { true })
        processor.submit(DspSettings(preampDb = -6.0), plan())
        processor.configure(format(C.ENCODING_PCM_FLOAT))

        val input = floatArrayOf(0.5f, -0.25f, 0.125f, -0.0625f)
        processor.queueInput(floatBuffer(input))

        // The plan allows the chain and the settings ask for a 6 dB cut; only the gate keeps it at level.
        assertArrayEquals(input.map(::pcm16).toShortArray(), readShorts(processor.getOutput()))
        assertFalse(processor.isEngaged)
    }

    @Test
    fun `end of stream releases the frames the stretch stage holds back`() {
        val processor = processor()
        processor.submit(DspSettings(speed = 2.0), plan(tempoPitch = true))
        processor.configure(format(C.ENCODING_PCM_16BIT))

        val frames = 4_096
        val input = ShortArray(frames * CHANNELS) { index -> (((index % 64) - 32) * 256).toShort() }
        processor.queueInput(shortBuffer(input))
        val first = readShorts(processor.getOutput()).size

        processor.queueEndOfStream()
        val tail = readShorts(processor.getOutput())

        assertTrue("the stretch stage held frames back", first < frames * CHANNELS * 2)
        assertTrue("end of stream drains the tail", tail.isNotEmpty())
    }

    @Test
    fun `flush clears the stage history before the next block`() {
        val processor = processor()
        processor.submit(DspSettings(speed = 2.0), plan(tempoPitch = true))
        processor.configure(format(C.ENCODING_PCM_16BIT))
        processor.queueInput(shortBuffer(ShortArray(1_024 * CHANNELS) { 16_384 }))
        readShorts(processor.getOutput())

        processor.flush()
        processor.submit(DspSettings(), plan())
        val input = shortArrayOf(1024, -1024, 2048, -2048)
        processor.queueInput(shortBuffer(input))

        assertArrayEquals(input, readShorts(processor.getOutput()))

        processor.reset()
        assertFalse(processor.isEngaged)
    }

    /**
     * The order `MusicService.buildStandardAudioSink` installs: `DefaultAudioSink` appends Media3's silence
     * skipper after the supplied processors, and the transition filter is the last supplied one. Both reject
     * anything but 16-bit PCM, and the skipper rejects it even while disabled — the sink configures every
     * processor in the chain.
     */
    @Test
    fun `the standard chain configures with silence skipping off`() = assertStandardChainConfigures(skipSilence = false)

    @Test
    fun `the standard chain configures with silence skipping on`() = assertStandardChainConfigures(skipSilence = true)

    private fun assertStandardChainConfigures(skipSilence: Boolean) {
        val dsp = processor()
        dsp.submit(
            DspSettings(
                preampDb = -3.0,
                equalizerEnabled = true,
                equalizerBands = listOf(
                    EqualizerBand(
                        type = EqualizerBandType.PEAKING,
                        frequencyHz = 1_000.0,
                        gainDb = 6.0,
                        q = 1.0,
                    ),
                ),
            ),
            plan(),
        )
        val transitionFilter = TransitionFilterProcessor()
        val silenceSkipper = SilenceSkippingAudioProcessor().apply { setEnabled(skipSilence) }

        var output = AudioProcessor.AudioFormat(SAMPLE_RATE, CHANNELS, C.ENCODING_PCM_16BIT)
        for (audioProcessor in listOf(dsp, transitionFilter, silenceSkipper)) {
            output = audioProcessor.configure(output)
        }

        assertEquals(C.ENCODING_PCM_16BIT, output.encoding)
        assertEquals(SAMPLE_RATE, output.sampleRate)
        assertEquals(CHANNELS, output.channelCount)
    }

    /**
     * Media3 configures the next stream while the previous one is still draining and commits the format at
     * flush. The queued tail of the old stream must keep the encoding and stride it was configured with.
     */
    @Test
    fun `a block queued for the draining stream keeps its encoding`() {
        val processor = processor()
        processor.submit(DspSettings(), plan())
        processor.configure(format(C.ENCODING_PCM_16BIT))
        val first = shortArrayOf(8192, -8192, 4096, -4096)
        processor.queueInput(shortBuffer(first))
        assertArrayEquals(first, readShorts(processor.getOutput()))

        processor.configure(AudioProcessor.AudioFormat(SECOND_SAMPLE_RATE, 1, C.ENCODING_PCM_FLOAT))

        val tail = shortArrayOf(2048, -2048)
        processor.queueInput(shortBuffer(tail))
        assertArrayEquals(tail, readShorts(processor.getOutput()))

        processor.flush()
        val floats = floatArrayOf(0.25f, -0.25f, 0.5f, -0.5f)
        processor.queueInput(floatBuffer(floats))
        assertArrayEquals(floats.map(::pcm16).toShortArray(), readShorts(processor.getOutput()))
    }

    @Test
    fun `a configure for the next stream keeps the old stream's held frames`() {
        val processor = processor()
        processor.submit(DspSettings(speed = 2.0), plan(tempoPitch = true))
        processor.configure(format(C.ENCODING_PCM_16BIT))
        processor.queueInput(shortBuffer(ShortArray(4_096 * CHANNELS) { index -> (((index % 64) - 32) * 256).toShort() }))
        readShorts(processor.getOutput())

        processor.configure(AudioProcessor.AudioFormat(SECOND_SAMPLE_RATE, CHANNELS, C.ENCODING_PCM_16BIT))
        processor.queueEndOfStream()

        assertTrue("the draining stream's stretch tail survives the next configure", readShorts(processor.getOutput()).isNotEmpty())
    }

    @Test
    fun `flush adopts the staged format for the next stream`() {
        val formats = mutableListOf<DspFormat>()
        val processor =
            UnifiedDspAudioProcessor(
                newGraph = { format ->
                    formats += format
                    DspGraph(format.sampleRateHz, format.channelCount)
                },
                bypassGate = { false },
            )
        processor.submit(DspSettings(), plan())

        processor.configure(format(C.ENCODING_PCM_16BIT))
        assertEquals(listOf(DspFormat(SAMPLE_RATE, CHANNELS)), formats)

        processor.configure(AudioProcessor.AudioFormat(SECOND_SAMPLE_RATE, 1, C.ENCODING_PCM_16BIT))
        assertEquals("the staged format must not touch the running graph", listOf(DspFormat(SAMPLE_RATE, CHANNELS)), formats)

        processor.flush()
        assertEquals(
            listOf(DspFormat(SAMPLE_RATE, CHANNELS), DspFormat(SECOND_SAMPLE_RATE, 1)),
            formats,
        )
    }

    /**
     * Steady-state rendering runs on the playback thread, so a block must not allocate. The JVM's own
     * per-thread allocation counter is read reflectively (the API lives outside the standard
     * `java.lang.management` view) and the test is skipped where the JVM does not account for it.
     */
    @Test
    fun `a warmed steady-state block allocates nothing`() {
        val processor = processor()
        processor.submit(
            DspSettings(
                preampDb = -3.0,
                equalizerEnabled = true,
                equalizerBands = listOf(EqualizerBand(EqualizerBandType.PEAKING, 1_000.0, 4.0, 1.0)),
            ),
            plan(),
        )
        processor.configure(format(C.ENCODING_PCM_16BIT))

        val block = ByteBuffer.allocateDirect(BLOCK_SAMPLES * SHORT_BYTES).order(ByteOrder.nativeOrder())
        block.asShortBuffer().put(ShortArray(BLOCK_SAMPLES) { index -> (((index % 128) - 64) * 128).toShort() })
        block.position(0)
        block.limit(BLOCK_SAMPLES * SHORT_BYTES)

        repeat(WARMUP_BLOCKS) { render(processor, block) }

        val before = allocatedBytes()
        Assume.assumeTrue("this JVM does not account per-thread allocation", before != null)
        repeat(MEASURED_BLOCKS) { render(processor, block) }
        val after = allocatedBytes()
        Assume.assumeTrue("this JVM does not account per-thread allocation", after != null)

        val allocated = after!! - before!!
        assertTrue(
            "steady-state rendering allocated $allocated bytes over $MEASURED_BLOCKS blocks",
            allocated < ALLOCATION_LIMIT_BYTES,
        )
    }

    private fun render(processor: UnifiedDspAudioProcessor, block: ByteBuffer) {
        block.position(0)
        processor.queueInput(block)
        processor.getOutput()
    }

    private fun allocatedBytes(): Long? =
        runCatching {
            val bean = java.lang.management.ManagementFactory.getThreadMXBean()
            val method = bean.javaClass.getMethod("getThreadAllocatedBytes", java.lang.Long.TYPE)
            method.invoke(bean, Thread.currentThread().id) as? Long
        }.getOrNull()

    private companion object {
        const val SAMPLE_RATE = 48_000
        const val SECOND_SAMPLE_RATE = 44_100
        const val CHANNELS = 2
        const val FLOAT_BYTES = 4
        const val SHORT_BYTES = 2
        const val SHORT_FULL_SCALE = 32_767f
        const val BLOCK_SAMPLES = 2_048
        const val WARMUP_BLOCKS = 64
        const val MEASURED_BLOCKS = 512
        const val ALLOCATION_LIMIT_BYTES = 32L * 1_024L
    }
}
