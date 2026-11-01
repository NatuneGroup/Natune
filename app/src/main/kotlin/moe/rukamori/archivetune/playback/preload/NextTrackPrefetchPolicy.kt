package moe.rukamori.archivetune.playback.preload

/**
 * When the service resolves the next song's stream ahead of its play start. The transition-time
 * prefetch already covers skips and most track ends; the remaining-≤10s window adds the trigger
 * Spotify's PLAYER_CAN_PRELOAD uses, so a song that entered the queue after the last transition
 * still resolves before the boundary. All pure: the watcher job in MusicService reads the player
 * and asks these two questions.
 */
internal object NextTrackPrefetchPolicy {
    /** How much of the current song may remain before the successor's stream is resolved. */
    const val PREFETCH_REMAINING_MS = 10_000L

    /** A known finite duration inside the last [PREFETCH_REMAINING_MS] arms the prefetch. */
    fun shouldPrefetch(durationMs: Long, positionMs: Long): Boolean =
        durationMs > 0L && positionMs >= 0L && durationMs - positionMs <= PREFETCH_REMAINING_MS

    /**
     * The watcher's poll cadence: unknown durations and far-out positions poll slowly, the last
     * thirty seconds polls finely enough to land the trigger close to the window edge.
     */
    fun pollDelayMs(durationMs: Long, positionMs: Long): Long =
        if (durationMs <= 0L || positionMs < 0L || durationMs - positionMs > 30_000L) {
            2_000L
        } else {
            500L
        }
}
