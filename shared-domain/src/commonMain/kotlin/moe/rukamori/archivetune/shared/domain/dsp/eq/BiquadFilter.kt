package moe.rukamori.archivetune.shared.domain.dsp.eq

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Normalized biquad coefficients with a0 divided out:
 * H(z) = (b0 + b1·z⁻¹ + b2·z⁻²) / (1 + a1·z⁻¹ + a2·z⁻²).
 */
data class BiquadCoefficients(
    val b0: Double,
    val b1: Double,
    val b2: Double,
    val a1: Double,
    val a2: Double,
) {
    companion object {
        val IDENTITY = BiquadCoefficients(b0 = 1.0, b1 = 0.0, b2 = 0.0, a1 = 0.0, a2 = 0.0)
    }
}

/**
 * Audio EQ Cookbook (RBJ) designs. Every frequency is clamped into 20 Hz..(sampleRate/2 - 1), so a cutoff
 * at or above Nyquist cannot drive the recursion unstable.
 */
object BiquadDesign {
    const val MIN_FREQUENCY_HZ = 20.0
    const val MIN_SAMPLE_RATE_HZ = 8_000
    const val BUTTERWORTH_Q = 0.7071067811865476

    fun clampFrequency(sampleRateHz: Int, frequencyHz: Double): Double {
        require(sampleRateHz >= MIN_SAMPLE_RATE_HZ) { "sampleRateHz must be at least $MIN_SAMPLE_RATE_HZ" }
        require(frequencyHz.isFinite()) { "frequency must be finite" }
        return frequencyHz.coerceIn(MIN_FREQUENCY_HZ, sampleRateHz / 2.0 - 1.0)
    }

    fun lowPass(sampleRateHz: Int, frequencyHz: Double, q: Double = BUTTERWORTH_Q): BiquadCoefficients {
        val shape = Shape.of(sampleRateHz, frequencyHz, q)
        return normalized(
            b0 = (1 - shape.cos) / 2,
            b1 = 1 - shape.cos,
            b2 = (1 - shape.cos) / 2,
            a0 = 1 + shape.alpha,
            a1 = -2 * shape.cos,
            a2 = 1 - shape.alpha,
        )
    }

    fun highPass(sampleRateHz: Int, frequencyHz: Double, q: Double = BUTTERWORTH_Q): BiquadCoefficients {
        val shape = Shape.of(sampleRateHz, frequencyHz, q)
        return normalized(
            b0 = (1 + shape.cos) / 2,
            b1 = -(1 + shape.cos),
            b2 = (1 + shape.cos) / 2,
            a0 = 1 + shape.alpha,
            a1 = -2 * shape.cos,
            a2 = 1 - shape.alpha,
        )
    }

    fun peaking(sampleRateHz: Int, frequencyHz: Double, gainDb: Double, q: Double): BiquadCoefficients {
        val shape = Shape.of(sampleRateHz, frequencyHz, q)
        val a = amplitude(gainDb)
        return normalized(
            b0 = 1 + shape.alpha * a,
            b1 = -2 * shape.cos,
            b2 = 1 - shape.alpha * a,
            a0 = 1 + shape.alpha / a,
            a1 = -2 * shape.cos,
            a2 = 1 - shape.alpha / a,
        )
    }

    fun lowShelf(sampleRateHz: Int, frequencyHz: Double, gainDb: Double, q: Double): BiquadCoefficients {
        val shape = Shape.of(sampleRateHz, frequencyHz, q)
        val a = amplitude(gainDb)
        val beta = 2 * sqrt(a) * shape.alpha
        return normalized(
            b0 = a * ((a + 1) - (a - 1) * shape.cos + beta),
            b1 = 2 * a * ((a - 1) - (a + 1) * shape.cos),
            b2 = a * ((a + 1) - (a - 1) * shape.cos - beta),
            a0 = (a + 1) + (a - 1) * shape.cos + beta,
            a1 = -2 * ((a - 1) + (a + 1) * shape.cos),
            a2 = (a + 1) + (a - 1) * shape.cos - beta,
        )
    }

