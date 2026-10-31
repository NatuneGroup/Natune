package moe.rukamori.archivetune.shared.ui.lyrics

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos

/** A silence on the lyrics timeline long enough to show the dots. [endMs] is when the next sung line starts. */
internal data class LyricsInterlude(
    val startMs: Long,
    val endMs: Long,
) {
    val durationMs: Long
        get() = endMs - startMs
}

/** Finds the silences long enough to show the three dots, and the row whose item shows each one. */
internal object LyricsInterludes {
    /** Shorter silences read as ordinary phrasing and keep the plain line rhythm. */
    const val MIN_BREAK_MS = 4_000L

    /**
     * The break each row's item shows, keyed by row index. A sung row owns the silence before it. An instrumental
     * row owns the silence before it and its own span. Rows are walked in time order, and the walk keeps the furthest
     * point the spans have reached, so overlapping duet rows do not hide a real silence.
     */
    fun byRow(rows: List<LyricsRenderRow>): Map<Int, LyricsInterlude> {
        val result = HashMap<Int, LyricsInterlude>()
        var reachedMs = 0L
        for (row in rows) {
            if (!row.isSynced) continue
            val silentFromMs = minOf(reachedMs, row.scanStartMs)
            val untilMs = if (row.isInstrumental) row.scanEndMs else row.scanStartMs
            if (untilMs - silentFromMs >= MIN_BREAK_MS) {
                result[row.index] = LyricsInterlude(silentFromMs, untilMs)
            }
            reachedMs = maxOf(reachedMs, row.scanEndMs)
        }
        return result
    }
}

/**
 * The dots' motion over one break. Each function reads the lyrics position and keeps no state, so a seek lands on
 * the right frame and nothing is left running when the break ends.
 */
internal object LyricsInterludeMotion {
    const val DOT_COUNT = 3

    private const val ENTER_MS = 400L
    private const val EXIT_MS = 400L
    private const val BREATH_CYCLE_MS = 2_400L
    private const val BREATH_MIN = 0.92f
    private const val BREATH_MAX = 1.08f
    private const val IDLE_LIGHT = 0.25f

    /** How visible the whole indicator is at [positionMs]. It fades in as the break starts and out before it ends. */
    fun visibility(interlude: LyricsInterlude, positionMs: Long): Float {
        if (positionMs < interlude.startMs || positionMs >= interlude.endMs) return 0f
        val enter = (positionMs - interlude.startMs).toFloat() / ENTER_MS
        val exit = (interlude.endMs - positionMs).toFloat() / EXIT_MS
        return minOf(enter, exit, 1f)
    }

    /** The share of the break that has passed, from 0 to 1. */
    fun progress(interlude: LyricsInterlude, positionMs: Long): Float =
        ((positionMs - interlude.startMs).toFloat() / interlude.durationMs.coerceAtLeast(1L)).coerceIn(0f, 1f)

    /**
     * How lit dot [index] is, from [IDLE_LIGHT] to 1. The dots take their turn across the break, a third each.
     * Reduced motion lights them in steps rather than easing them.
     */
    fun dotLight(progress: Float, index: Int, reduceMotion: Boolean): Float {
        val turn = progress * DOT_COUNT - index
        val share = if (reduceMotion) {
            if (turn > 0f) 1f else 0f
        } else {
            turn.coerceIn(0f, 1f)
        }
        return IDLE_LIGHT + (1f - IDLE_LIGHT) * share
    }

    /** The breath, from [BREATH_MIN] to [BREATH_MAX], on a cycle of [BREATH_CYCLE_MS] of the lyrics timeline. */
    fun breath(positionMs: Long): Float {
        val phase = (positionMs % BREATH_CYCLE_MS).toDouble() / BREATH_CYCLE_MS
        val swell = 0.5 - 0.5 * cos(2.0 * PI * phase)
        return BREATH_MIN + (BREATH_MAX - BREATH_MIN) * swell.toFloat()
    }
}

/**
 * Three dots that light in turn over an instrumental break, with a slow breath. They read the lyrics position in the
 * draw phase, so they move without recomposing the row, and they draw nothing outside their break.
 */
@Composable
internal fun LyricsInterludeDots(
    interlude: LyricsInterlude,
    clock: LyricsClock,
    color: Color,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    Spacer(
        modifier
            .size(width = DOTS_WIDTH_DP.dp, height = DOTS_HEIGHT_DP.dp)
            .drawBehind {
                drawInterludeDots(interlude, clock.feedMs.longValue, color, reduceMotion)
            },
    )
}

private const val DOTS_WIDTH_DP = 38
private const val DOTS_HEIGHT_DP = 12
private const val DOT_DIAMETER_DP = 8
private const val DOT_GAP_DP = 7

private fun DrawScope.drawInterludeDots(
    interlude: LyricsInterlude,
    positionMs: Long,
    color: Color,
    reduceMotion: Boolean,
) {
    val visibility = LyricsInterludeMotion.visibility(interlude, positionMs)
    if (visibility <= 0f) return
    val progress = LyricsInterludeMotion.progress(interlude, positionMs)
    val breath = if (reduceMotion) 1f else LyricsInterludeMotion.breath(positionMs)
    val diameter = DOT_DIAMETER_DP.dp.toPx()
    val gap = DOT_GAP_DP.dp.toPx()
    val radius = diameter / 2f * breath
    val centerY = size.height / 2f
    for (index in 0 until LyricsInterludeMotion.DOT_COUNT) {
        drawCircle(
            color = color,
            radius = radius,
            center = Offset(diameter / 2f + index * (diameter + gap), centerY),
            alpha = visibility * LyricsInterludeMotion.dotLight(progress, index, reduceMotion),
        )
    }
}
