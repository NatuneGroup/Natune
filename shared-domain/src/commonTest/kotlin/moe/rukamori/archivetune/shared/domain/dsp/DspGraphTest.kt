package moe.rukamori.archivetune.shared.domain.dsp

import kotlin.math.abs
import kotlin.math.pow
import moe.rukamori.archivetune.shared.domain.dsp.bitperfect.SoftwareStagePlan
import moe.rukamori.archivetune.shared.domain.dsp.eq.EqualizerBand
import moe.rukamori.archivetune.shared.domain.dsp.eq.EqualizerBandType
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The contract every adapter relies on: one ordered chain, gated by the plan, bit-exact when it is not needed. */
class DspGraphTest {
    private val rate = 48_000
    private val channels = 2

    private fun plan(
        normalization: Boolean = false,
        equalizer: Boolean = false,
        tempoPitch: Boolean = false,
    ) = SoftwareStagePlan(
        bypass = false,
        normalization = normalization,
        equalizer = equalizer,
        tempoPitch = tempoPitch,
        resampling = false,
        twoDeckMixing = false,
        silenceSkipping = false,
        softwareVolume = false,
    )

    private val bypassPlan = SoftwareStagePlan(
        bypass = true,
        normalization = false,
        equalizer = false,
        tempoPitch = false,
        resampling = false,
        twoDeckMixing = false,
        silenceSkipping = false,
        softwareVolume = false,
    )

    private fun render(graph: DspGraph, input: FloatArray, frames: Int): FloatArray {
        val capacity = graph.requiredOutputCapacity(frames)
        val destination = FloatArray(capacity * channels)
        val copied = graph.process(input, frames, destination, capacity)
        graph.finish()
        val tail = FloatArray(graph.requiredOutputCapacity(0) * channels)
        val extra = graph.pull(tail, tail.size / channels)
        assertEquals(0, graph.pendingFrames)
        return DspTestSignals.concat(listOf(destination.copyOf(copied * channels), tail.copyOf(extra * channels)))
    }

    @Test
    fun bypassedPlanReturnsTheCallersSamplesBitExactly() {
        val graph = DspGraph(rate, channels)
        graph.apply(
            DspSettings(
                linearGain = 0.4f,
                equalizerBands = listOf(EqualizerBand(EqualizerBandType.PEAKING, 1_000.0, 9.0)),
                speed = 1.5,
                pitchSemitones = 3.0,
                dither = DspDither(enabled = true),
            ),
            bypassPlan,
        )
        assertTrue(graph.isBypassed)
        assertFalse(graph.isTempoEngaged)

        val input = DspTestSignals.sine(1_000.0, rate, frames = 4_096, channels = channels)
        val destination = FloatArray(4_096 * channels)
        assertEquals(4_096, graph.process(input, 4_096, destination, 4_096))
        assertContentEquals(input, destination)
        graph.finish()
        assertEquals(0, graph.pendingFrames)
    }

    @Test
    fun neutralSettingsPassTheStreamThroughUnchanged() {
        val graph = DspGraph(rate, channels)
        graph.apply(DspSettings(), plan(normalization = true, equalizer = true))
        assertFalse(graph.isBypassed)

        val input = DspTestSignals.sine(440.0, rate, frames = 2_048, channels = channels, amplitude = 0.9)
        val destination = FloatArray(2_048 * channels)
        assertEquals(2_048, graph.process(input, 2_048, destination, 2_048))
        assertContentEquals(input, destination)
    }

    @Test
    fun normalizationAndTheFullScaleClampBoundTheOutput() {
        val graph = DspGraph(rate, channels)
        graph.apply(DspSettings(linearGain = 4f), plan(normalization = true))

        val input = DspTestSignals.sine(1_000.0, rate, frames = 1_024, channels = channels, amplitude = 0.9)
        val output = render(graph, input, 1_024)
        for (index in output.indices) {
            assertTrue(abs(output[index]) <= 1f, "sample $index reached ${output[index]}")
        }
        assertTrue(output.any { it == 1f || it == -1f }, "the clamp never engaged")
    }

    @Test
    fun limiterNeverExceedsItsCeiling() {
        val ceilingDb = -0.5
        val ceiling = 10.0.pow(ceilingDb / 20.0)
        val graph = DspGraph(rate, channels)
        graph.apply(
            DspSettings(linearGain = 8f, limiterEnabled = true, limiterCeilingDb = ceilingDb),
            plan(normalization = true),
        )

        val frames = rate
        val input = DspTestSignals.sine(220.0, rate, frames = frames, channels = channels, amplitude = 0.9)
        val output = render(graph, input, frames)

        var peak = 0.0
        for (frame in frames / 2 until frames) {
            for (channel in 0 until channels) {
                val value = abs(output[frame * channels + channel].toDouble())
                if (value > peak) peak = value
            }
        }
        assertTrue(peak <= ceiling + 1e-3, "peak $peak exceeded the $ceiling ceiling")
        assertTrue(peak > 0.7, "the limiter left the signal at $peak, so it never acted")
    }

