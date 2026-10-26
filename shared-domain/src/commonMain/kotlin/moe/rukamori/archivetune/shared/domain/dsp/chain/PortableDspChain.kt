package moe.rukamori.archivetune.shared.domain.dsp.chain

import moe.rukamori.archivetune.shared.domain.dsp.bitperfect.SoftwareStagePlan
import moe.rukamori.archivetune.shared.domain.dsp.eq.EqualizerBand
import moe.rukamori.archivetune.shared.domain.dsp.eq.PortableEqualizer
import moe.rukamori.archivetune.shared.domain.dsp.tempo.TempoPitchStage

/** Values the software chain applies when its stages are enabled by the plan. */
data class DspSettings(
    val linearGain: Float = 1f,
    val equalizerBands: List<EqualizerBand> = emptyList(),
    val speed: Double = 1.0,
    val pitchSemitones: Double = 0.0,
) {
    init {
        require(linearGain.isFinite() && linearGain > 0f) { "linearGain must be positive" }
    }
}

/**
 * Portable software chain for decoded float PCM, in the order the native adapter design requires:
 * normalization, then EQ, then tempo/pitch, then full-scale clamp. A bypassed plan returns the caller's
 * samples unchanged and clears history, so the next engaged block cannot inherit filter state.
 *
 * Call [reset] before reusing a chain after [finish].
 */
class PortableDspChain(
    private val sampleRateHz: Int,
    private val channels: Int,
) {
    private val equalizer = PortableEqualizer(sampleRateHz, channels)
    private val tempoPitch = TempoPitchStage(channels, sampleRateHz)
    private var gain = 1f

    var isBypassed: Boolean = false
        private set

    init {
        require(channels > 0) { "channels must be positive" }
    }

    fun apply(settings: DspSettings, plan: SoftwareStagePlan) {
        if (plan.bypass) {
            if (!isBypassed) reset()
            isBypassed = true
            return
        }
        isBypassed = false
        gain = if (plan.normalization) settings.linearGain else 1f
        equalizer.setBands(if (plan.equalizer) settings.equalizerBands else emptyList())
        tempoPitch.configure(
            speed = if (plan.tempoPitch) settings.speed else 1.0,
            pitchSemitones = if (plan.tempoPitch) settings.pitchSemitones else 0.0,
        )
    }

    /** Processes [frames] frames and returns the frames ready now. Tempo changes make the count differ. */
    fun process(input: FloatArray, frames: Int): FloatArray {
        require(frames >= 0 && input.size >= frames * channels) { "input holds fewer than $frames frames" }
        if (isBypassed) return input.copyOf(frames * channels)
        val samples = input.copyOf(frames * channels)
        if (gain != 1f) {
            for (i in samples.indices) samples[i] *= gain
        }
        equalizer.process(samples, frames)
        return clamp(tempoPitch.process(samples, frames))
    }

    /** Ends the stream and returns the tail that tempo/pitch held back. */
    fun finish(): FloatArray {
        if (isBypassed) return FloatArray(0)
        return clamp(tempoPitch.finish())
    }

    /** Clears filter and time-stretch history at a seek or any other discontinuity. */
    fun reset() {
        equalizer.reset()
        tempoPitch.reset()
    }

    private fun clamp(samples: FloatArray): FloatArray {
        for (i in samples.indices) samples[i] = samples[i].coerceIn(-1f, 1f)
        return samples
    }
}
