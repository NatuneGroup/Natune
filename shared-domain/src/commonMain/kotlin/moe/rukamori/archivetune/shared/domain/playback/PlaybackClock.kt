package moe.rukamori.archivetune.shared.domain.playback

import kotlin.math.roundToLong

/**
 * Derives the playback position at an instant from the latest accepted [PlaybackSnapshot].
 *
 * The clock never reads engine state. It extrapolates only while the output device is audibly
 * consuming audio. A pause, a buffer stall or a remote route therefore holds the last observed
 * position, rather than letting the displayed position run ahead of what is heard.
 */
object PlaybackClock {
    private const val NANOS_PER_MILLISECOND = 1_000_000.0

    /**
     * The position in milliseconds at [nowMonotonicNanos]. That value must come from the same
     * base as [PlaybackSnapshot.sampleMonotonicNanos], normally [SystemMonotonicClock].
     */
    fun positionMs(snapshot: PlaybackSnapshot, nowMonotonicNanos: Long): Long {
        val elapsedNanos = nowMonotonicNanos - snapshot.sampleMonotonicNanos
        val position = if (elapsedNanos > 0L && isInterpolating(snapshot)) {
            val scaledElapsedMs = elapsedNanos / NANOS_PER_MILLISECOND * snapshot.playbackSpeed
            snapshot.positionMs + scaledElapsedMs.roundToLong()
        } else {
            // A reader that runs before the sample time must not see the position step backwards.
            snapshot.positionMs
        }
        return clamp(position, snapshot.durationMs)
    }

    /**
     * Whether [positionMs] moves with time for this snapshot. A false result holds the position,
     * so a frame clock that only drives progress can stop while it is false. Play intent alone
     * does not count: a pause that is still draining audio keeps advancing until the output stops.
     */
    fun isInterpolating(snapshot: PlaybackSnapshot): Boolean =
        snapshot.isAudioAdvancing &&
            !snapshot.isBuffering &&
            snapshot.outputRoute != PlaybackOutputRoute.REMOTE

    /**
     * The anchor to interpolate from once [incoming] has been observed.
     *
     * A snapshot that [supersedes] the current anchor replaces it outright. That replacement is
     * the reset: nothing from the old anchor is extrapolated across a seek, a repeat or a new
     * session. A stale snapshot leaves the anchor in place.
     */
    fun accept(current: PlaybackSnapshot?, incoming: PlaybackSnapshot): PlaybackSnapshot =
        if (current == null || incoming.supersedes(current)) incoming else current

    private fun clamp(positionMs: Long, durationMs: Long?): Long {
        val floored = positionMs.coerceAtLeast(0L)
        return durationMs?.let { floored.coerceAtMost(it) } ?: floored
    }
}
