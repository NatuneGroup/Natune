package moe.rukamori.archivetune.shared.domain.dsp

import kotlin.math.round

/**
 * Triangular-PDF dither and requantization to a fixed-point grid, the last stage of the chain.
 *
 * The generator is the xorshift32 the retired native float chain used: two uniforms in [-0.5, 0.5) from one
 * word, their difference a triangle one LSB wide peak to peak. Seeded identically it produces an identical
 * sequence, which is what the parity test pins.
 *
 * Quantization uses `2^(bits-1) - 1` steps of full scale, the full scale the native float-to-int16
 * conversion uses, so a full-scale sample stays at full scale instead of overshooting it.
 */
class TpdfDither(
    bitDepth: Int = DspDither.DEFAULT_BIT_DEPTH,
    seed: Int = DspDither.DEFAULT_SEED,
) {
    private var state: Int = if (seed == 0) DspDither.DEFAULT_SEED else seed

    var bitDepth: Int = validatedBitDepth(bitDepth)
        private set

    /** Full-scale steps at [bitDepth]: 32767 for 16 bits, the value the native conversion multiplies by. */
    var fullScaleSteps: Float = fullScaleFor(bitDepth)
        private set

    fun setFormat(bitDepth: Int) {
        this.bitDepth = validatedBitDepth(bitDepth)
        fullScaleSteps = fullScaleFor(bitDepth)
    }

    /** Restarts the sequence. Only a host starting a new stream calls this; a running stream keeps going. */
    fun reseed(seed: Int) {
        state = if (seed == 0) DspDither.DEFAULT_SEED else seed
    }

    /** Adds one LSB of triangular noise and rounds to the target grid, in place. */
    fun process(samples: FloatArray, frames: Int, channels: Int) {
        require(frames >= 0 && samples.size >= frames * channels) { "samples hold fewer than $frames frames" }
        val scale = fullScaleSteps
        val inverse = 1f / scale
        for (index in 0 until frames * channels) {
            val noise = nextUniform() - nextUniform()
            val quantized = round(samples[index] * scale + noise) * inverse
            samples[index] = quantized.coerceIn(-1f, 1f)
        }
    }

    /** One uniform in [-0.5, 0.5) from the xorshift32 word, matching the native sequence exactly. */
    fun nextUniform(): Float {
        state = state xor (state shl 13)
        state = state xor (state ushr 17)
        state = state xor (state shl 5)
        return (state ushr 8).toFloat() / UNIFORM_DIVISOR - 0.5f
    }

    companion object {
        private const val UNIFORM_DIVISOR = 16_777_216f

        private fun validatedBitDepth(bitDepth: Int): Int {
            require(bitDepth in DspDither.MIN_BIT_DEPTH..DspDither.MAX_BIT_DEPTH) {
                "bitDepth must be within ${DspDither.MIN_BIT_DEPTH}..${DspDither.MAX_BIT_DEPTH}"
            }
            return bitDepth
        }

        private fun fullScaleFor(bitDepth: Int): Float {
            var steps = 1f
            repeat(validatedBitDepth(bitDepth) - 1) { steps *= 2f }
            return steps - 1f
        }
    }
}
