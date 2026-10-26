package moe.rukamori.archivetune.shared.domain.dsp.tempo

import moe.rukamori.archivetune.shared.domain.dsp.DspTestSignals
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DspTempoPitchTest {
    private val rate = 48_000
    private val channels = 2
    private val frames = 2 * rate

    /** The WSOLA hop at 48 kHz is 480 frames (10 ms). Length checks allow a few hops for the edges. */
    private val hop = 480

    /** Pushes [input] through [stage] in blocks, finishes it, and returns everything it produced. */
    private fun run(stage: TempoPitchStage, input: FloatArray, blockFrames: Int = 1_000): FloatArray {
        val parts = ArrayList<FloatArray>()
        val inputFrames = input.size / channels
        var offset = 0
        while (offset < inputFrames) {
            val block = minOf(blockFrames, inputFrames - offset)
            parts.add(stage.process(input.copyOfRange(offset * channels, (offset + block) * channels), block))
            offset += block
        }
        parts.add(stage.finish())
        return DspTestSignals.concat(parts)
    }

    @Test
    fun neutralSettingsPassSamplesThroughUntouched() {
        val stage = TempoPitchStage(channels, rate)
        stage.configure(speed = 1.0, pitchSemitones = 0.0)
        assertTrue(stage.isPassthrough)

        val input = DspTestSignals.sine(440.0, rate, frames = 2_048, channels = channels)
        assertContentEquals(input, stage.process(input, 2_048))
        assertEquals(0, stage.finish().size)
    }

    @Test
    fun doubleSpeedShortensAndHalfSpeedLengthensTheStream() {
        val input = DspTestSignals.sine(440.0, rate, frames, channels)

        val fast = TempoPitchStage(channels, rate).apply { configure(speed = 2.0, pitchSemitones = 0.0) }
        val fastFrames = run(fast, input).size / channels
        assertTrue(abs(fastFrames - frames / 2) <= 2 * hop, "2x speed produced $fastFrames frames")

        val slow = TempoPitchStage(channels, rate).apply { configure(speed = 0.5, pitchSemitones = 0.0) }
        val slowFrames = run(slow, input).size / channels
        assertTrue(abs(slowFrames - frames * 2) <= 2 * hop, "0.5x speed produced $slowFrames frames")
    }

    @Test
    fun octaveUpRaisesThePitchAndKeepsTheDuration() {
        val input = DspTestSignals.sine(440.0, rate, frames, channels)
        val stage = TempoPitchStage(channels, rate).apply { configure(speed = 1.0, pitchSemitones = 12.0) }
        val output = run(stage, input)

        // Pitch shifting stretches then varispeeds, so the edges add up to a few hops.
        assertTrue(abs(output.size / channels - frames) <= 4 * hop, "length ${output.size / channels}")
        assertEquals(880.0, DspTestSignals.positiveCrossingFrequency(output, channels, rate), 880.0 * 0.03)
    }

    @Test
    fun octaveDownLowersThePitchAndKeepsTheDuration() {
        val input = DspTestSignals.sine(440.0, rate, frames, channels)
        val stage = TempoPitchStage(channels, rate).apply { configure(speed = 1.0, pitchSemitones = -12.0) }
        val output = run(stage, input)

        assertTrue(abs(output.size / channels - frames) <= 4 * hop, "length ${output.size / channels}")
        assertEquals(220.0, DspTestSignals.positiveCrossingFrequency(output, channels, rate), 220.0 * 0.03)
    }

    @Test
    fun resetMakesTheStageRepeatable() {
        val input = DspTestSignals.sine(440.0, rate, frames = 24_000, channels = channels)
        val stage = TempoPitchStage(channels, rate).apply { configure(speed = 1.5, pitchSemitones = 3.0) }
        val first = run(stage, input)

        stage.reset()
        assertContentEquals(first, run(stage, input))
    }

    @Test
    fun speedAndPitchOutsideTheSupportedRangeAreRejected() {
        val stage = TempoPitchStage(channels, rate)
        assertFailsWith<IllegalArgumentException> { stage.configure(speed = 5.0, pitchSemitones = 0.0) }
        assertFailsWith<IllegalArgumentException> { stage.configure(speed = 1.0, pitchSemitones = 13.0) }
        assertFailsWith<IllegalArgumentException> { stage.configure(speed = Double.NaN, pitchSemitones = 0.0) }
    }
}
