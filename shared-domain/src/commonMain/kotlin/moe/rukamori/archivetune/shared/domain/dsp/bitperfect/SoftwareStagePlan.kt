/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.shared.domain.dsp.bitperfect

/** The user's software settings for one track, before the bit-perfect gate narrows them. */
data class SoftwareStageRequest(
    val normalizationEnabled: Boolean,
    val equalizerEnabled: Boolean,
    val speed: Double,
    val pitchSemitones: Double,
    val silenceSkippingEnabled: Boolean,
    val twoDeckMixingEnabled: Boolean,
)

/** Which software stages run for a track. A bypassed plan runs none of them. */
data class SoftwareStagePlan(
    val bypass: Boolean,
    val normalization: Boolean,
    val equalizer: Boolean,
    val tempoPitch: Boolean,
    val resampling: Boolean,
    val twoDeckMixing: Boolean,
    val silenceSkipping: Boolean,
    val softwareVolume: Boolean,
) {
    companion object {
        fun resolve(decision: BitPerfectDecision, request: SoftwareStageRequest): SoftwareStagePlan {
            if (decision.bypass) {
                return SoftwareStagePlan(
                    bypass = true,
                    normalization = false,
                    equalizer = false,
                    tempoPitch = false,
                    resampling = false,
                    twoDeckMixing = false,
                    silenceSkipping = false,
                    softwareVolume = decision.softwareVolumeActive,
                )
            }
            // A requested but unverified route keeps the user's EQ and normalization, but runs at unity
            // speed and pitch with no silence skipping and no mixing, as the shipped gate pins them.
            return SoftwareStagePlan(
                bypass = false,
                normalization = request.normalizationEnabled,
                equalizer = request.equalizerEnabled,
                tempoPitch = !decision.requested && (request.speed != 1.0 || request.pitchSemitones != 0.0),
                resampling = decision.resamplerActive,
                twoDeckMixing = request.twoDeckMixingEnabled && !decision.requested,
                silenceSkipping = request.silenceSkippingEnabled && !decision.requested,
                softwareVolume = false,
            )
        }
    }
}
