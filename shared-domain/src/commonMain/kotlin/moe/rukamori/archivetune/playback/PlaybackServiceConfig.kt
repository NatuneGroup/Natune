package moe.rukamori.archivetune.playback

/**
 * Player-builder flags the service applies identically to the primary and secondary (crossfade)
 * players. Pinned as constants so the unit tests can hold the values regardless of which builder
 * line a later refactor touches.
 */
internal object PlaybackServiceConfig {
    /**
     * Unplugging headphones, or the output route otherwise becoming noisy, pauses playback rather
     * than blasting the speaker — Media3's audio-becoming-noisy handling, always on.
     */
    const val HANDLE_AUDIO_BECOMING_NOISY = true
}
