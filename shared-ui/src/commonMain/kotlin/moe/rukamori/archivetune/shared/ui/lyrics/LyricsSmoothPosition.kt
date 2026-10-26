package moe.rukamori.archivetune.shared.ui.lyrics

import moe.rukamori.archivetune.shared.domain.playback.PlaybackClock
import moe.rukamori.archivetune.shared.domain.playback.PlaybackSnapshot
import kotlin.math.roundToLong

/** How [LyricsSmoothPosition] took one incoming snapshot. */
enum class LyricsAnchorChange {
    /** Same timeline. The clock keeps easing toward the new anchor instead of jumping. */
    ANCHORED,

    /** A new session, occurrence or discontinuity. The clock jumps; the row list keeps its items. */
    RESET,

    /** An older observation than the current anchor. The clock ignores it. */
    STALE,
}

/**
 * The one lyrics clock. It reads only [PlaybackSnapshot] values and never the engine, so every lyrics
 * surface shows the same position for the same snapshot.
 *
 * Between snapshots the position extrapolates from the anchor while audio is advancing. A projected
 * position more than [MAX_FORWARD_DRIFT_MS] ahead of the anchor, or more than [MAX_BACKWARD_DRIFT_MS]
 * behind it, snaps to the anchor. Smaller drift closes [DRIFT_CORRECTION] of the gap on each advance.
 * A paused, buffering or remote-routed output holds the anchor position exactly.
 */
class LyricsSmoothPosition {
    private var anchor: PlaybackSnapshot? = null
    private var smoothMs = 0.0
    private var lastAdvanceNanos = NO_ADVANCE

    /** The position last produced by [accept] or [advance]. */
    val positionMs: Long
        get() = smoothMs.roundToLong()

    /** Takes one observation. [nowNanos] must share the snapshot time base. */
    fun accept(incoming: PlaybackSnapshot, nowNanos: Long): LyricsAnchorChange {
        val current = anchor
        val next = PlaybackClock.accept(current, incoming)
        if (current != null && next !== incoming) return LyricsAnchorChange.STALE
        anchor = incoming
        if (current != null && sameTimeline(current, incoming)) return LyricsAnchorChange.ANCHORED
        smoothMs = PlaybackClock.positionMs(incoming, nowNanos).toDouble()
        lastAdvanceNanos = nowNanos
        return LyricsAnchorChange.RESET
    }

    /**
     * The position at [nowNanos]. Call once per frame while the frame loop runs, or once per snapshot
     * otherwise. The position never moves while the anchor is not interpolating.
     */
    fun advance(nowNanos: Long): Long {
        val snapshot = anchor ?: return 0L
        val authoritative = PlaybackClock.positionMs(snapshot, nowNanos).toDouble()
        val elapsedMs = elapsedSinceLastAdvance(nowNanos)
        lastAdvanceNanos = nowNanos
        if (!PlaybackClock.isInterpolating(snapshot)) {
            smoothMs = authoritative
            return positionMs
        }
        val projected = smoothMs + elapsedMs * snapshot.playbackSpeed
        val drift = projected - authoritative
        val corrected = when {
            drift > MAX_FORWARD_DRIFT_MS || drift < -MAX_BACKWARD_DRIFT_MS -> authoritative
            else -> projected - drift * DRIFT_CORRECTION
        }
        smoothMs = clampToDuration(corrected, snapshot.durationMs)
        return positionMs
    }

    private fun elapsedSinceLastAdvance(nowNanos: Long): Double {
        if (lastAdvanceNanos == NO_ADVANCE || nowNanos <= lastAdvanceNanos) return 0.0
        return ((nowNanos - lastAdvanceNanos) / NANOS_PER_MS).coerceAtMost(MAX_ELAPSED_MS)
    }

    private fun clampToDuration(positionMs: Double, durationMs: Long?): Double {
        val floored = positionMs.coerceAtLeast(0.0)
        return if (durationMs == null) floored else floored.coerceAtMost(durationMs.toDouble())
    }

    private fun sameTimeline(a: PlaybackSnapshot, b: PlaybackSnapshot): Boolean =
        a.playbackGeneration == b.playbackGeneration &&
            a.discontinuityGeneration == b.discontinuityGeneration &&
            a.occurrenceId == b.occurrenceId

    companion object {
        /** How far the projection may run ahead of the anchor before it snaps back. */
        const val MAX_FORWARD_DRIFT_MS = 80.0

        /** How far the projection may trail the anchor before it snaps forward. */
        const val MAX_BACKWARD_DRIFT_MS = 180.0

        /** Share of the remaining gap closed on each advance while the drift stays inside the band. */
        const val DRIFT_CORRECTION = 0.55

        /** A stalled frame extrapolates at most this far, so one slow frame cannot launch the clock. */
        const val MAX_ELAPSED_MS = 500.0

        private const val NANOS_PER_MS = 1_000_000.0
        private const val NO_ADVANCE = Long.MIN_VALUE
    }
}

/**
 * Decides whether the lyrics frame loop may run. A hidden, minimised, reduced-motion or paused surface
 * stops it; the clock then only follows snapshots, so nothing animates off screen.
 */
object LyricsFrameGate {
    fun runsFrameLoop(
        visible: Boolean,
        resumed: Boolean,
        reduceMotion: Boolean,
        snapshot: PlaybackSnapshot?,
    ): Boolean =
        visible && resumed && !reduceMotion && snapshot != null && PlaybackClock.isInterpolating(snapshot)
}
