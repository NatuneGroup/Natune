package moe.rukamori.archivetune.shared.domain.dsp.tempo

import kotlin.math.pow

/**
 * Portable tempo and pitch. Tempo is a pitch-preserving WSOLA stretch. A pitch change stretches by the
 * inverse of its ratio and then reads that output back at the ratio, so the duration holds and only the
 * pitch moves. Neutral settings pass samples through untouched.
 *
 * Changing the settings restarts both stages, so a caller applies a change at a track or seek boundary.
 */
class TempoPitchStage(
    private val channels: Int,
    private val sampleRateHz: Int,
) {
    private var stretcher: WsolaTimeStretcher? = null
    private var resampler: VarispeedResampler? = null
    private var speed = 1.0
    private var pitchSemitones = 0.0

    val isPassthrough: Boolean
        get() = stretcher == null

    fun configure(speed: Double, pitchSemitones: Double) {
        require(speed.isFinite() && speed in MIN_SPEED..MAX_SPEED) { "speed must be within $MIN_SPEED..$MAX_SPEED" }
        require(pitchSemitones.isFinite() && pitchSemitones in -MAX_PITCH_SEMITONES..MAX_PITCH_SEMITONES) {
            "pitch must be within ±$MAX_PITCH_SEMITONES semitones"
        }
        if (speed == this.speed && pitchSemitones == this.pitchSemitones) return
        this.speed = speed
        this.pitchSemitones = pitchSemitones
        if (speed == 1.0 && pitchSemitones == 0.0) {
            stretcher = null
            resampler = null
            return
        }
        val ratio = 2.0.pow(pitchSemitones / 12.0)
        stretcher = WsolaTimeStretcher(channels, sampleRateHz, speed / ratio)
        resampler = if (pitchSemitones == 0.0) null else VarispeedResampler(channels, ratio)
    }

    /** Processes [frames] frames and returns the output ready now. Its length differs when tempo changes. */
    fun process(input: FloatArray, frames: Int): FloatArray {
        require(frames >= 0 && input.size >= frames * channels) { "input holds fewer than $frames frames" }
        val stretch = stretcher ?: return input.copyOf(frames * channels)
        stretch.push(input, frames)
        return relay(stretch, flush = false)
    }

    /** Ends the stream and returns the tail held back by the stages. The stage must be reset before reuse. */
    fun finish(): FloatArray {
        val stretch = stretcher ?: return FloatArray(0)
        stretch.finish()
        return relay(stretch, flush = true)
    }

    fun reset() {
        stretcher?.reset()
        resampler?.reset()
    }

    private fun relay(stretch: WsolaTimeStretcher, flush: Boolean): FloatArray {
        val stretched = pullAll(stretch)
        val pitch = resampler ?: return stretched
        if (stretched.isNotEmpty()) pitch.push(stretched, stretched.size / channels)
        if (flush) pitch.finish()
        return pullAll(pitch)
    }

    private fun pullAll(stage: StreamStage): FloatArray {
        val frames = stage.pendingFrames
        val samples = FloatArray(frames * channels)
        stage.pull(samples, frames)
        return samples
    }

    companion object {
        const val MIN_SPEED = 0.5
        const val MAX_SPEED = 4.0
        const val MAX_PITCH_SEMITONES = 12.0
    }
}