    @Test
    fun dcBlockerRemovesAConstantOffset() {
        val graph = DspGraph(rate, channels)
        graph.apply(DspSettings(dcBlockerEnabled = true), plan())

        val frames = rate
        val input = FloatArray(frames * channels) { 0.5f }
        val output = render(graph, input, frames)

        var sum = 0.0
        var count = 0
        for (frame in frames - frames / 5 until frames) {
            for (channel in 0 until channels) {
                sum += abs(output[frame * channels + channel].toDouble())
                count++
            }
        }
        assertTrue(sum / count < 1e-3, "the offset left ${sum / count} behind")
    }

    @Test
    fun seededDitherIsDeterministic() {
        val settings = DspSettings(dither = DspDither(enabled = true, bitDepth = 16, seed = 12_345))
        val input = DspTestSignals.sine(1_000.0, rate, frames = 512, channels = channels, amplitude = 0.3)

        val first = render(DspGraph(rate, channels).also { it.apply(settings, plan()) }, input.copyOf(), 512)
        val second = render(DspGraph(rate, channels).also { it.apply(settings, plan()) }, input.copyOf(), 512)
        assertContentEquals(first, second, "the same seed produced different output")

        val other = DspSettings(dither = DspDither(enabled = true, bitDepth = 16, seed = 999))
        val third = render(DspGraph(rate, channels).also { it.apply(other, plan()) }, input.copyOf(), 512)
        assertFalse(first.contentEquals(third), "a different seed produced identical output")
        assertFalse(first.contentEquals(input), "dither left the samples untouched")
    }

    @Test
    fun tempoStretchesToTheRequestedLength() {
        val graph = DspGraph(rate, channels)
        graph.apply(DspSettings(speed = 2.0), plan(tempoPitch = true))
        assertTrue(graph.isTempoEngaged)

        val frames = rate * 2
        val input = DspTestSignals.sine(440.0, rate, frames = frames, channels = channels)
        val output = render(graph, input, frames)
        val outputFrames = output.size / channels
        assertTrue(abs(outputFrames - rate) <= rate / 20, "2x tempo produced $outputFrames frames, expected $frames / 2")
    }

    @Test
    fun pitchShiftsWithoutChangingTheLength() {
        val graph = DspGraph(rate, channels)
        graph.apply(DspSettings(pitchSemitones = 12.0), plan(tempoPitch = true))
        assertTrue(graph.isTempoEngaged)

        val frames = rate
        val input = DspTestSignals.sine(440.0, rate, frames = frames, channels = channels)
        val output = render(graph, input, frames)
        val outputFrames = output.size / channels
        assertTrue(abs(outputFrames - frames) <= frames / 20, "pitch shift changed the length to $outputFrames frames")
        val shifted = DspTestSignals.positiveCrossingFrequency(output, channels, rate)
        val before = positiveCrossing(input)
        assertTrue(abs(before * 2.0 - shifted) <= 40.0, "12 semitones moved $before Hz to $shifted Hz")
    }

    @Test
    fun configuringANewFormatRebuildsEveryStage() {
        val graph = DspGraph(rate, channels)
        graph.apply(DspSettings(equalizerBands = listOf(EqualizerBand(EqualizerBandType.PEAKING, 1_000.0, 6.0))), plan(equalizer = true))
        graph.process(DspTestSignals.sine(1_000.0, rate, frames = 512, channels = channels), 512, FloatArray(1_024), 512)

        graph.configure(DspFormat(44_100, 1))
        assertEquals(DspFormat(44_100, 1), graph.format)
        assertEquals(0, graph.pendingFrames)

        graph.apply(DspSettings(linearGain = 0.5f), plan(normalization = true))
        val frames = 1_024
        val input = FloatArray(frames) { 0.4f }
        val destination = FloatArray(frames)
        assertEquals(frames, graph.process(input, frames, destination, frames))
        for (index in destination.indices) {
            assertTrue(abs(destination[index] - 0.2f) < 1e-6, "sample $index was ${destination[index]}")
        }
    }

    private fun positiveCrossing(samples: FloatArray): Double =
        DspTestSignals.positiveCrossingFrequency(samples, channels, rate)
}
