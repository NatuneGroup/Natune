package moe.rukamori.archivetune.ui.component

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.exp

/**
 * The line-change glide of the ENHANCED list. A one-line advance moves the list at once, so the new line is already in its
 * place and every row is drawn where it was before the move. The rows then ease into their places, each after its own
 * stagger delay, so the change ripples outward from the sung line. The offset is read in the draw phase, so a glide costs
 * no recomposition, and the first frame after the move already shows the rows at their old places.
 */
@Stable
internal class EnhancedLineSettle(private val listState: LazyListState) {
    /** The line the list moved to, or -1 when no glide runs. */
    var primaryIndex: Int by mutableIntStateOf(-1)
        private set

    /** Where that line sat before the move, in px from the top of the viewport. */
    var fromTopPx: Int by mutableIntStateOf(0)
        private set

    /** Milliseconds since the move. Only [runSettle] advances it, once per frame. */
    var elapsedMs: Long by mutableLongStateOf(0L)
        private set

    /** Counts starts and clears. Each one restarts [runEachSettle], so a glide in flight never outlives its move. */
    var generation: Int by mutableIntStateOf(0)
        private set

    /** Starts a glide for the move of line [index], which sat at [fromTop] before the list moved. */
    fun start(index: Int, fromTop: Int) {
        primaryIndex = index
        fromTopPx = fromTop
        elapsedMs = 0L
        generation += 1
    }

    /** Moves the glide to [elapsed] ms, if [ofGeneration] is still the current move. */
    fun advance(ofGeneration: Int, elapsed: Long) {
        if (ofGeneration == generation) elapsedMs = elapsed
    }

    /** Ends the glide, if [ofGeneration] is still the current move. */
    fun finish(ofGeneration: Int) {
        if (ofGeneration == generation) primaryIndex = -1
    }

    /**
     * Puts every row in place at once. A drag, a seek, or a surface that stops showing ends the glide here, so nothing is
     * left gliding under a finger.
     */
    fun clear() {
        primaryIndex = -1
        generation += 1
    }

    /**
     * The vertical offset in px of a row that waits [delayMs] before it moves. The move is measured against the live
     * position of the sung line, so the offset is right on the frame the list moves and never lags a layout pass.
     */
    fun offsetPx(rowIndex: Int, delayMs: Long): Float {
        val index = primaryIndex
        if (index < 0 || elapsedMs >= EnhancedLineSettleMotion.DURATION_MS) return 0f
        val nowTop = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.offset ?: return 0f
        val displacement = (fromTopPx - nowTop).toFloat()
        val delay = EnhancedLineSettleMotion.delayFor(rowIndex, index, displacement, delayMs)
        return EnhancedLineSettleMotion.offsetPx(displacement, elapsedMs, delay)
    }
}

/**
 * Runs a glide each time one starts. A newer start or clear cancels the frame loop in flight. The host runs this inside
 * repeatOnLifecycle(RESUMED), so the frame loop stops when the screen is not resumed and picks up where it stopped.
 */
internal suspend fun EnhancedLineSettle.runEachSettle() {
    snapshotFlow { generation }.collectLatest { runSettle() }
}

/** Advances one glide once per frame until its longest delay and spring have run out, then ends it. */
internal suspend fun EnhancedLineSettle.runSettle() {
    if (primaryIndex < 0) return
    val owned = generation
    // A loop that a stop of the screen cancelled resumes from the stored elapsed time, so the glide does not replay.
    val startNanos = withFrameNanos { it } - elapsedMs * NANOS_PER_MS
    while (true) {
        val elapsed = withFrameNanos { (it - startNanos) / NANOS_PER_MS }
        if (elapsed >= EnhancedLineSettleMotion.DURATION_MS) break
        advance(owned, elapsed)
    }
    finish(owned)
}

private const val NANOS_PER_MS = 1_000_000L

/**
 * The glide curve, kept apart from the state so its timing can be checked without a composition. A critically damped
 * spring released from rest is `x(t) = x0 * (1 + w t) * e^(-w t)`, with no overshoot. [ANGULAR_FREQUENCY] is the square
 * root of `Spring.StiffnessMedium`, the stiffness the row's depth change uses too, so the two motions share their
 * timing: the ripple a line change makes and the fade of the rows it passes settle together, in about 400 ms rather
 * than the ~1 s the softest spring took.
 */
internal object EnhancedLineSettleMotion {
    /** The longest a glide runs: the largest stagger delay plus the time the spring needs to come to rest. */
    const val DURATION_MS = 1_000L

    private const val ANGULAR_FREQUENCY = 38.729835f

    /**
     * The delay row [rowIndex] actually waits. Every row travels the same distance, so a row that started later than
     * the row ahead of it in the direction of travel would close on it: the sung line rising into the line above. Rows
     * ahead of the sung line therefore move with it, and only the rows trailing it ripple, which only ever widens a gap.
     */
    fun delayFor(rowIndex: Int, primaryIndex: Int, displacementPx: Float, staggerMs: Long): Long {
        val leading = if (displacementPx > 0f) rowIndex < primaryIndex else rowIndex > primaryIndex
        return if (leading) 0L else staggerMs
    }

    fun offsetPx(displacementPx: Float, elapsedMs: Long, delayMs: Long): Float {
        val seconds = (elapsedMs - delayMs).coerceAtLeast(0L) / 1_000f
        val w = ANGULAR_FREQUENCY * seconds
        return displacementPx * (1f + w) * exp(-w)
    }
}
