package moe.rukamori.archivetune.playback

import moe.rukamori.archivetune.shared.domain.settings.audioNormalizationApplies

/**
 * Whether MusicService may fold a track's normalization gain into the player volume.
 *
 * The single Audio processing switch covers every stage the software chain renders, including the
 * normalization gain; while it is off, the decoder's samples go to the output unchanged and the stored
 * value stays where it is, so switching processing back on restores the same gain without a re-resolve.
 * A bit-perfect sink hands volume to the DAC path, so normalization sits out there regardless.
 */
object NormalizationGainPolicy {
    fun applies(
        requested: Boolean,
        bitPerfectSinkActive: Boolean,
        processingEnabled: Boolean,
    ): Boolean = processingEnabled && audioNormalizationApplies(requested, bitPerfectSinkActive)
}
