package moe.rukamori.archivetune.shared.ui.lyrics

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlin.math.exp

/**
 * The line-change scroll as the rows see it. A change moves the list at once, so the new line is already in its place
 * and every row is drawn where it was before the move. The rows then ease into their places, each after its own
 * stagger delay, so the change ripples outward from the sung line. The offset is read in the draw phase, so a settle
 * costs no recomposition, and the first frame after the move already shows the rows at their old places.
 */
@Stable
internal class LyricsLineSettle(private val listState: LazyListState) {
    /** The line the list moved to, or -1 when no settle runs. */
    var primaryIndex: Int by mutableIntStateOf(-1)
        private set

    /** Where that line sat before the move, in px from the top of the viewport. */
    var fromTopPx: Int by mutableIntStateOf(0)
        private set

    /** Milliseconds since the change. Only [runSettle] advances it, once per frame. */
    var elapsedMs: Long by mutableLongStateOf(0L)
        private set

    /** Counts changes. Each change restarts [runSettle], so the rows always follow the newest move. */
    var generation: Int by mutableIntStateOf(0)
        private set

    /** Starts a settle for the move of line [index], which sat at [fromTop] before the list moved. */
    fun start(index: Int, fromTop: Int) {
        primaryIndex = index
        fromTopPx = fromTop
        elapsedMs = 0L
        generation += 1
    }

    /** Moves the settle to [elapsed] ms, if [ofGeneration] is still the current change. */
    fun advance(ofGeneration: Int, elapsed: Long) {
        if (ofGeneration == generation) elapsedMs = elapsed
    }

    /** Ends the settle, if [ofGeneration] is still the current change. */
    fun finish(ofGeneration: Int) {
        if (ofGeneration == generation) primaryIndex = -1
    }

    /**
     * Puts every row in place at once. A seek, a new session, a hand on the list or a hidden surface ends the settle
     * here, so nothing is left gliding under a finger.
     */
    fun clear() {
        primaryIndex = -1
    }

    /**
     * The vertical offset in px of a row that waits [delayMs] before it moves. The move is measured against the live
     * position of the sung line, so the offset is right on the frame the list moves and never lags a layout pass.
     */
    fun offsetPx(delayMs: Long): Float {
        val index = primaryIndex
        if (index < 0 || elapsedMs >= LyricsLineSettleMotion.DURATION_MS) return 0f
        val nowTop = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.offset ?: return 0f
        return LyricsLineSettleMotion.offsetPx((fromTopPx - nowTop).toFloat(), elapsedMs, delayMs)
    }
}

/**
 * Advances [LyricsLineSettle] once per frame until its longest stagger and spring have run out, then ends it. A later
 * change cancels this loop when its generation key changes, and the guards keep a stale frame from touching the new one.
 */
internal suspend fun LyricsLineSettle.runSettle() {
    val owned = generation
    val start = withFrameNanos { it }
    while (true) {
        val elapsed = withFrameNanos { (it - start) / NANOS_PER_MS }
        if (elapsed >= LyricsLineSettleMotion.DURATION_MS) break
        advance(owned, elapsed)
    }
    finish(owned)
}

private const val NANOS_PER_MS = 1_000_000L

/**
 * The settle curve, kept apart from the state so its timing can be checked without a composition. A critically damped
 * spring released from rest is `x(t) = x0 * (1 + w t) * e^(-w t)`, with no overshoot. [ANGULAR_FREQUENCY] is the square
 * root of `Spring.StiffnessLow`, the stiffness the depth change already uses, so the two motions share their timing.
 */
internal object LyricsLineSettleMotion {
    /** The longest a settle runs: the largest stagger delay plus the time the spring needs to come to rest. */
    const val DURATION_MS = 1_000L

    private const val ANGULAR_FREQUENCY = 14.142136f

    fun offsetPx(displacementPx: Float, elapsedMs: Long, delayMs: Long): Float {
        val seconds = (elapsedMs - delayMs).coerceAtLeast(0L) / 1_000f
        val w = ANGULAR_FREQUENCY * seconds
        return displacementPx * (1f + w) * exp(-w)
    }
}
