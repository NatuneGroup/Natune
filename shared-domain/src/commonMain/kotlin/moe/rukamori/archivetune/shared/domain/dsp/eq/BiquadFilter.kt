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
        val out = DoubleArray(BiquadFilter.COEFFICIENTS)
        lowPassInto(sampleRateHz, frequencyHz, q, out)
        return BiquadCoefficients(out[0], out[1], out[2], out[3], out[4])
    }

    /** [lowPass] written into [out] as b0, b1, b2, a1, a2, so a render-path retune can allocate nothing. */
    fun lowPassInto(sampleRateHz: Int, frequencyHz: Double, q: Double = BUTTERWORTH_Q, out: DoubleArray) {
        val w0 = shapeW0(sampleRateHz, frequencyHz, q)
        val cosW0 = cos(w0)
        val alpha = sin(w0) / (2 * q)
        normalizedInto(
            b0 = (1 - cosW0) / 2,
            b1 = 1 - cosW0,
            b2 = (1 - cosW0) / 2,
            a0 = 1 + alpha,
            a1 = -2 * cosW0,
            a2 = 1 - alpha,
            out = out,
        )
    }

    fun highPass(sampleRateHz: Int, frequencyHz: Double, q: Double = BUTTERWORTH_Q): BiquadCoefficients {
        val out = DoubleArray(BiquadFilter.COEFFICIENTS)
        highPassInto(sampleRateHz, frequencyHz, q, out)
        return BiquadCoefficients(out[0], out[1], out[2], out[3], out[4])
    }

    /** [highPass] written into [out] as b0, b1, b2, a1, a2. */
    fun highPassInto(sampleRateHz: Int, frequencyHz: Double, q: Double = BUTTERWORTH_Q, out: DoubleArray) {
        val w0 = shapeW0(sampleRateHz, frequencyHz, q)
        val cosW0 = cos(w0)
        val alpha = sin(w0) / (2 * q)
        normalizedInto(
            b0 = (1 + cosW0) / 2,
            b1 = -(1 + cosW0),
            b2 = (1 + cosW0) / 2,
            a0 = 1 + alpha,
            a1 = -2 * cosW0,
            a2 = 1 - alpha,
            out = out,
        )
    }

    fun peaking(sampleRateHz: Int, frequencyHz: Double, gainDb: Double, q: Double): BiquadCoefficients {
        val out = DoubleArray(BiquadFilter.COEFFICIENTS)
        peakingInto(sampleRateHz, frequencyHz, gainDb, q, out)
        return BiquadCoefficients(out[0], out[1], out[2], out[3], out[4])
    }

    /** [peaking] written into [out] as b0, b1, b2, a1, a2. */
    fun peakingInto(sampleRateHz: Int, frequencyHz: Double, gainDb: Double, q: Double, out: DoubleArray) {
        val w0 = shapeW0(sampleRateHz, frequencyHz, q)
        val cosW0 = cos(w0)
        val alpha = sin(w0) / (2 * q)
        val a = amplitude(gainDb)
        normalizedInto(
            b0 = 1 + alpha * a,
            b1 = -2 * cosW0,
            b2 = 1 - alpha * a,
            a0 = 1 + alpha / a,
            a1 = -2 * cosW0,
            a2 = 1 - alpha / a,
            out = out,
        )
    }

    fun lowShelf(sampleRateHz: Int, frequencyHz: Double, gainDb: Double, q: Double): BiquadCoefficients {
        val out = DoubleArray(BiquadFilter.COEFFICIENTS)
        lowShelfInto(sampleRateHz, frequencyHz, gainDb, q, out)
        return BiquadCoefficients(out[0], out[1], out[2], out[3], out[4])
    }

    /** [lowShelf] written into [out] as b0, b1, b2, a1, a2. */
    fun lowShelfInto(sampleRateHz: Int, frequencyHz: Double, gainDb: Double, q: Double, out: DoubleArray) {
        val w0 = shapeW0(sampleRateHz, frequencyHz, q)
        val cosW0 = cos(w0)
        val alpha = sin(w0) / (2 * q)
        val a = amplitude(gainDb)
        val beta = 2 * sqrt(a) * alpha
        normalizedInto(
            b0 = a * ((a + 1) - (a - 1) * cosW0 + beta),
            b1 = 2 * a * ((a - 1) - (a + 1) * cosW0),
            b2 = a * ((a + 1) - (a - 1) * cosW0 - beta),
            a0 = (a + 1) + (a - 1) * cosW0 + beta,
            a1 = -2 * ((a - 1) + (a + 1) * cosW0),
            a2 = (a + 1) + (a - 1) * cosW0 - beta,
            out = out,
        )
    }

    fun highShelf(sampleRateHz: Int, frequencyHz: Double, gainDb: Double, q: Double): BiquadCoefficients {
        val out = DoubleArray(BiquadFilter.COEFFICIENTS)
        highShelfInto(sampleRateHz, frequencyHz, gainDb, q, out)
        return BiquadCoefficients(out[0], out[1], out[2], out[3], out[4])
    }

    /** [highShelf] written into [out] as b0, b1, b2, a1, a2. */
    fun highShelfInto(sampleRateHz: Int, frequencyHz: Double, gainDb: Double, q: Double, out: DoubleArray) {
        val w0 = shapeW0(sampleRateHz, frequencyHz, q)
        val cosW0 = cos(w0)
        val alpha = sin(w0) / (2 * q)
        val a = amplitude(gainDb)
        val beta = 2 * sqrt(a) * alpha
        normalizedInto(
            b0 = a * ((a + 1) + (a - 1) * cosW0 + beta),
            b1 = -2 * a * ((a - 1) + (a + 1) * cosW0),
            b2 = a * ((a + 1) + (a - 1) * cosW0 - beta),
            a0 = (a + 1) - (a - 1) * cosW0 + beta,
            a1 = 2 * ((a - 1) - (a + 1) * cosW0),
            a2 = (a + 1) - (a - 1) * cosW0 - beta,
            out = out,
        )
    }

    /**
     * The same high shelf written with the cookbook's slope parameter instead of a Q. LastWave's clarity
     * contour is specified as a slope (0.85), and the two shapes are not interchangeable, so the shared
     * design keeps both forms and the clarity preset names the one it was ported with.
     */
    fun highShelfSlope(sampleRateHz: Int, frequencyHz: Double, gainDb: Double, slope: Double): BiquadCoefficients {
        val out = DoubleArray(BiquadFilter.COEFFICIENTS)
        highShelfSlopeInto(sampleRateHz, frequencyHz, gainDb, slope, out)
        return BiquadCoefficients(out[0], out[1], out[2], out[3], out[4])
    }

    /** [highShelfSlope] written into [out] as b0, b1, b2, a1, a2. */
    fun highShelfSlopeInto(
        sampleRateHz: Int,
        frequencyHz: Double,
        gainDb: Double,
        slope: Double,
        out: DoubleArray,
    ) {
        require(slope.isFinite() && slope > 0.0) { "slope must be positive" }
        val frequency = clampFrequency(sampleRateHz, frequencyHz)
        val w0 = 2 * PI * frequency / sampleRateHz
        val cosW0 = cos(w0)
        val sinW0 = sin(w0)
        val a = amplitude(gainDb)
        val safeSlope = slope.coerceIn(0.1, 1.0)
        val alpha = (sinW0 * 0.5) * sqrt((a + 1.0 / a) * (1.0 / safeSlope - 1.0) + 2.0)
        val beta = 2 * sqrt(a) * alpha
        normalizedInto(
            b0 = a * ((a + 1) + (a - 1) * cosW0 + beta),
            b1 = -2 * a * ((a - 1) + (a + 1) * cosW0),
            b2 = a * ((a + 1) + (a - 1) * cosW0 - beta),
            a0 = (a + 1) - (a - 1) * cosW0 + beta,
            a1 = 2 * ((a - 1) - (a + 1) * cosW0),
            a2 = (a + 1) - (a - 1) * cosW0 - beta,
            out = out,
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

    /** The cookbook's angular centre frequency, with the same validation the object-returning designs apply. */
    private fun shapeW0(sampleRateHz: Int, frequencyHz: Double, q: Double): Double {
        require(q.isFinite() && q > 0.0) { "q must be positive" }
        return 2 * PI * clampFrequency(sampleRateHz, frequencyHz) / sampleRateHz
    }

    /** Writes b0, b1, b2, a1, a2 with a0 divided out, so no coefficient object is built. */
    private fun normalizedInto(
        b0: Double,
        b1: Double,
        b2: Double,
        a0: Double,
        a1: Double,
        a2: Double,
        out: DoubleArray,
    ) {
        require(out.size >= BiquadFilter.COEFFICIENTS) { "out holds fewer than ${BiquadFilter.COEFFICIENTS} coefficients" }
        out[0] = b0 / a0
        out[1] = b1 / a0
        out[2] = b2 / a0
        out[3] = a1 / a0
        out[4] = a2 / a0
    }
}

/**
 * One biquad in transposed direct form II. History is kept per channel, so a stereo stream filtered
 * through one instance never leaks one channel into the other.
 *
 * A second coefficient set may ride along for the right channel of every stereo pair, which is what a
 * band with a per-channel override uses; without one, every channel shares the primary design.
 *
 * The running coefficients are held as primitives. A retune on the playback thread installs a design that
 * was computed into a plain array ([setDesign]), so it allocates nothing; the value-typed API stays for
 * hosts and tests that design off the render path.
 */
class BiquadFilter(private val channels: Int) {
    private val z1: DoubleArray
    private val z2: DoubleArray

    private var primaryB0 = 1.0
    private var primaryB1 = 0.0
    private var primaryB2 = 0.0
    private var primaryA1 = 0.0
    private var primaryA2 = 0.0
    private var secondaryB0 = 1.0
    private var secondaryB1 = 0.0
    private var secondaryB2 = 0.0
    private var secondaryA1 = 0.0
    private var secondaryA2 = 0.0
    private var hasSecondary = false

    init {
        require(channels > 0) { "channels must be positive" }
        z1 = DoubleArray(channels)
        z2 = DoubleArray(channels)
    }

    /** The primary design, for diagnostics. The render path reads the primitives instead. */
    val coefficients: BiquadCoefficients
        get() = BiquadCoefficients(primaryB0, primaryB1, primaryB2, primaryA1, primaryA2)

    /**
     * Swaps coefficients and keeps the history, so a retune mid-stream glides instead of restarting the
     * filter. [rightChannel] replaces the coefficients of channel index 1, 3, … when the stream is stereo.
     */
    fun setCoefficients(next: BiquadCoefficients, rightChannel: BiquadCoefficients? = null) {
        primaryB0 = next.b0
        primaryB1 = next.b1
        primaryB2 = next.b2
        primaryA1 = next.a1
        primaryA2 = next.a2
        hasSecondary = channels > 1 && rightChannel != null && rightChannel != next
        if (hasSecondary && rightChannel != null) {
            secondaryB0 = rightChannel.b0
            secondaryB1 = rightChannel.b1
            secondaryB2 = rightChannel.b2
            secondaryA1 = rightChannel.a1
            secondaryA2 = rightChannel.a2
        }
    }

    /**
     * Installs a design already written as b0, b1, b2, a1, a2, so a coefficient change on the playback
     * thread allocates nothing. [rightDesign] replaces channel 1's coefficients exactly as [setCoefficients].
     */
    fun setDesign(design: DoubleArray, rightDesign: DoubleArray? = null) {
        require(design.size >= COEFFICIENTS) { "design holds fewer than $COEFFICIENTS coefficients" }
        primaryB0 = design[0]
        primaryB1 = design[1]
        primaryB2 = design[2]
        primaryA1 = design[3]
        primaryA2 = design[4]
        hasSecondary = channels > 1 && rightDesign != null && !sameDesign(design, rightDesign)
        if (hasSecondary && rightDesign != null) {
            secondaryB0 = rightDesign[0]
            secondaryB1 = rightDesign[1]
            secondaryB2 = rightDesign[2]
            secondaryA1 = rightDesign[3]
            secondaryA2 = rightDesign[4]
        }
    }

    /** True when [left] and [right] carry the same five coefficients. */
    private fun sameDesign(left: DoubleArray, right: DoubleArray): Boolean {
        if (right.size < COEFFICIENTS) return false
        for (index in 0 until COEFFICIENTS) {
            if (left[index] != right[index]) return false
        }
        return true
    }

    /** Filters one sample of [channel] and returns it, advancing that channel's history. */
    fun tick(input: Float, channel: Int): Float {
        val x = input.toDouble()
        return if (channel and 1 == 1 && hasSecondary) {
            val y = secondaryB0 * x + z1[channel]
            z1[channel] = secondaryB1 * x - secondaryA1 * y + z2[channel]
            z2[channel] = secondaryB2 * x - secondaryA2 * y
            y.toFloat()
        } else {
            val y = primaryB0 * x + z1[channel]
            z1[channel] = primaryB1 * x - primaryA1 * y + z2[channel]
            z2[channel] = primaryB2 * x - primaryA2 * y
            y.toFloat()
        }
    }

    /** Filters [frames] interleaved frames in place, starting at [startFrame]. */
    fun process(samples: FloatArray, frames: Int, startFrame: Int = 0) {
        require(frames >= 0 && startFrame >= 0 && samples.size >= (startFrame + frames) * channels) {
            "samples hold fewer than $frames frames from frame $startFrame"
        }
        var index = startFrame * channels
        repeat(frames) {
            for (channel in 0 until channels) {
                val x = samples[index].toDouble()
                val y: Double
                if (channel and 1 == 1 && hasSecondary) {
                    y = secondaryB0 * x + z1[channel]
                    z1[channel] = secondaryB1 * x - secondaryA1 * y + z2[channel]
                    z2[channel] = secondaryB2 * x - secondaryA2 * y
                } else {
                    y = primaryB0 * x + z1[channel]
                    z1[channel] = primaryB1 * x - primaryA1 * y + z2[channel]
                    z2[channel] = primaryB2 * x - primaryA2 * y
                }
                samples[index] = y.toFloat()
                index++
            }
        }
    }


    fun reset() {
        z1.fill(0.0)
        z2.fill(0.0)
    }

    companion object {
        /** b0, b1, b2, a1, a2: the plain-array shape [setDesign] takes. */
        const val COEFFICIENTS = 5
    }
}
