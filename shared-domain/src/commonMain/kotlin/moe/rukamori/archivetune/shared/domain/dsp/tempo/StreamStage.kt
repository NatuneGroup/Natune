/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.shared.domain.dsp.tempo

/**
 * A push/pull stage over interleaved float frames. Output length can differ from input length, so a stage
 * produces frames only when it has enough input and every pulled block is whatever is ready at that moment.
 */
internal interface StreamStage {
    /** Frames produced and waiting for [pull]. */
    val pendingFrames: Int

    fun push(samples: FloatArray, frames: Int)

    /** Copies up to [maxFrames] ready frames into [destination] and returns how many were copied. */
    fun pull(destination: FloatArray, maxFrames: Int): Int

    /** Ends the input so the held-back tail becomes pullable. A finished stage must be reset before reuse. */
    fun finish()

    fun reset()
}

/**
 * Growable FIFO of interleaved frames. Frames carry absolute indices so a stage can read a window of input
 * by position, and discarding frames never shifts those positions.
 */
internal class FrameFifo(private val channels: Int) {
    private var data = FloatArray(channels * INITIAL_CAPACITY_FRAMES)
    private var head = 0
    private var startFrame = 0L
    private var liveFrames = 0

    val count: Int
        get() = liveFrames

    val endFrame: Long
        get() = startFrame + liveFrames

    fun append(samples: FloatArray, frames: Int) {
        if (frames == 0) return
        val needed = liveFrames + frames
        if ((head + needed) * channels > data.size) {
            val fresh = if (needed * channels > data.size) FloatArray(maxOf(needed * 2, INITIAL_CAPACITY_FRAMES) * channels) else data
            // Forward copy: the destination starts before the source, so overlapping ranges stay correct.
            for (i in 0 until liveFrames * channels) fresh[i] = data[head * channels + i]
            data = fresh
            head = 0
        }
        samples.copyInto(data, (head + liveFrames) * channels, 0, frames * channels)
        liveFrames = needed
    }

    /** Reads one sample by absolute frame index. Frames past the end read as silence, which flushing relies on. */
    fun sample(frame: Long, channel: Int): Float {
        check(frame >= startFrame) { "frame $frame was already discarded" }
        val offset = frame - startFrame
        if (offset >= liveFrames) return 0f
        return data[(head + offset.toInt()) * channels + channel]
    }

    fun drainTo(destination: FloatArray, maxFrames: Int): Int {
        val frames = minOf(liveFrames, maxFrames)
        if (frames == 0) return 0
        data.copyInto(destination, 0, head * channels, (head + frames) * channels)
        discardBefore(startFrame + frames)
        return frames
    }

    fun discardBefore(frame: Long) {
        val drop = (frame - startFrame).coerceIn(0L, liveFrames.toLong()).toInt()
        head += drop
        liveFrames -= drop
        startFrame += drop
        if (liveFrames == 0) head = 0
    }

    fun clear() {
        head = 0
        liveFrames = 0
        startFrame = 0L
    }

    private companion object {
        const val INITIAL_CAPACITY_FRAMES = 1024
    }
}
