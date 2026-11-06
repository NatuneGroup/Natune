package moe.rukamori.archivetune.ui.component

import kotlin.math.roundToLong

/**
 * The Enhanced pane's drawn position, projected from the anchor the position loop last published.
 *
 * The loop reads the player once per frame and anchors: "the song was at [anchorPositionMs] at
 * [anchorNanos]". The pane then projects that anchor at draw time with the frame's own clock. A
 * position written into a state from the loop instead would be one frame old by the time the frame
 * that reads it is drawn — the sweep of every frame would show the position of the frame before it,
 * which reads as motion that lags the audio. Projecting at draw removes that frame of lag and keeps
 * the sweep an exact function of the instant it is painted at.
 *
 * All of it is pure arithmetic: no clock is read here, so a test can pin both instants.
 */
internal object LyricsPositionProjection {
    /**
     * How far the player may run ahead of the projection before it is treated as a jump. Small enough
     * to catch a forward seek or a stall, large enough to ride through ExoPlayer's ordinary
     * corrections (a few tens of milliseconds).
     */
    const val MAX_FORWARD_DRIFT_MS = 80L

    /** The backward bound is looser: a player clock that lands a beat late is not a seek. */
    const val MAX_BACKWARD_DRIFT_MS = 180L

    /** The share of the drift the next anchor takes, so a drifting clock is followed without a step. */
    const val DRIFT_CORRECTION = 0.55f

    private const val NANOS_PER_MS = 1_000_000.0

    /** True when the player is far enough from [projectedPositionMs] that the anchor has to move to it. */
    fun shouldReanchor(
        playerPositionMs: Long,
        projectedPositionMs: Long,
    ): Boolean {
        val drift = playerPositionMs - projectedPositionMs
        return drift > MAX_FORWARD_DRIFT_MS || drift < -MAX_BACKWARD_DRIFT_MS
    }

    /** The anchor advanced by [elapsedNanos] of wall time at [speed], never before zero. */
    fun project(
        anchorPositionMs: Long,
        elapsedNanos: Long,
        speed: Float,
    ): Long {
        val advanced = (elapsedNanos.coerceAtLeast(0L) / NANOS_PER_MS * speed).roundToLong()
        return (anchorPositionMs + advanced).coerceAtLeast(0L)
    }

    /**
     * The next anchor: [project] nudged [DRIFT_CORRECTION] of the way toward the player's own
     * position so a drifting audio clock is followed without the sweep stepping. A drift past the
     * bounds is a seek or a stall, and the player's position wins outright.
     */
    fun corrected(
        anchorPositionMs: Long,
        elapsedNanos: Long,
        speed: Float,
        playerPositionMs: Long,
    ): Long {
        val projected = project(anchorPositionMs, elapsedNanos, speed)
        if (shouldReanchor(playerPositionMs, projected)) return playerPositionMs.coerceAtLeast(0L)
        val drift = playerPositionMs - projected
        if (drift == 0L) return projected
        return (projected + (drift * DRIFT_CORRECTION).roundToLong()).coerceAtLeast(0L)
    }
}

/**
 * The anchor the Enhanced pane draws from, published by the position loop and projected at draw
 * time. [advancing] is false while paused, while the slider owns the position and under reduced
 * motion, where the drawn position is exactly the anchor and a seek lands on its own frame.
 */
internal class EnhancedLyricsDrawClock {
    private var anchorPositionMs = 0L
    private var anchorNanos = 0L
    private var speed = 1f
    private var advancing = false

    /** Publishes the position the lyrics were at [atNanos], and whether it keeps moving from there. */
    fun anchor(
        positionMs: Long,
        atNanos: Long,
        speed: Float,
        advancing: Boolean,
    ) {
        anchorPositionMs = positionMs.coerceAtLeast(0L)
        anchorNanos = atNanos
        this.speed = speed
        this.advancing = advancing
    }

    /** The drawn position at [nowNanos] (the wall clock by default). */
    fun positionMs(nowNanos: Long = System.nanoTime()): Long =
        if (!advancing) {
            anchorPositionMs
        } else {
            LyricsPositionProjection.project(anchorPositionMs, nowNanos - anchorNanos, speed)
        }
}
