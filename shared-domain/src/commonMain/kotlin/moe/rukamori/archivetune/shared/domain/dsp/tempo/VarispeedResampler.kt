package moe.rukamori.archivetune.shared.domain.dsp.tempo

import kotlin.math.floor

/**
 * Linear-interpolation varispeed. Reading the input at [rate] frames per output frame multiplies its pitch
 * by [rate] and divides its length by [rate]. Linear interpolation dulls highs at large ratios, which is
 * acceptable for the ±12 semitone range the chain allows.
 */
internal class VarispeedResampler(
    private val channels: Int,
    val rate: Double,
) : StreamStage {
    init {
        require(channels > 0) { "channels must be positive" }
        require(rate.isFinite() && rate > 0.0) { "rate must be positive" }
    }

    private val input = FrameFifo(channels)
    private val output = FrameFifo(channels)
    private val frame = FloatArray(channels)
    private var position = 0.0
    private var finished = false

    override val pendingFrames: Int
        get() = output.count

    override fun push(samples: FloatArray, frames: Int) {
        check(!finished) { "push after finish" }
        input.append(samples, frames)
        advance(flushing = false)
    }

    override fun pull(destination: FloatArray, maxFrames: Int): Int = output.drainTo(destination, maxFrames)

    override fun finish() {
        if (finished) return
        finished = true
        advance(flushing = true)
    }

    override fun reset() {
        input.clear()
        output.clear()
        position = 0.0
        finished = false
    }

    private fun advance(flushing: Boolean) {
        while (true) {
            val base = floor(position).toLong()
            val end = input.endFrame
            if (flushing) {
                if (base >= end) return
            } else if (base + 1 >= end) {
                return
            }
            val fraction = (position - base).toFloat()
            for (channel in 0 until channels) {
                val a = input.sample(base, channel)
                // The last input frame holds its value while flushing, so the tail has no zero droop.
                val b = if (base + 1 < end) input.sample(base + 1, channel) else a
                frame[channel] = a + (b - a) * fraction
            }
            output.append(frame, 1)
            position += rate
            input.discardBefore(floor(position).toLong())
        }
    }
}