    fun highShelf(sampleRateHz: Int, frequencyHz: Double, gainDb: Double, q: Double): BiquadCoefficients {
        val shape = Shape.of(sampleRateHz, frequencyHz, q)
        val a = amplitude(gainDb)
        val beta = 2 * sqrt(a) * shape.alpha
        return normalized(
            b0 = a * ((a + 1) + (a - 1) * shape.cos + beta),
            b1 = -2 * a * ((a - 1) + (a + 1) * shape.cos),
            b2 = a * ((a + 1) + (a - 1) * shape.cos - beta),
            a0 = (a + 1) - (a - 1) * shape.cos + beta,
            a1 = 2 * ((a - 1) - (a + 1) * shape.cos),
            a2 = (a + 1) - (a - 1) * shape.cos - beta,
        )
    }

    /** Magnitude |H(e^jw)| at [frequencyHz]. Analysis helper for tests and diagnostics; playback never calls it. */
    fun magnitude(coefficients: BiquadCoefficients, sampleRateHz: Int, frequencyHz: Double): Double {
        val w = 2 * PI * frequencyHz / sampleRateHz
        val cos1 = cos(w)
        val sin1 = sin(w)
        val cos2 = cos(2 * w)
        val sin2 = sin(2 * w)
        val numeratorRe = coefficients.b0 + coefficients.b1 * cos1 + coefficients.b2 * cos2
        val numeratorIm = -(coefficients.b1 * sin1 + coefficients.b2 * sin2)
        val denominatorRe = 1 + coefficients.a1 * cos1 + coefficients.a2 * cos2
        val denominatorIm = -(coefficients.a1 * sin1 + coefficients.a2 * sin2)
        return sqrt(numeratorRe * numeratorRe + numeratorIm * numeratorIm) /
            sqrt(denominatorRe * denominatorRe + denominatorIm * denominatorIm)
    }

    private fun amplitude(gainDb: Double): Double = 10.0.pow(gainDb / 40.0)

    private fun normalized(
        b0: Double,
        b1: Double,
        b2: Double,
        a0: Double,
        a1: Double,
        a2: Double,
    ): BiquadCoefficients = BiquadCoefficients(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)

    private class Shape(val cos: Double, val alpha: Double) {
        companion object {
            fun of(sampleRateHz: Int, frequencyHz: Double, q: Double): Shape {
                require(q.isFinite() && q > 0.0) { "q must be positive" }
                val w0 = 2 * PI * clampFrequency(sampleRateHz, frequencyHz) / sampleRateHz
                return Shape(cos = cos(w0), alpha = sin(w0) / (2 * q))
            }
        }
    }
}

/**
 * One biquad in transposed direct form II. History is kept per channel, so a stereo stream filtered
 * through one instance never leaks one channel into the other.
 */
class BiquadFilter(private val channels: Int) {
    private val z1: DoubleArray
    private val z2: DoubleArray

    init {
        require(channels > 0) { "channels must be positive" }
        z1 = DoubleArray(channels)
        z2 = DoubleArray(channels)
    }

    var coefficients: BiquadCoefficients = BiquadCoefficients.IDENTITY
        private set

    /** Swaps coefficients and keeps the history, so a retune in the middle of a stream does not click. */
    fun setCoefficients(next: BiquadCoefficients) {
        coefficients = next
    }

    /** Filters [frames] interleaved frames in place. */
    fun process(samples: FloatArray, frames: Int) {
        require(frames >= 0 && samples.size >= frames * channels) { "samples hold fewer than $frames frames" }
        val c = coefficients
        var index = 0
        repeat(frames) {
            for (channel in 0 until channels) {
                val x = samples[index].toDouble()
                val y = c.b0 * x + z1[channel]
                z1[channel] = c.b1 * x - c.a1 * y + z2[channel]
                z2[channel] = c.b2 * x - c.a2 * y
                samples[index] = y.toFloat()
                index++
            }
        }
    }

    fun reset() {
        z1.fill(0.0)
        z2.fill(0.0)
    }
}
