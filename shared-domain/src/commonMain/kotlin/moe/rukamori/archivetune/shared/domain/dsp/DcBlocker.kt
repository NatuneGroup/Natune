package moe.rukamori.archivetune.shared.domain.dsp

import kotlin.math.PI

/**
 * One-pole DC blocker, the same recursion the native float chain runs (`native/dsp/float_dsp.cpp`):
 * y[n] = x[n] - x[n-1] + R·y[n-1] with R = 1 - 2π·f/fs, clamped below 1 so it stays stable at any rate.
 *
 * A stream DC offset eats peak headroom asymmetrically and clicks at track boundaries, so the chain blocks
 * it before gain staging. The corner sits at 5 Hz, far below anything audible: program material without an
 * offset passes through essentially untouched.
 */
class DcBlocker(
    sampleRateHz: Int,
    private val channels: Int,
    cutoffHz: Double = DEFAULT_CUTOFF_HZ,
) {
    private val previousInput = DoubleArray(channels)
    private val previousOutput = DoubleArray(channels)
    private val pole: Double

    init {
        require(sampleRateHz >= MIN_SAMPLE_RATE_HZ) { "sampleRateHz must be at least $MIN_SAMPLE_RATE_HZ" }
        require(channels > 0) { "channels must be positive" }
        require(cutoffHz.isFinite() && cutoffHz > 0.0) { "cutoffHz must be positive" }
        pole = (1.0 - 2.0 * PI * cutoffHz / sampleRateHz).coerceIn(0.0, MAX_POLE)
    }

    fun process(samples: FloatArray, frames: Int) {
        require(frames >= 0 && samples.size >= frames * channels) { "samples hold fewer than $frames frames" }
        var index = 0
        repeat(frames) {
            for (channel in 0 until channels) {
                val input = samples[index].toDouble()
                val output = input - previousInput[channel] + pole * previousOutput[channel]
                previousInput[channel] = input
                previousOutput[channel] = output
                samples[index] = output.toFloat()
                index++
            }
        }
    }

    fun reset() {
        previousInput.fill(0.0)
        previousOutput.fill(0.0)
    }

    companion object {
        const val DEFAULT_CUTOFF_HZ = 5.0
        const val MIN_SAMPLE_RATE_HZ = 8_000
        private const val MAX_POLE = 0.999
    }
}
