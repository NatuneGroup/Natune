/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.shared.domain.dsp.spectral

enum class SpectralVerdict {
    UNKNOWN,
    NO_SUSPICIOUS_CUTOFF,
    SUSPECTED_UPSCALED,
}

data class SpectralAssessment(
    val verdict: SpectralVerdict,
    val confidence: Double,
    val cutoffFrequencyHz: Int? = null,
    val activeWindows: Int = 0,
)

object SpectralAuthenticityPolicy {
    fun shouldReject(
        assessment: SpectralAssessment,
        rejectSuspectedUpscaled: Boolean,
    ): Boolean =
        rejectSuspectedUpscaled &&
            assessment.verdict == SpectralVerdict.SUSPECTED_UPSCALED &&
            assessment.confidence >= SpectralAuthenticityAnalyzer.MIN_REJECTION_CONFIDENCE
}
