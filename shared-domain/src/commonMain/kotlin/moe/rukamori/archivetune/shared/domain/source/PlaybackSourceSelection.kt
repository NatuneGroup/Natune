package moe.rukamori.archivetune.shared.domain.source

object PlaybackSourceSelection {
    /** The candidates that are enabled, in their given order, with repeats removed. */
    fun enabledPlaybackSources(
        candidates: List<AudioSourceType>,
        enabledSources: Set<AudioSourceType>,
    ): List<AudioSourceType> = candidates.distinct().filter { it in enabledSources }

    /**
     * Whether a cached stream from [source] may be used. It must still be enabled, and authenticity checks
     * must not have rejected that source for this song.
     */
    fun canUseCachedPlaybackSource(
        source: AudioSourceType,
        enabled: Boolean,
        rejectedSources: Set<AudioSourceType>,
    ): Boolean = enabled && source !in rejectedSources
}
