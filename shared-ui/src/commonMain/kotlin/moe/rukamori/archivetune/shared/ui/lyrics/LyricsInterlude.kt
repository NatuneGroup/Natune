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
    const val MIN_BREAK_MS = 9_000L

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

    /** One full turn of the travelling pulse: the dots peak a third of a cycle apart. */
    const val PULSE_CYCLE_MS = 1_400L

    /** The slow size breath, two pulse turns long, so the two rhythms meet again every cycle. */
    const val BREATH_CYCLE_MS = 2_800L
    private const val BREATH_MIN = 0.94f
    private const val BREATH_MAX = 1.08f
    private const val IDLE_LIGHT = 0.28f

    /** Over the last [END_SWELL_MS] of the break the dots swell toward the arriving line. */
    const val END_SWELL_MS = 1_500L
    const val END_SWELL_TO = 1.3f

    /**
     * The anticipation swell as a *share of the breath*: exactly 1 everywhere outside the last
     * [END_SWELL_MS] of the break, easing to [END_SWELL_TO] as the next line arrives. A factor of 1 is
     * neutral in a product, so the curve leaves and re-enters the breath without a jump — a `maxOf`
     * against a function that started above 1 snapped the dots larger on the frame its window opened.
     */
    fun endSwell(interlude: LyricsInterlude, positionMs: Long): Float {
        val remaining = interlude.endMs - positionMs
        if (remaining <= 0L || remaining > END_SWELL_MS) return 1f
        val through = 1f - remaining.toFloat() / END_SWELL_MS
        val eased = through * through * (3f - 2f * through)
        return 1f + (END_SWELL_TO - 1f) * eased
    }

    /**
     * The dots' scale at [positionMs]: the slow breath times the end swell, so neither can restart.
     * Both are motion, so reduced motion leaves the dots at their own size — a neutral 1 — instead of
     * holding the breath still and still swelling them 1.3x into the arriving line.
     */
    fun scale(interlude: LyricsInterlude, positionMs: Long, reduceMotion: Boolean): Float {
        if (reduceMotion) return 1f
        return breath(positionMs) * endSwell(interlude, positionMs)
    }

    /** How visible the whole indicator is at [positionMs]. It fades in as the break starts and out before it ends. */
    fun visibility(interlude: LyricsInterlude, positionMs: Long): Float {
        if (positionMs < interlude.startMs || positionMs >= interlude.endMs) return 0f
        val enter = (positionMs - interlude.startMs).toFloat() / ENTER_MS
        val exit = (interlude.endMs - positionMs).toFloat() / EXIT_MS
        return minOf(enter, exit, 1f)
    }

    /**
     * How lit dot [index] is at [positionMs], from [IDLE_LIGHT] to 1: a travelling pulse whose per-dot
     * brightness is one half of a cosine, each dot a third of a cycle behind the one before it.
     *
     * The loop is seamless by construction — a cosine of the phase returns the same value and slope at
     * the wrap — and the phase comes from the lyrics timeline rather than the break's progress, so a
     * long break and a short one pulse alike instead of a fill that begins again at every break.
     * Reduced motion lights one dot at a time without easing.
     */
    fun dotLight(
        positionMs: Long,
        index: Int,
        reduceMotion: Boolean,
    ): Float {
        if (reduceMotion) {
            val slot = (phaseOf(positionMs) * DOT_COUNT).toInt().coerceIn(0, DOT_COUNT - 1)
            return if (slot == index) 1f else IDLE_LIGHT
        }
        val offset = phaseOf(positionMs - index * (PULSE_CYCLE_MS / DOT_COUNT))
        val lobe = ((cos(2.0 * PI * offset) + 1.0) / 2.0).toFloat()
        return IDLE_LIGHT + (1f - IDLE_LIGHT) * lobe
    }

    /** The breath, from [BREATH_MIN] to [BREATH_MAX], on a cycle of [BREATH_CYCLE_MS] of the lyrics timeline. */
    fun breath(positionMs: Long): Float {
        val swell = 0.5 - 0.5 * cos(2.0 * PI * phaseOf(positionMs, BREATH_CYCLE_MS))
        return BREATH_MIN + (BREATH_MAX - BREATH_MIN) * swell.toFloat()
    }

    /** [positionMs] as a share of [cycleMs], wrapped into `0..1` for any position, negative included. */
    private fun phaseOf(
        positionMs: Long,
        cycleMs: Long = PULSE_CYCLE_MS,
    ): Double = (((positionMs % cycleMs) + cycleMs) % cycleMs).toDouble() / cycleMs
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

// ArchiveTune's breathing-dot defaults, kept identical on every host: three 16 dp dots 12 dp apart,
// 72 dp across, in a band tall enough for the 1.3x end swell.
private const val DOTS_WIDTH_DP = 72
private const val DOTS_HEIGHT_DP = 22
private const val DOT_DIAMETER_DP = 16
private const val DOT_GAP_DP = 12

private fun DrawScope.drawInterludeDots(
    interlude: LyricsInterlude,
    positionMs: Long,
    color: Color,
    reduceMotion: Boolean,
) {
    val visibility = LyricsInterludeMotion.visibility(interlude, positionMs)
    if (visibility <= 0f) return
    val breath = LyricsInterludeMotion.scale(interlude, positionMs, reduceMotion)
    val diameter = DOT_DIAMETER_DP.dp.toPx()
    val gap = DOT_GAP_DP.dp.toPx()
    val radius = diameter / 2f * breath
    val centerY = size.height / 2f
    for (index in 0 until LyricsInterludeMotion.DOT_COUNT) {
        drawCircle(
            color = color,
            radius = radius,
            center = Offset(size.width / 2f - (diameter + gap) + index * (diameter + gap), centerY),
            alpha = visibility * LyricsInterludeMotion.dotLight(positionMs, index, reduceMotion),
        )
    }
}
