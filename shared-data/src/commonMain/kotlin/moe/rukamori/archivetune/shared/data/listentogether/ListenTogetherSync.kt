package moe.rukamori.archivetune.shared.data.listentogether

import kotlin.math.abs
import kotlin.math.max

/**
 * The drift and tolerance math of Listen Together playback sync, ported from the Android client's
 * listentogether/ListenTogetherManager.kt. These are pure functions so every host (Android, desktop, iOS)
 * drifts the same way and the numbers can be tested without a player.
 *
 * The room has no shared clock: the host stamps a play with the server's receive time, and each guest adds
 * the wall-clock time that passed since the server saw it. A stale or future stamp contributes nothing —
 * positions only ever move forward by real elapsed time, never backwards from clock skew.
 */
object ListenTogetherSync {

    /** A host play or pause within this window of the local position is ignored: the round trip already costs it. */
    const val SYNC_DEBOUNCE_THRESHOLD_MS = 1_000L

    /** Drift corrected on pause and on a plain play. */
    const val POSITION_TOLERANCE_MS = 2_000L

    /** Drift tolerated while actively playing under a buffer gate, where a seek would be heard. */
    const val PLAYBACK_POSITION_TOLERANCE_MS = 3_000L

    /** The host repeats its position this often while the room is playing. */
    const val HOST_HEARTBEAT_INTERVAL_MS = 10_000L

    /** A guest that never finishes loading still reports, so the room is not held. */
    const val BUFFER_WAIT_MS = 20_000L

    /** A reconnecting guest asks for a fresh sync state once this much time has passed (Smart Resync). */
    const val SMART_RESYNC_DELAY_MS = 1_000L

    /** The most queue entries a host shares with the room. */
    const val MAX_SHARED_QUEUE = 100

    /**
     * Where a host PLAY belongs now: [positionMs] at the moment the server saw it, plus the time since.
     * `serverTimeMs` is the server's clock, so [nowMs] minus it is the one-way delay; a server clock ahead of
     * this device contributes nothing.
     */
    fun adjustedPlayPosition(positionMs: Long, serverTimeMs: Long?, nowMs: Long): Long =
        positionMs + (serverTimeMs?.let { max(0L, nowMs - it) } ?: 0L)

    /**
     * Where a sync-state position stands now: only a playing room has moved since the server stamped it.
     */
    fun adjustedSyncPosition(positionMs: Long, isPlaying: Boolean, lastUpdateMs: Long, nowMs: Long): Long =
        positionMs + if (isPlaying) max(0L, nowMs - lastUpdateMs) else 0L

    /**
     * Where a position buffered for buffer_complete belongs now: it only accumulates while the room is playing.
     */
    fun adjustedPendingPosition(positionMs: Long, playing: Boolean, receivedAtMs: Long, nowMs: Long): Long =
        positionMs + if (playing) max(0L, nowMs - receivedAtMs) else 0L

    /**
     * Whether [currentMs] is far enough from [targetMs] to be worth a seek. A null reading counts as out of
     * tolerance, matching the Android player behaviour where an unread position always seeks.
     */
    fun outsideTolerance(currentMs: Long?, targetMs: Long, toleranceMs: Long): Boolean =
        currentMs == null || abs(currentMs - targetMs) > toleranceMs

    /** The tolerance a settled guest applies for a host action: looser while the room is playing. */
    fun toleranceFor(willPlay: Boolean, isPlayingNow: Boolean): Long =
        if (willPlay && isPlayingNow) PLAYBACK_POSITION_TOLERANCE_MS else POSITION_TOLERANCE_MS
}
