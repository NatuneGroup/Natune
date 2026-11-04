package moe.rukamori.archivetune.shared.domain.dsp

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

/**
 * Soft-knee peak limiter, ported from the native float chain (`native/dsp/float_dsp.cpp`): a −0.5 dBFS
 * ceiling with a 6 dB knee, a 5 ms attack and a 150 ms release, one gain decision per block with the
 * envelope follower smoothing between blocks.
 *
 * The curve is written in the decibel domain: below the knee the gain is exactly unity, inside the knee it
 * falls as a quadratic, and above the ceiling it falls one decibel per decibel. The stage then bounds its
 * own output at the ceiling, so a host can trust `|y| <= ceilingLinear` for every sample it emits — the
 * guarantee meters and any downstream fixed-point conversion depend on.
 */
class SoftKneeLimiter(
    sampleRateHz: Int,
    private val channels: Int = 1,
    ceilingDecibels: Double = DEFAULT_CEILING_DB,
) {
    private val attackCoefficient: Float = onePole(ATTACK_MS, sampleRateHz)
    private val releaseCoefficient: Float = onePole(RELEASE_MS, sampleRateHz)
    private var envelope = 0f

    /** The ceiling in decibels, never above 0. */
    var ceilingDb: Double = ceilingDecibels
        private set

    /** The largest magnitude this limiter emits. */
    var ceilingLinear: Float = linearOf(ceilingDecibels)
        private set

    init {
        require(sampleRateHz >= MIN_SAMPLE_RATE_HZ) { "sampleRateHz must be at least $MIN_SAMPLE_RATE_HZ" }
        require(channels > 0) { "channels must be positive" }
        require(ceilingDecibels.isFinite() && ceilingDecibels in MIN_CEILING_DB..0.0) {
            "ceilingDb must be within $MIN_CEILING_DB..0"
        }
    }

    /** Retunes the ceiling. Callers apply it between blocks, so no sample sees a half-set ceiling. */
    fun setCeilingDb(ceilingDb: Double) {
        require(ceilingDb.isFinite() && ceilingDb in MIN_CEILING_DB..0.0) { "ceilingDb must be within $MIN_CEILING_DB..0" }
        this.ceilingDb = ceilingDb
        ceilingLinear = linearOf(ceilingDb)
    }

    fun process(samples: FloatArray, frames: Int) {
        require(frames >= 0 && samples.size >= frames * channels) { "samples hold fewer than $frames frames" }
        val count = frames * channels
        if (count == 0) return

        var blockPeak = 0f
        for (index in 0 until count) {
            val magnitude = if (samples[index] < 0f) -samples[index] else samples[index]
            if (magnitude > blockPeak) blockPeak = magnitude
        }
        val coefficient = if (blockPeak > envelope) attackCoefficient else releaseCoefficient
        envelope = coefficient * envelope + (1f - coefficient) * blockPeak

        if (envelope > MIN_ENVELOPE) {
            val linear = gainFor(dbOf(envelope), ceilingDb).toFloat()
            // Unity gain is the common case; skipping the multiply keeps an unclipped block bit-exact.
            if (linear < UNITY_TOLERANCE) {
                for (index in 0 until count) samples[index] *= linear
            }
        }
        val ceiling = ceilingLinear
        for (index in 0 until count) samples[index] = samples[index].coerceIn(-ceiling, ceiling)
    }

    fun reset() {
        envelope = 0f
    }

    companion object {
        const val DEFAULT_CEILING_DB = -0.5
        const val MIN_CEILING_DB = -24.0
        const val KNEE_DB = 6.0
        const val ATTACK_MS = 5.0
        const val RELEASE_MS = 150.0
        const val MIN_SAMPLE_RATE_HZ = 8_000

        private const val MIN_ENVELOPE = 1e-6f
        private const val UNITY_TOLERANCE = 0.999999
        private const val DECIBELS_PER_NEPER = 8.685889638065035
        private const val NATURAL_LOG_OF_TEN_OVER_TWENTY = 0.11512925464970229

        /**
         * The gain the soft-knee curve asks for at one envelope level, as a linear multiplier. Exposed so a
         * native port can be checked against these exact numbers.
         */
        fun gainFor(envelopeDb: Double, ceilingDb: Double = DEFAULT_CEILING_DB): Double {
            val kneeStart = ceilingDb - KNEE_DB
            if (envelopeDb <= kneeStart) return 1.0
            val gainDb = if (envelopeDb < ceilingDb) {
                val over = envelopeDb - kneeStart
                -(over * over) / (2.0 * KNEE_DB)
            } else {
                ceilingDb - envelopeDb - KNEE_DB / 2.0
            }
            return exp(gainDb * NATURAL_LOG_OF_TEN_OVER_TWENTY)
        }

        /** Envelope level in decibels, the unit the curve is written in. */
        fun dbOf(magnitude: Float): Double = ln(max(magnitude.toDouble(), 1e-12)) * DECIBELS_PER_NEPER

        private fun linearOf(ceilingDecibels: Double): Float = 10.0.pow(ceilingDecibels / 20.0).toFloat()

        private fun onePole(timeConstantMs: Double, sampleRateHz: Int): Float {
            val tauSamples = timeConstantMs * 0.001 * sampleRateHz
            return exp(-1.0 / max(tauSamples, 1.0)).toFloat()
        }
    }
}
