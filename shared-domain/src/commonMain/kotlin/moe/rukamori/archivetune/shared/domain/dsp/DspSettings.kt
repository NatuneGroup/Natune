package moe.rukamori.archivetune.shared.domain.dsp

import kotlin.math.abs
import kotlin.math.pow
import moe.rukamori.archivetune.shared.domain.dsp.clarity.ClarityPreset
import moe.rukamori.archivetune.shared.domain.dsp.eq.EqualizerBand
import moe.rukamori.archivetune.shared.domain.dsp.tempo.TempoPitchStage

/**
 * Everything the one software DSP chain is allowed to do to a stream, in one immutable value.
 *
 * [DspGraph] reads it together with a `SoftwareStagePlan`: the plan says which stages a route may run
 * (a bit-perfect route allows none), these settings hold what the listener asked for. A stage runs only
 * when both allow it. Every default is neutral, so a graph built from `DspSettings()` is sample-transparent
 * apart from the full-scale clamp.
 *
 * Gain staging is deliberately split. [linearGain] is the normalization correction a host measured
 * (`NormalizationGain.linearGain` already folds the user preamp into it), and [preampDb] is the extra
 * preamp a host keeps separately — a host sets one of the two, never the same decibels twice.
 */
data class DspSettings(
    val linearGain: Float = 1f,
    val preampDb: Double = 0.0,
    val normalizationEnabled: Boolean = true,
    val equalizerEnabled: Boolean = true,
    val equalizerBands: List<EqualizerBand> = emptyList(),
    /** LastWave's Studio Master Clarity contour as a named preset. Null keeps the contour out of the chain. */
    val clarity: ClarityPreset? = null,
    val dcBlockerEnabled: Boolean = false,
    val limiterEnabled: Boolean = false,
    val limiterCeilingDb: Double = SoftKneeLimiter.DEFAULT_CEILING_DB,
    /** Pitch-preserving tempo multiplier: 2.0 plays twice as fast. */
    val speed: Double = 1.0,
    val pitchSemitones: Double = 0.0,
    /** Crossfade or automix gain for this block, ramped inside the graph so a step never clicks. */
    val transitionGain: Float = 1f,
    val dither: DspDither = DspDither.OFF,
) {
    init {
        require(linearGain.isFinite() && linearGain > 0f) { "linearGain must be positive" }
        require(preampDb.isFinite() && abs(preampDb) <= MAX_PREAMP_DB) { "preampDb must be within ±$MAX_PREAMP_DB dB" }
        require(equalizerBands.size <= MAX_EQUALIZER_BANDS) { "at most $MAX_EQUALIZER_BANDS equalizer bands" }
        require(limiterCeilingDb.isFinite() && limiterCeilingDb in SoftKneeLimiter.MIN_CEILING_DB..0.0) {
            "limiterCeilingDb must be within ${SoftKneeLimiter.MIN_CEILING_DB}..0 dB"
        }
        require(speed.isFinite() && speed in TempoPitchStage.MIN_SPEED..TempoPitchStage.MAX_SPEED) {
            "speed must be within ${TempoPitchStage.MIN_SPEED}..${TempoPitchStage.MAX_SPEED}"
        }
        require(pitchSemitones.isFinite() && abs(pitchSemitones) <= TempoPitchStage.MAX_PITCH_SEMITONES) {
            "pitch must be within ±${TempoPitchStage.MAX_PITCH_SEMITONES} semitones"
        }
        require(transitionGain.isFinite() && transitionGain in 0f..MAX_TRANSITION_GAIN) {
            "transitionGain must be within 0..$MAX_TRANSITION_GAIN"
        }
    }

    /** The user preamp alone as a linear factor, for a host that applies its own normalization gain. */
    val preampGain: Float
        get() = if (preampDb == 0.0) 1f else 10.0.pow(preampDb / 20.0).toFloat()

    /** Normalization gain and preamp multiplied into the one gain the chain applies first. */
    val linearInputGain: Float
        get() = linearGain * preampGain

    /**
     * The same stored value with every processing stage neutral, for a host whose one Audio processing switch
     * is off. The stored copy keeps every preset, so this value is what an engine renders, never what is saved:
     * turning the switch back on restores the user's curve, preamp and normalization unchanged.
     */
    fun withoutProcessing(): DspSettings = copy(
        linearGain = 1f,
        preampDb = 0.0,
        normalizationEnabled = false,
        equalizerEnabled = false,
        equalizerBands = emptyList(),
        clarity = null,
        dcBlockerEnabled = false,
        limiterEnabled = false,
        dither = DspDither.OFF,
    )

    /** True when no stage would change a sample: the graph can hand the caller's samples straight back. */
    val isNeutral: Boolean
        get() = linearInputGain == 1f &&
            equalizerBands.isEmpty() &&
            clarity == null &&
            !dcBlockerEnabled &&
            !limiterEnabled &&
            !dither.enabled &&
            speed == 1.0 &&
            pitchSemitones == 0.0 &&
            transitionGain == 1f

    companion object {
        /** The widest equalizer this chain accepts: sixteen sections is a full parametric desk. */
        const val MAX_EQUALIZER_BANDS = 16

        /** Hosts keep the user preamp inside ±12 dB, as the normalization policy already does. */
        const val MAX_PREAMP_DB = 12.0

        const val MAX_TRANSITION_GAIN = 4f
    }
}
