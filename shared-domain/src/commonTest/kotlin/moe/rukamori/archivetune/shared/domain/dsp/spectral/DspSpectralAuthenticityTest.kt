/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.shared.domain.dsp.spectral

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DspSpectralAuthenticityTest {
    @Test
    fun hiResBandLimitedSignalIsSuspectedUpscaledAtCdBandEdge() {
        val assessment = analyze(bandLimitedHiRes20s, sampleRateHz = HI_RES_RATE_HZ)

        assertEquals(SpectralVerdict.SUSPECTED_UPSCALED, assessment.verdict)
        assertEquals(22_050, assessment.cutoffFrequencyHz)
        assertTrue(assessment.confidence >= SpectralAuthenticityAnalyzer.MIN_REJECTION_CONFIDENCE)
        assertTrue(assessment.activeWindows > 0)
        assertTrue(SpectralAuthenticityPolicy.shouldReject(assessment, rejectSuspectedUpscaled = true))
        assertFalse(SpectralAuthenticityPolicy.shouldReject(assessment, rejectSuspectedUpscaled = false))
    }

    @Test
    fun hiResFullBandSignalIsNotSuspectedUpscaled() {
        val assessment = analyze(fullBandHiRes20s, sampleRateHz = HI_RES_RATE_HZ)

        assertEquals(SpectralVerdict.NO_SUSPICIOUS_CUTOFF, assessment.verdict)
        assertFalse(SpectralAuthenticityPolicy.shouldReject(assessment, rejectSuspectedUpscaled = true))
    }

    @Test
    fun hiResDigitalSilenceIsUnknownWithZeroConfidence() {
        val silence = FloatArray(HI_RES_RATE_HZ * 20 * 2)

        val assessment = analyze(silence, sampleRateHz = HI_RES_RATE_HZ)

        assertEquals(SpectralVerdict.UNKNOWN, assessment.verdict)
        assertEquals(0.0, assessment.confidence)
    }

    @Test
    fun tooShortHiResSignalIsUnknown() {
        // Fewer frames than MIN_ANALYSIS_FRAMES (12 windows): the analyzer must not guess.
        val short = interleave(bandLimitedPeriodHiRes, frames = 8_192 * 4, channelCount = 2)

        val assessment = analyze(short, sampleRateHz = HI_RES_RATE_HZ)

        assertEquals(SpectralVerdict.UNKNOWN, assessment.verdict)
    }

    @Test
    fun signalNotClaimedLosslessIsUnknown() {
        val assessment =
            analyze(bandLimitedHiRes20s, sampleRateHz = HI_RES_RATE_HZ, claimedLossless = false)

        assertEquals(SpectralVerdict.UNKNOWN, assessment.verdict)
    }

    @Test
    fun advertisedRateBelowHiResThresholdRunsStandardPathWithoutThrowing() {
        // Advertised 44100 is below the hi-res threshold, so the standard-rate codec-cutoff path runs on
        // the 96 kHz PCM. That path inspects at most MAX_ANALYSIS_FRAMES frames at its own window size.
        val assessment =
            SpectralAuthenticityAnalyzer.analyzeInterleavedPcm(
                samples = interleave(bandLimitedPeriodHiRes, frames = 480_000, channelCount = 2),
                pcmSampleRateHz = HI_RES_RATE_HZ,
                channelCount = 2,
                advertisedSampleRateHz = 44_100,
                claimedLossless = true,
            )

        assertTrue(assessment.verdict in SpectralVerdict.entries)
        assertTrue(assessment.activeWindows <= MAX_WINDOWS)
    }

    @Test
    fun standardRateLowpassedSignalIsSuspectedAtCodecCandidateCutoff() {
        // Tones stop at 15 kHz, so 15.5 kHz..20.5 kHz is empty. Every candidate whose pre-band still
        // holds tones sees ~30 dB or more attenuation above it, so the verdict must be SUSPECTED_UPSCALED
        // and the reported cutoff must be one of the candidates (the lowest candidate wins ties).
        val assessment =
            SpectralAuthenticityAnalyzer.analyzeInterleavedPcm(
                samples = interleave(lowpassed44k15kPeriod, frames = 441_000, channelCount = 2),
                pcmSampleRateHz = 44_100,
                channelCount = 2,
                advertisedSampleRateHz = 44_100,
                claimedLossless = true,
            )

        assertEquals(SpectralVerdict.SUSPECTED_UPSCALED, assessment.verdict)
        assertContains(CODEC_CANDIDATES_HZ, assessment.cutoffFrequencyHz)
    }

    @Test
    fun activeWindowsStayBoundedForLongHiResInput() {
        // Two minutes of 96 kHz stereo (about 11.5 M frames). The analyzer reads only the first
        // MAX_ANALYSIS_FRAMES, so the window count must not grow with duration. Ten minutes was
        // considered but needs about 460 MB of float samples, which is too heavy for the JVM test heap.
        val twoMinutes = interleave(bandLimitedPeriodHiRes, frames = HI_RES_RATE_HZ * 120, channelCount = 2)

        val assessment = analyze(twoMinutes, sampleRateHz = HI_RES_RATE_HZ)

        assertTrue(assessment.activeWindows <= MAX_WINDOWS)
    }

    @Test
    fun cancellationFromCheckActivePropagatesUnchanged() {
        assertFailsWith<IllegalStateException> {
            SpectralAuthenticityAnalyzer.analyzeInterleavedPcm(
                samples = bandLimitedHiRes20s,
                pcmSampleRateHz = HI_RES_RATE_HZ,
                channelCount = 2,
                advertisedSampleRateHz = HI_RES_RATE_HZ,
                claimedLossless = true,
                checkActive = { throw IllegalStateException("cancelled") },
            )
        }
    }

    @Test
    fun policyRejectsOnlyAtOrAboveMinimumConfidenceWhenFlagIsOn() {
        val below = SpectralAssessment(SpectralVerdict.SUSPECTED_UPSCALED, confidence = 0.91, cutoffFrequencyHz = 22_050)
        val atThreshold = below.copy(confidence = 0.92)

        assertFalse(SpectralAuthenticityPolicy.shouldReject(below, rejectSuspectedUpscaled = true))
        assertTrue(SpectralAuthenticityPolicy.shouldReject(atThreshold, rejectSuspectedUpscaled = true))
        assertFalse(SpectralAuthenticityPolicy.shouldReject(atThreshold, rejectSuspectedUpscaled = false))
    }

    private fun analyze(
        samples: FloatArray,
        sampleRateHz: Int,
        claimedLossless: Boolean = true,
    ): SpectralAssessment =
        SpectralAuthenticityAnalyzer.analyzeInterleavedPcm(
            samples = samples,
            pcmSampleRateHz = sampleRateHz,
            channelCount = 2,
            advertisedSampleRateHz = sampleRateHz,
            claimedLossless = claimedLossless,
        )

    private companion object {
        const val HI_RES_RATE_HZ = 96_000
        const val MAX_WINDOWS = SpectralAuthenticityAnalyzer.MAX_ANALYSIS_FRAMES /
            SpectralAuthenticityAnalyzer.ANALYSIS_WINDOW_FRAMES
        val CODEC_CANDIDATES_HZ = (15_500..20_500 step 500).toList()

        // One-second periods: integer-Hz tones make the signal exactly periodic every sampleRate frames,
        // so long arrays are filled by index rather than by 3.84 M-plus sin calls per 20 s.
        val bandLimitedPeriodHiRes by lazy { tonePeriod(HI_RES_RATE_HZ, lowHz = 4_000, highHz = 21_000, toneCount = 40) }
        val fullBandPeriodHiRes by lazy { tonePeriod(HI_RES_RATE_HZ, lowHz = 4_000, highHz = 44_000, toneCount = 40) }
        val lowpassed44k15kPeriod by lazy { tonePeriod(44_100, lowHz = 4_000, highHz = 15_000, toneCount = 40) }

        val bandLimitedHiRes20s by lazy { interleave(bandLimitedPeriodHiRes, frames = HI_RES_RATE_HZ * 20, channelCount = 2) }
        val fullBandHiRes20s by lazy { interleave(fullBandPeriodHiRes, frames = HI_RES_RATE_HZ * 20, channelCount = 2) }
    }
}

private fun tonePeriod(sampleRateHz: Int, lowHz: Int, highHz: Int, toneCount: Int): DoubleArray {
    val period = DoubleArray(sampleRateHz)
    var seed = 0x2545F4914F6CDD1DL
    val amplitude = 0.8 / toneCount
    for (tone in 0 until toneCount) {
        // Fixed LCG so phases are deterministic across runs.
        seed = seed * 6364136223846793005L + 1442695040888963407L
        val phase = ((seed ushr 11).toDouble() / (1L shl 53).toDouble()) * 2.0 * PI
        val frequencyHz = lowHz + tone * (highHz - lowHz) / (toneCount - 1)
        val step = 2.0 * PI * frequencyHz / sampleRateHz
        for (index in 0 until sampleRateHz) {
            period[index] += amplitude * sin(step * index + phase)
        }
    }
    return period
}

private fun interleave(period: DoubleArray, frames: Int, channelCount: Int): FloatArray {
    val out = FloatArray(frames * channelCount)
    for (frame in 0 until frames) {
        val value = period[frame % period.size].toFloat()
        val offset = frame * channelCount
        for (channel in 0 until channelCount) {
            out[offset + channel] = value
        }
    }
    return out
}
