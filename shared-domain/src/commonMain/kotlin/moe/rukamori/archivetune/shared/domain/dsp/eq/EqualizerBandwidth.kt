package moe.rukamori.archivetune.shared.domain.dsp.eq

import kotlin.math.log2
import kotlin.math.sqrt

/**
 * The Q-factor to bandwidth-in-octaves mapping shared by hosts that drive a parametric equalizer whose
 * API takes octaves (AVAudioUnitEQ on iOS). Biquad designs speak Q; the conversion is the standard
 * bandpass definition, exact in both directions.
 */
object EqualizerBandwidth {
    /**
     * Bandwidth in octaves for a quality factor [q]: N = 2 * log2((1 + sqrt(1 + 4Q^2)) / (2Q)), the
     * standard digital-equalizer convention where Q = sqrt(2) is exactly one octave.
     */
    fun octavesForQ(q: Double): Double {
        require(q.isFinite() && q > 0.0) { "q must be positive" }
        return 2.0 * log2((1.0 + sqrt(1.0 + 4.0 * q * q)) / (2.0 * q))
    }

    /** Quality factor for a bandwidth of [octaves]: Q = sqrt(2^N) / (2^N - 1). */
    fun qForOctaves(octaves: Double): Double {
        require(octaves.isFinite() && octaves > 0.0) { "octaves must be positive" }
        val ratio = kotlin.math.exp(octaves * LN2)
        return sqrt(ratio) / (ratio - 1.0)
    }

    private const val LN2 = 0.6931471805599453
}
