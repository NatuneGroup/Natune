package moe.rukamori.archivetune.shared.domain.dsp.normalization

import kotlin.math.min
import kotlin.math.pow

/**
 * ReplayGain-style track normalization. The measured track correction and the user's preamp are combined,
 * bounded, and then limited so the track's known peak cannot clip. An unknown measurement counts as unity,
 * so an untagged track plays at its own level.
 */
object NormalizationGain {
    const val MIN_GAIN_DB = -24.0
    const val MAX_GAIN_DB = 12.0
    const val MAX_PREAMP_DB = 12.0

    fun linearGain(trackGainDb: Double?, trackPeak: Double?, preampDb: Double = 0.0): Float {
        val correction = trackGainDb?.takeIf { it.isFinite() } ?: 0.0
        val preamp = preampDb.takeIf { it.isFinite() }?.coerceIn(-MAX_PREAMP_DB, MAX_PREAMP_DB) ?: 0.0
        val gainDb = (correction + preamp).coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
        var linear = 10.0.pow(gainDb / 20.0)
        val peak = trackPeak?.takeIf { it.isFinite() && it > 0.0 }
        if (peak != null) linear = min(linear, 1.0 / peak)
        return linear.toFloat()
    }
}
