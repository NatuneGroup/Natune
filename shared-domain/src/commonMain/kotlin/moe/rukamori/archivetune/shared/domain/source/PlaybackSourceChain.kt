package moe.rukamori.archivetune.shared.domain.source

/**
 * One immutable read of the user's source settings: the order they arranged plus the sources
 * currently enabled. Each resolution derives its chain from one snapshot, so the order and the toggles
 * cannot disagree within a single decision.
 */
data class PlaybackSourceSnapshot(
    val order: List<AudioSourceType>,
    val enabled: Set<AudioSourceType>,
)

/** Pure derivation of the ordered, gated source lists a resolution needs. */
object PlaybackSourceChain {
    /**
     * The enabled sources in priority order, cut at YouTube. YouTube and everything after it is left to
     * the terminal fallback, and disabled sources are skipped. The result is empty when only YouTube is
     * enabled.
     */
    fun baseChain(snapshot: PlaybackSourceSnapshot): List<AudioSourceType> =
        snapshot.order.takeWhile { it != AudioSourceType.YOUTUBE }.filter { it in snapshot.enabled }

    /**
     * The chain one resolution attempts: the per-song pin's chain when a pin applies, otherwise
     * [baseChain]. A pin whose source has since been turned off yields to the base chain, as a stale pin
     * does. An enabled YouTube pin empties the chain so the terminal fallback serves the song, unless
     * YouTube was already rejected for this song, which hands it back to the base chain.
     */
    fun configuredChain(
        snapshot: PlaybackSourceSnapshot,
        override: AudioSourceType?,
        rejected: Set<AudioSourceType>,
    ): List<AudioSourceType> {
        val base = baseChain(snapshot)
        if (override == null) return base
        if (override !in snapshot.enabled) return base
        if (override == AudioSourceType.YOUTUBE) {
            return if (override in rejected) base else emptyList()
        }
        return listOf(override)
    }

    /**
     * Whether the ephemeral YouTube player cache may short-circuit a resolution. It may only when no
     * enabled source sits above YouTube. Otherwise a newly enabled source would be bypassed by bytes
     * cached from an earlier YouTube play.
     */
    fun cacheShortCircuitAllowed(snapshot: PlaybackSourceSnapshot): Boolean = baseChain(snapshot).isEmpty()

    /**
     * The cache keys to probe for [mediaId], in priority order. Base-chain sources come first. The bare
     * YouTube key comes last, and only while YouTube is enabled. Sources without a cache key contribute
     * nothing, and a disabled source's keys are left out entirely.
     */
    fun cacheKeyOrder(snapshot: PlaybackSourceSnapshot, mediaId: String): List<String> =
        baseChain(snapshot).mapNotNull { source -> cacheProbeKey(source, mediaId) } +
            listOfNotNull(mediaId.takeIf { AudioSourceType.YOUTUBE in snapshot.enabled })

    /**
     * The cache key a source's bytes are probed under, or null when the source has none. These prefixes
     * match what downloads write under, so a play and a download find the same bytes.
     */
    fun cacheProbeKey(source: AudioSourceType, mediaId: String): String? =
        when (source) {
            AudioSourceType.TIDAL -> "tidal:$mediaId"
            AudioSourceType.QOBUZ -> "qobuz:$mediaId"
            AudioSourceType.QOBUZ_BACKUP -> "qobuz_backup:$mediaId"
            AudioSourceType.DEEZER -> "deezer:$mediaId"
            AudioSourceType.JIOSAAVN -> "jiosaavn:$mediaId"
            AudioSourceType.YOUTUBE -> mediaId
            AudioSourceType.APPLE, AudioSourceType.AMAZON, AudioSourceType.QQ, AudioSourceType.SPOTIFY, AudioSourceType.MODULES -> null
        }

    /**
     * The enabled set from the per-source switches. Spotify counts only when its switch is on and a
     * streaming account is signed in, since the switch alone does nothing.
     */
    fun enabledSet(flags: Map<AudioSourceType, Boolean>, spotifySignedIn: Boolean): Set<AudioSourceType> {
        val enabled = flags.filter { (source, on) -> source != AudioSourceType.SPOTIFY && on }.keys.toMutableSet()
        if (flags[AudioSourceType.SPOTIFY] == true && spotifySignedIn) enabled += AudioSourceType.SPOTIFY
        return enabled
    }

    /**
     * Whether YouTube may serve as the terminal fallback. The YouTube disable toggle is a hard stop: a
     * user who turned YouTube off gets a no-stream failure, not a YouTube stream. A source that
     * authenticity checks rejected for this song is also refused.
     */
    fun youTubeTerminalAllowed(snapshot: PlaybackSourceSnapshot, rejected: Set<AudioSourceType>): Boolean =
        AudioSourceType.YOUTUBE in snapshot.enabled && AudioSourceType.YOUTUBE !in rejected
}
