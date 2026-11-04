package moe.rukamori.archivetune.shared.domain.dsp

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The crossfade or automix gain at the end of the chain, ramped over 20 ms so a host may hand it a new
 * value on any block boundary without a step landing in the samples.
 *
 * A transition is already shaped by the host's own envelope; this stage only has to make the block-to-block
 * hand-off continuous, which a linear ramp of twenty milliseconds does without changing the fade shape.
 */
class TransitionGainRamp(
    sampleRateHz: Int,
    private val channels: Int,
    rampMs: Double = DEFAULT_RAMP_MS,
) {
    private val rampFrames: Int = max(1, (sampleRateHz * rampMs / 1_000.0).roundToInt())
    private var step = 0f
    private var remaining = 0

    var gain: Float = 1f
        private set

    /** The gain the ramp is heading for. */
    var target: Float = 1f
        private set

    init {
        require(sampleRateHz >= MIN_SAMPLE_RATE_HZ) { "sampleRateHz must be at least $MIN_SAMPLE_RATE_HZ" }
        require(channels > 0) { "channels must be positive" }
    }

    val isSteady: Boolean
        get() = remaining == 0 && gain == target

    fun setGain(target: Float) {
        require(target.isFinite() && target >= 0f) { "transition gain must be finite and not negative" }
        if (target == this.target) return
        this.target = target
        step = (target - gain) / rampFrames
        remaining = rampFrames
    }

    /** Applies the gain frame by frame, advancing the ramp. */
    fun apply(samples: FloatArray, frames: Int) {
        require(frames >= 0 && samples.size >= frames * channels) { "samples hold fewer than $frames frames" }
        if (gain == 1f && remaining == 0) return
        var index = 0
        repeat(frames) {
            if (remaining > 0) {
                gain += step
                remaining--
                if (remaining == 0) gain = target
            }
            val current = gain
            for (channel in 0 until channels) {
                samples[index] *= current
                index++
            }
        }
    }

    /** Jumps straight to [gain] without a ramp, for a seek or a fresh stream. */
    fun reset(gain: Float = 1f) {
        require(gain.isFinite() && gain >= 0f) { "transition gain must be finite and not negative" }
        this.gain = gain
        target = gain
        step = 0f
        remaining = 0
    }

    companion object {
        const val DEFAULT_RAMP_MS = 20.0
        private const val MIN_SAMPLE_RATE_HZ = 8_000
    }
}
