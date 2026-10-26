/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.ForwardingAudioOutput
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToLong

/**
 * Wraps the stock AudioTrack provider so each AudioTrack can be negotiated for bit-perfect USB
 * output (see [BitPerfectUsbOutput]).
 *
 * The sink hands this provider float (or 16-bit) PCM. When the DAC's bit-perfect mode wants a
 * different container — typically 24- or 32-bit integer — the AudioTrack is created in that
 * container and [ConvertingAudioOutput] converts on write. Float carries 16- and 24-bit sources
 * exactly, so the conversion returns the original integers: the DAC receives the file's samples.
 * When nothing is negotiated this provider is a plain pass-through.
 */
@UnstableApi
class BitPerfectAudioOutputProvider(
    context: Context,
    delegate: AudioOutputProvider,
) : ForwardingAudioOutputProvider(delegate) {
    private val appContext = context.applicationContext

    override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput {
        val plan = BitPerfectUsbOutput.plan(appContext, config) ?: return super.getAudioOutput(config)
        if (plan.encoding == config.encoding) return super.getAudioOutput(config)

        val channelCount = Integer.bitCount(config.channelMask).coerceAtLeast(1)
        val inFrameSize = bytesPerSample(config.encoding) * channelCount
        val outFrameSize = bytesPerSample(plan.encoding) * channelCount
        val frames = config.bufferSize / inFrameSize
        val converted =
            config
                .buildUpon()
                .setEncoding(plan.encoding)
                .setBufferSize(frames * outFrameSize)
                .build()
        return ConvertingAudioOutput(
            output = super.getAudioOutput(converted),
            inputEncoding = config.encoding,
            outputEncoding = plan.encoding,
            channelCount = channelCount,
        )
    }

    override fun release() {
        BitPerfectUsbOutput.clear(appContext)
        super.release()
    }

    internal companion object {
        fun bytesPerSample(encoding: Int): Int =
            when (encoding) {
                C.ENCODING_PCM_16BIT -> 2
                C.ENCODING_PCM_24BIT -> 3
                C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
                else -> throw IllegalArgumentException("Unsupported PCM encoding $encoding")
            }
    }
}

/**
 * Converts PCM between containers on its way into an AudioTrack.
 *
 * The input buffer's position only advances by the frames the AudioTrack actually accepted, so the
 * sink's written-frame accounting (and with it end-of-stream draining) stays exact even when a
 * non-blocking write takes only part of a buffer. The remainder stays converted in [pending] and
 * is offered first on the next call, which the sink makes with the same buffer.
 */
@UnstableApi
internal class ConvertingAudioOutput(
    output: AudioOutput,
    private val inputEncoding: Int,
    private val outputEncoding: Int,
    channelCount: Int,
) : ForwardingAudioOutput(output) {
    private val inner = output
    private val inFrameSize = BitPerfectAudioOutputProvider.bytesPerSample(inputEncoding) * channelCount
    private val outFrameSize = BitPerfectAudioOutputProvider.bytesPerSample(outputEncoding) * channelCount

    private var pending: ByteBuffer? = null
    private var pendingSource: ByteBuffer? = null
    private var pendingSourceStart = 0
    private var scratch: ByteBuffer = ByteBuffer.allocateDirect(0)

    override fun write(
        buffer: ByteBuffer,
        encodedAccessUnitCount: Int,
        presentationTimeUs: Long,
    ): Boolean {
        if (pending != null && pendingSource !== buffer) {
            pending = null
            pendingSource = null
        }
        val out =
            pending ?: run {
                val frames = buffer.remaining() / inFrameSize
                if (frames == 0) return true
                val converted = convert(buffer, frames)
                pending = converted
                pendingSource = buffer
                pendingSourceStart = buffer.position()
                converted
            }
        inner.write(out, encodedAccessUnitCount, presentationTimeUs)
        val framesDone = out.position() / outFrameSize
        buffer.position(pendingSourceStart + framesDone * inFrameSize)
        if (!out.hasRemaining()) {
            pending = null
            pendingSource = null
        }
        return !buffer.hasRemaining()
    }

    override fun flush() {
        pending = null
        pendingSource = null
        super.flush()
    }

    override fun release() {
        pending = null
        pendingSource = null
        super.release()
    }

    /** Converts [frames] frames starting at [source]'s position without moving that position. */
    private fun convert(
        source: ByteBuffer,
        frames: Int,
    ): ByteBuffer {
        val needed = frames * outFrameSize
        if (scratch.capacity() < needed) {
            scratch = ByteBuffer.allocateDirect(needed).order(ByteOrder.nativeOrder())
        }
        val out = scratch
        out.clear()
        out.order(ByteOrder.nativeOrder())
        val input = source.duplicate().order(ByteOrder.nativeOrder())
        val sampleCount = frames * (inFrameSize / BitPerfectAudioOutputProvider.bytesPerSample(inputEncoding))
        repeat(sampleCount) {
            writeSample(out, readSample(input))
        }
        out.flip()
        out.limit(needed)
        return out
    }

    /** Reads one sample as a left-justified signed 32-bit value. */
    private fun readSample(input: ByteBuffer): Int =
        when (inputEncoding) {
            C.ENCODING_PCM_16BIT -> input.short.toInt() shl 16
            C.ENCODING_PCM_24BIT -> {
                val b0 = input.get().toInt() and 0xFF
                val b1 = input.get().toInt() and 0xFF
                val b2 = input.get().toInt()
                ((b2 shl 16) or (b1 shl 8) or b0) shl 8
            }
            C.ENCODING_PCM_32BIT -> input.int
            else -> floatToInt32(input.float)
        }

    private fun writeSample(
        out: ByteBuffer,
        sample: Int,
    ) {
        when (outputEncoding) {
            C.ENCODING_PCM_16BIT -> out.putShort((sample shr 16).toShort())
            C.ENCODING_PCM_24BIT -> {
                val v = sample shr 8
                out.put((v and 0xFF).toByte())
                out.put(((v shr 8) and 0xFF).toByte())
                out.put(((v shr 16) and 0xFF).toByte())
            }
            C.ENCODING_PCM_32BIT -> out.putInt(sample)
            else -> out.putFloat((sample.toDouble() / FULL_SCALE).toFloat())
        }
    }

    private fun floatToInt32(value: Float): Int {
        if (value.isNaN()) return 0
        val scaled = (value.toDouble() * FULL_SCALE).roundToLong()
        return scaled.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
    }

    private companion object {
        const val FULL_SCALE = 2147483648.0
    }
}
