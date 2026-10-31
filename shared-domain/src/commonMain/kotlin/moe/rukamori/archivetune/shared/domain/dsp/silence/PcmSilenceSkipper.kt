package moe.rukamori.archivetune.shared.domain.dsp.silence

import kotlin.math.abs

/**
 * Shortens silence in interleaved float PCM, normalized so that full scale is 1.0. Frames gather into blocks of 100 ms.
 * A block with any audible frame passes through whole, so an onset is never clipped. An all-silent block keeps a fifth
 * of its frames, and the silence kept within one run stops growing at two seconds, so a long pause keeps only its onset.
 *
 * [process] returns the frames that are ready now. A block that is still filling is held back, so one call can return
 * fewer or more frames than it was given. [drain] releases the held partial block at the end of a stream, and [reset]
 * drops it at a seek or any other discontinuity.
 */
class PcmSilenceSkipper(
    private val sampleRate: Int,
    private val channels: Int,
) {
    private val blockFrames = maxOf(sampleRate / 10, 1)
    private val pending = FloatArray(blockFrames * channels)
    private var pendingFrames = 0
    private var pendingAudible = false
    private var retainedSilentFrames = 0L

    init {
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(channels > 0) { "channels must be positive" }
    }

    /**
     * Processes the first [frames] frames of [input] and returns the frames ready now. With [enabled] false the held block
     * is released unchanged ahead of the input, so turning the skip off mid-stream never reorders audio. [onSkipped]
     * receives the number of frames dropped from each silent block.
     */
    fun process(
        input: FloatArray,
        frames: Int,
        enabled: Boolean,
        onSkipped: (skippedFrames: Int) -> Unit = {},
    ): FloatArray {
        require(frames >= 0 && input.size >= frames * channels) { "input holds fewer than $frames frames" }
        val sink = PcmFrameSink()
        if (!enabled) {
            flush(sink, skipSilence = false, onSkipped = onSkipped)
            retainedSilentFrames = 0L
            sink.append(input, 0, frames * channels)
            return sink.toArray()
        }
        var next = 0
        while (next < frames) {
            val take = minOf(blockFrames - pendingFrames, frames - next)
            input.copyInto(pending, pendingFrames * channels, next * channels, (next + take) * channels)
            for (index in pendingFrames until pendingFrames + take) {
                if (!pendingAudible && !isSilent(index * channels)) pendingAudible = true
            }
            pendingFrames += take
            next += take
            if (pendingFrames == blockFrames) flush(sink, skipSilence = true, onSkipped = onSkipped)
        }
        return sink.toArray()
    }

    /** Releases the partial block still held at the end of a stream, classified the same way as a full block. */
    fun drain(onSkipped: (skippedFrames: Int) -> Unit = {}): FloatArray {
        val sink = PcmFrameSink()
        flush(sink, skipSilence = true, onSkipped = onSkipped)
        return sink.toArray()
    }

    /** Forgets the held block and the silence run, at a seek or any other discontinuity. */
    fun reset() {
        pendingFrames = 0
        pendingAudible = false
        retainedSilentFrames = 0L
    }

    private fun flush(sink: PcmFrameSink, skipSilence: Boolean, onSkipped: (Int) -> Unit) {
        if (pendingFrames > 0) {
            if (skipSilence && !pendingAudible) {
                val budget = maxOf(sampleRate.toLong() * 2L - retainedSilentFrames, 0L)
                val keep = minOf(pendingFrames / 5L, budget).toInt()
                sink.append(pending, 0, keep * channels)
                retainedSilentFrames += keep.toLong()
                val skipped = pendingFrames - keep
                if (skipped > 0) onSkipped(skipped)
            } else {
                sink.append(pending, 0, pendingFrames * channels)
                retainedSilentFrames = 0L
            }
        }
        pendingFrames = 0
        pendingAudible = false
    }

    private fun isSilent(offset: Int): Boolean {
        var peak = 0f
        for (channel in 0 until channels) {
            val magnitude = abs(pending[offset + channel])
            if (magnitude > peak) peak = magnitude
        }
        return peak <= SILENT_PEAK
    }

    private companion object {
        /** 1024 in 16-bit PCM units, as a fraction of full scale. */
        const val SILENT_PEAK = 1024f / 32768f
    }
}

private class PcmFrameSink {
    private var data = FloatArray(0)
    private var size = 0

    fun append(source: FloatArray, offset: Int, count: Int) {
        if (count <= 0) return
        if (size + count > data.size) data = data.copyOf(maxOf(size + count, data.size * 2))
        source.copyInto(data, size, offset, offset + count)
        size += count
    }

    fun toArray(): FloatArray = data.copyOf(size)
}
