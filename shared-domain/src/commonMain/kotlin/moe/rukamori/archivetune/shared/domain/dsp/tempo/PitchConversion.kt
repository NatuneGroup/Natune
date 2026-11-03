package moe.rukamori.archivetune.shared.domain.dsp.tempo

import kotlin.math.ln

/**
 * The multiplier-to-musical-interval mapping every host's pitch stage shares. A multiplier of 2 is one
 * octave (12 semitones, 1200 cents); semitones and cents are two names for the same log scale.
 */
object PitchConversion {
    /** Semitones for a playback-rate-style pitch multiplier: 12 * log2(multiplier). */
    fun multiplierToSemitones(multiplier: Double): Double = 12.0 * ln(multiplier) / LN2

    /** Cents for a pitch multiplier, the unit AVAudioUnitTimePitch.pitch and the desktop stage speak. */
    fun multiplierToCents(multiplier: Double): Double = multiplierToSemitones(multiplier) * 100.0

    /** The multiplier for a semitone count: 2^(semitones / 12). */
    fun semitonesToMultiplier(semitones: Double): Double = kotlin.math.exp(semitones * LN2 / 12.0)

    private const val LN2 = 0.6931471805599453
}
