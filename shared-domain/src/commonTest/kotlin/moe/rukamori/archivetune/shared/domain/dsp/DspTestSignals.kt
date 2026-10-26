/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.shared.domain.dsp

import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/** Signal builders and measurements shared by the DSP tests. Everything is interleaved float PCM. */
internal object DspTestSignals {
    fun sine(frequencyHz: Double, sampleRateHz: Int, frames: Int, channels: Int, amplitude: Double = 0.5): FloatArray {
        val samples = FloatArray(frames * channels)
        for (frame in 0 until frames) {
            val value = (amplitude * sin(2.0 * PI * frequencyHz * frame / sampleRateHz)).toFloat()
            for (channel in 0 until channels) samples[frame * channels + channel] = value
        }
        return samples
    }

    /** Root-mean-square of channel 0 between two frame indices. */
    fun rms(samples: FloatArray, channels: Int, fromFrame: Int, toFrame: Int): Double {
        var sum = 0.0
        for (frame in fromFrame until toFrame) {
            val x = samples[frame * channels].toDouble()
            sum += x * x
        }
        return sqrt(sum / (toFrame - fromFrame))
    }

    /** Estimates the frequency of channel 0 from its positive-going zero crossings in the middle of the signal. */
    fun positiveCrossingFrequency(samples: FloatArray, channels: Int, sampleRateHz: Int): Double {
        val frames = samples.size / channels
        val from = frames / 5
        val to = frames - frames / 5
        var crossings = 0
        for (frame in from + 1 until to) {
            val previous = samples[(frame - 1) * channels]
            val current = samples[frame * channels]
            if (previous <= 0f && current > 0f) crossings++
        }
        return crossings.toDouble() * sampleRateHz / (to - from)
    }

    fun concat(parts: List<FloatArray>): FloatArray {
        val out = FloatArray(parts.sumOf { it.size })
        var offset = 0
        for (part in parts) {
            part.copyInto(out, offset)
            offset += part.size
        }
        return out
    }

    fun assertAllZero(samples: FloatArray) {
        for (index in samples.indices) {
            kotlin.test.assertEquals(0f, samples[index], 0f, "sample $index should be silent")
        }
    }
}
