/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.shared.domain.dsp.tempo

import moe.rukamori.archivetune.shared.domain.dsp.eq.BiquadDesign
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Pitch-preserving time stretch by waveform-similarity overlap-add (WSOLA). Each 20 ms Hann grain is taken
 * near its ideal input position, at the offset whose waveform best continues the previous grain, so the
 * seams do not phase-shift. [speed] above 1 shortens the output; below 1 it lengthens it.
 *
 * Quality holds up to about 2x. Beyond that the ideal step outruns the search radius and grains stop
 * continuing one another, which shows as stutter. The first hop fades in from silence because only the
 * rising edge of the first grain covers it. [finish] emits the falling edge of the last grain.
 */
internal class WsolaTimeStretcher(
    private val channels: Int,
    sampleRateHz: Int,
    val speed: Double,
) : StreamStage {
    init {
        require(channels > 0) { "channels must be positive" }
        require(sampleRateHz >= BiquadDesign.MIN_SAMPLE_RATE_HZ) { "sampleRateHz is too low for time stretching" }
        require(speed.isFinite() && speed > 0.0) { "speed must be positive" }
    }

    private val frameLength = (sampleRateHz / GRAINS_PER_SECOND) and 1.inv()
    private val hop = frameLength / 2
    private val searchRadius = hop / 2
    private val window = DoubleArray(frameLength) { 0.5 - 0.5 * cos(2.0 * PI * it / frameLength) }
    private val grain = DoubleArray(frameLength * channels)
    private val hopSamples = FloatArray(hop * channels)
    private val input = FrameFifo(channels)
    private val output = FrameFifo(channels)
    private var grainIndex = 0L
    private var previousStart = NO_GRAIN
    private var finished = false

    override val pendingFrames: Int
        get() = output.count

    override fun push(samples: FloatArray, frames: Int) {
        check(!finished) { "push after finish" }
        input.append(samples, frames)
        advance(flushing = false)
    }

    override fun pull(destination: FloatArray, maxFrames: Int): Int = output.drainTo(destination, maxFrames)

    override fun finish() {
        if (finished) return
        finished = true
        advance(flushing = true)
        // No later grain completes the last hop, so it is emitted as it stands.
        if (grainIndex > 0L) emitHop()
    }

    override fun reset() {
        input.clear()
        output.clear()
        grain.fill(0.0)
        grainIndex = 0L
        previousStart = NO_GRAIN
        finished = false
    }

    private fun advance(flushing: Boolean) {
        while (true) {
            val nominal = nominalStart(grainIndex)
            if (flushing) {
                if (nominal >= input.endFrame) return
            } else if (input.endFrame < nominal + searchRadius + frameLength) {
                return
            }
            val start = if (previousStart == NO_GRAIN) nominal else bestStart(nominal)
            addGrain(start)
            emitHop()
            previousStart = start
            grainIndex++
            input.discardBefore(minOf(start + hop, nominalStart(grainIndex) - searchRadius))
        }
    }

    private fun nominalStart(index: Long): Long = (index.toDouble() * hop * speed).roundToLong()

    private fun bestStart(nominal: Long): Long {
        val target = previousStart + hop
        val targetEnergy = energy(target)
        if (targetEnergy <= SILENCE_ENERGY) return nominal
        var best = nominal
        var bestScore = Double.NEGATIVE_INFINITY
        for (offset in -searchRadius..searchRadius) {
            val candidate = nominal + offset
            if (candidate < 0L) continue
            val score = similarity(candidate, target, targetEnergy)
            if (score > bestScore) {
                bestScore = score
                best = candidate
            }
        }
        return best
    }

    private fun energy(start: Long): Double {
        var sum = 0.0
        for (i in 0 until hop) {
            for (channel in 0 until channels) {
                val x = input.sample(start + i, channel).toDouble()
                sum += x * x
            }
        }
        return sum
    }

    private fun similarity(candidate: Long, target: Long, targetEnergy: Double): Double {
        var cross = 0.0
        var candidateEnergy = 0.0
        for (i in 0 until hop) {
            for (channel in 0 until channels) {
                val a = input.sample(candidate + i, channel).toDouble()
                cross += a * input.sample(target + i, channel)
                candidateEnergy += a * a
            }
        }
        val norm = sqrt(candidateEnergy * targetEnergy)
        return if (norm <= SILENCE_ENERGY) 0.0 else cross / norm
    }

    private fun addGrain(start: Long) {
        for (i in 0 until frameLength) {
            val weight = window[i]
            for (channel in 0 until channels) {
                grain[i * channels + channel] += input.sample(start + i, channel) * weight
            }
        }
    }

    /** Emits the first hop, which no later grain can change, then shifts the overlap buffer down. */
    private fun emitHop() {
        for (i in 0 until hop * channels) hopSamples[i] = grain[i].toFloat()
        output.append(hopSamples, hop)
        val shift = hop * channels
        for (i in 0 until (frameLength - hop) * channels) grain[i] = grain[i + shift]
        grain.fill(0.0, (frameLength - hop) * channels, grain.size)
    }

    private companion object {
        const val GRAINS_PER_SECOND = 50
        const val NO_GRAIN = -1L
        const val SILENCE_ENERGY = 1e-12
    }
}
