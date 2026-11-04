package moe.rukamori.archivetune.shared.domain.dsp.tempo

import kotlin.math.pow

/**
 * Portable tempo and pitch. Tempo is a pitch-preserving WSOLA stretch. A pitch change stretches by the
 * inverse of its ratio and then reads that output back at the ratio, so the duration holds and only the
 * pitch moves. Neutral settings pass samples through untouched.
 *
 * Changing the settings restarts both stages, so a caller applies a change at a track or seek boundary.
 *
 * The stage streams: [push] takes input, [pullReady] takes whatever output is ready, and [endOfStream]
 * releases the tail. [process] and [finish] are the allocating one-shot forms the legacy portable chain uses.
 */
class TempoPitchStage(
    private val channels: Int,
    private val sampleRateHz: Int,
) {
    private var stretcher: WsolaTimeStretcher? = null
    private var resampler: VarispeedResampler? = null
    private var relay = FloatArray(0)
    private var speed = 1.0
    private var pitchSemitones = 0.0

    val isPassthrough: Boolean
        get() = stretcher == null

    /** Frames [pullReady] can produce right now. */
    val pendingFrames: Int
        get() = resampler?.pendingFrames ?: stretcher?.pendingFrames ?: 0

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

    /** Accepts [frames] frames of input. Allocation-free once the relay buffer has reached the block size. */
    fun push(input: FloatArray, frames: Int) {
        require(frames >= 0 && input.size >= frames * channels) { "input holds fewer than $frames frames" }
        stretcher?.push(input, frames)
    }

    /** Copies up to [capacityFrames] ready frames into [destination] and returns how many were copied. */
    fun pullReady(destination: FloatArray, capacityFrames: Int): Int {
        require(capacityFrames >= 0 && destination.size >= capacityFrames * channels) {
            "destination holds fewer than $capacityFrames frames"
        }
        val stretch = stretcher ?: return 0
        val pitch = resampler ?: return stretch.pull(destination, minOf(stretch.pendingFrames, capacityFrames))
        moveStretched(pitch)
        return pitch.pull(destination, minOf(pitch.pendingFrames, capacityFrames))
    }

    /** Ends the input so the held-back tail becomes pullable through further [pullReady] calls. */
    fun endOfStream() {
        val stretch = stretcher ?: return
        stretch.finish()
        resampler?.let { pitch ->
            moveStretched(pitch)
            pitch.finish()
        }
    }

    /** Processes [frames] frames and returns the output ready now. Its length differs when tempo changes. */
    fun process(input: FloatArray, frames: Int): FloatArray {
        push(input, frames)
        return pullAll()
    }

    /** Ends the stream and returns the tail held back by the stages. The stage must be reset before reuse. */
    fun finish(): FloatArray {
        endOfStream()
        return pullAll()
    }

    fun reset() {
        stretcher?.reset()
        resampler?.reset()
    }

    private fun pullAll(): FloatArray {
        val parts = ArrayList<FloatArray>()
        var total = 0
        while (true) {
            resampler?.let { moveStretched(it) }
            val ready = pendingFrames
            if (ready <= 0) break
            val block = FloatArray(ready * channels)
            val copied = pullReady(block, ready)
            if (copied <= 0) break
            parts.add(if (copied == ready) block else block.copyOf(copied * channels))
            total += copied
        }
        if (parts.isEmpty()) return FloatArray(0)
        if (parts.size == 1) return parts[0]
        val output = FloatArray(total * channels)
        var offset = 0
        for (part in parts) {
            part.copyInto(output, offset)
            offset += part.size
        }
        return output
    }

    private fun moveStretched(pitch: VarispeedResampler) {
        val stretch = stretcher ?: return
        while (true) {
            val available = minOf(stretch.pendingFrames, RELAY_CHUNK_FRAMES)
            if (available <= 0) return
            ensureRelay(available * channels)
            val copied = stretch.pull(relay, available)
            if (copied <= 0) return
            pitch.push(relay, copied)
        }
    }

    private fun ensureRelay(samples: Int) {
        if (relay.size < samples) relay = FloatArray(samples)
    }

    companion object {
        const val MIN_SPEED = 0.5
        const val MAX_SPEED = 4.0
        const val MAX_PITCH_SEMITONES = 12.0
        private const val RELAY_CHUNK_FRAMES = 1_024
    }
}
