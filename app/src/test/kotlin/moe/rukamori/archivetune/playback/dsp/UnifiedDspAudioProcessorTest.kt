package moe.rukamori.archivetune.playback.dsp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import moe.rukamori.archivetune.playback.TransitionFilterProcessor
import moe.rukamori.archivetune.shared.domain.dsp.DspDither
import moe.rukamori.archivetune.shared.domain.dsp.DspFormat
import moe.rukamori.archivetune.shared.domain.dsp.DspGraph
import moe.rukamori.archivetune.shared.domain.dsp.DspSettings
import moe.rukamori.archivetune.shared.domain.dsp.TpdfDither
import moe.rukamori.archivetune.shared.domain.dsp.bitperfect.SoftwareStagePlan
import moe.rukamori.archivetune.shared.domain.dsp.eq.EqualizerBand
import moe.rukamori.archivetune.shared.domain.dsp.eq.EqualizerBandType
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

    private fun readShorts(buffer: ByteBuffer): ShortArray {
        val copy = buffer.duplicate().order(ByteOrder.nativeOrder())
        val values = ShortArray(copy.remaining() / SHORT_BYTES)
        copy.asShortBuffer().get(values)
        return values
    }

    private fun format(encoding: Int) = AudioProcessor.AudioFormat(SAMPLE_RATE, CHANNELS, encoding)

    /** A float sample as this adapter writes it: the signed 16-bit grid, saturated at the end points. */
    private fun pcm16(value: Float): Short =
        kotlin.math.round(value.coerceIn(-1f, 1f) * SHORT_SCALE).toInt().coerceIn(SHORT_MIN, SHORT_MAX).toShort()

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

        // Both end points: a 32767-and-32768 mismatch would turn 32767 into 32766 and -32768 into -32767.
        val input = shortArrayOf(Short.MIN_VALUE, Short.MAX_VALUE, 0, 0, 8192, -8192, 16_000, -16_000)
        processor.queueInput(shortBuffer(input))

        assertArrayEquals(input, readShorts(processor.getOutput()))
    }

    /** Every signed 16-bit value, so neutrality covers the whole grid rather than the values a scale hides. */
    @Test
    fun `a neutral plan keeps every PCM16 value bit-exact`() {
        val processor = processor()
        processor.submit(DspSettings(), plan())
        processor.configure(format(C.ENCODING_PCM_16BIT))

        val values = ShortArray(PCM16_VALUES) { (it + Short.MIN_VALUE).toShort() }
        var start = 0
        while (start < values.size) {
            val count = minOf(1_024, values.size - start)
            val block = ShortArray(count * CHANNELS) { values[start + it / CHANNELS] }
            processor.queueInput(shortBuffer(block))
            assertArrayEquals("values from $start", block, readShorts(processor.getOutput()))
            start += count
        }
    }

    /**
     * The output quantizer runs the dither and then rounds onto the same 16-bit grid, so a dithered sample is
     * already an integer number of steps and the conversion does not round it a second time.
     */
    @Test
    fun `dither lands on the grid the output converter rounds on`() {
        val dither = TpdfDither(OUTPUT_BIT_DEPTH, seed = 12_345)
        val samples = floatArrayOf(0.5f, -0.5f, 1f, -1f, 0.001f, -0.001f)
        dither.process(samples, samples.size / CHANNELS, CHANNELS)

        for (index in samples.indices) {
            val steps = samples[index] * SHORT_SCALE
            assertTrue("sample $index sits off the 16-bit grid", steps == kotlin.math.round(steps))
            // Positive full scale saturates at 32767; every other dithered sample is exactly its grid step.
            if (samples[index] < 1f) {
                assertEquals(
                    "sample $index",
                    samples[index].toDouble(),
                    pcm16(samples[index]).toDouble() / SHORT_SCALE.toDouble(),
                    0.0,
                )
            }
        }
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
            val configured = audioProcessor.configure(output)
            // AudioProcessingPipeline advances the format only for an active processor: a disabled silence
            // skipper declines with NOT_SET and the chain routes around it instead of keeping that value.
            val active = audioProcessor.isActive
            if (!active) assertEquals("an inactive processor reports NOT_SET", AudioProcessor.AudioFormat.NOT_SET, configured)
            if (active) output = configured
        }

        // The chain's own stage stays active on 16-bit PCM, which is what the sink's later processors need.
        assertTrue(dsp.isActive)
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
     * per-thread allocation counter is read through the exported `com.sun.management.ThreadMXBean`; the test
     * fails where that accounting is missing or disabled instead of passing without measuring.
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

        val block = block()

        repeat(WARMUP_BLOCKS) { render(processor, block) }

        val before = allocatedBytes()
        repeat(MEASURED_BLOCKS) { render(processor, block) }
        val allocated = allocatedBytes() - before

        assertTrue(
            "steady-state rendering allocated $allocated bytes over $MEASURED_BLOCKS blocks",
            allocated < ALLOCATION_LIMIT_BYTES,
        )
    }

    /**
     * A settings move reaches the processor on every preference emission, and `queueInput` applies it at a block
     * boundary. The settings value, the neutral value the bit-perfect gate renders and the dither stage are
     * prepared by `submit` on the caller's thread, and the equalizer's stage pool is built with the graph, so the
     * adopting block allocates nothing — including an equalizer switch or a band-count change.
     */
    @Test
    fun `a request change allocates nothing on the render path`() {
        val processor = processor()
        processor.submit(request(1), plan())
        processor.configure(format(C.ENCODING_PCM_16BIT))
        val block = block()
        repeat(WARMUP_BLOCKS) { render(processor, block) }

        // Built before the measurement: only the adopting block is measured, not the request's own objects.
        val requests = List(REQUEST_CHANGES) { index ->
            val settings = request(if (index % 2 == 0) MAX_BANDS else 1)
            if (index == REQUEST_CHANGES / 2) {
                settings.copy(dither = DspDither(enabled = true))
            } else {
                settings
            }
        }
        var allocated = 0L
        val perBlock = IntArray(requests.size)
        for ((index, request) in requests.withIndex()) {
            processor.submit(request, plan())
            val before = allocatedBytes()
            render(processor, block)
            perBlock[index] = (allocatedBytes() - before).toInt()
            allocated += perBlock[index]
        }

        if (allocated >= ALLOCATION_LIMIT_BYTES) {
            // Only a failing block pays for this message; the measured loop stays allocation-tight.
            org.junit.Assert.fail(
                "a request change allocated $allocated bytes across ${requests.size} blocks: " +
                    perBlock.joinToString(","),
            )
        }
    }

    /** One boosted band and [count]-1 flat ones, so a band-count change designs one stage, not [count]. */
    private fun request(count: Int): DspSettings = DspSettings(
        preampDb = -3.0,
        equalizerEnabled = true,
        equalizerBands = List(count) { index ->
            EqualizerBand(
                type = EqualizerBandType.PEAKING,
                frequencyHz = 250.0 * (index + 1),
                gainDb = if (index == 0) 4.0 else 0.0,
                q = 1.0,
            )
        },
    )

    private fun block(): ByteBuffer {
        val block = ByteBuffer.allocateDirect(BLOCK_SAMPLES * SHORT_BYTES).order(ByteOrder.nativeOrder())
        block.asShortBuffer().put(ShortArray(BLOCK_SAMPLES) { index -> (((index % 128) - 64) * 128).toShort() })
        block.position(0)
        block.limit(BLOCK_SAMPLES * SHORT_BYTES)
        return block
    }

    private fun render(processor: UnifiedDspAudioProcessor, block: ByteBuffer) {
        block.position(0)
        processor.queueInput(block)
        processor.getOutput()
    }

    /**
     * Per-thread allocation accounting is a `com.sun.management` extension, not part of the `java.lang.management`
     * view. It is read through that exported interface, because reflecting on the HotSpot implementation class
     * hits a module access check and returned null — which made this test skip itself on the runtime every CI
     * job uses. A missing or disabled counter is a test failure, not an unsupported JVM.
     */
    private fun allocatedBytes(): Long {
        val bean = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        assertTrue("this JVM does not support per-thread allocation accounting", bean.isThreadAllocatedMemorySupported)
        assertTrue("per-thread allocation accounting is disabled", bean.isThreadAllocatedMemoryEnabled)
        return bean.getThreadAllocatedBytes(Thread.currentThread().id)
    }

    private companion object {
        const val SAMPLE_RATE = 48_000
        const val SECOND_SAMPLE_RATE = 44_100
        const val CHANNELS = 2
        const val FLOAT_BYTES = 4
        const val SHORT_BYTES = 2
        const val SHORT_SCALE = 32_768f
        const val SHORT_MIN = -32_768
        const val SHORT_MAX = 32_767
        const val OUTPUT_BIT_DEPTH = 16
        const val PCM16_VALUES = 65_536
        const val BLOCK_SAMPLES = 2_048
        const val WARMUP_BLOCKS = 64
        const val MEASURED_BLOCKS = 512
        const val REQUEST_CHANGES = 64
        const val MAX_BANDS = 16
        const val ALLOCATION_LIMIT_BYTES = 32L * 1_024L
    }
}
