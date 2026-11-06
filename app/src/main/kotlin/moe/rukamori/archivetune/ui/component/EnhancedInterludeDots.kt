package moe.rukamori.archivetune.ui.component

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import com.mocharealm.accompanist.lyrics.core.model.ISyncedLine
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import kotlin.math.PI
import kotlin.math.cos
import moe.rukamori.archivetune.shared.ui.lyrics.LyricsActivation

/**
 * Silences shorter than this read as ordinary phrasing and keep the plain line rhythm. Matches the shared renderer's
 * LyricsInterludes.MIN_BREAK_MS, which is internal to shared-ui and so cannot be referenced from here.
 */
internal const val ENHANCED_INTERLUDE_MIN_BREAK_MS = 9_000L

/** A silence on the lyrics timeline long enough to show the dots. [endMs] is when the next sung line starts. */
internal data class EnhancedInterlude(
    val startMs: Long,
    val endMs: Long,
) {
    val durationMs: Long
        get() = endMs - startMs
}

/**
 * The silences long enough to show the dots, keyed by the index of the line whose item shows them. A silence is the gap
 * between the furthest point the earlier lines reach and the next line's start, so overlapping duet lines never hide a
 * real break. A line with no usable end is sung until the next one starts, so it leaves no break behind it.
 */
internal fun enhancedInterludes(starts: IntArray, ends: IntArray): Map<Int, EnhancedInterlude> {
    val result = HashMap<Int, EnhancedInterlude>()
    var reachedMs = 0L
    for (index in starts.indices) {
        val startMs = starts[index].toLong()
        val silentFromMs = minOf(reachedMs, startMs)
        if (startMs - silentFromMs >= ENHANCED_INTERLUDE_MIN_BREAK_MS) {
            result[index] = EnhancedInterlude(silentFromMs, startMs)
        }
        val endMs = ends[index].toLong()
        val nextStartMs = starts.getOrNull(index + 1)?.toLong() ?: startMs
        reachedMs = maxOf(reachedMs, if (endMs > startMs) endMs else nextStartMs)
    }
    return result
}

/**
 * Whether a position sits inside a break the in-house list draws its dots over, so the position loop
 * keeps stepping frames there instead of idle-polling. The breaks are re-derived only when [lyrics]
 * hands back a new object, because the loop asks every iteration.
 */
internal class EnhancedInterludeGate(private val lyrics: () -> SyncedLyrics) {
    private var derivedFrom: SyncedLyrics? = null
    private var breaks: Collection<EnhancedInterlude> = emptyList()

    fun covers(positionMs: Long): Boolean {
        val current = lyrics()
        if (current !== derivedFrom) {
            derivedFrom = current
            val lines = current.lines
            breaks = enhancedInterludes(IntArray(lines.size) { lines[it].start }, IntArray(lines.size) { lines[it].end }).values
        }
        return breaks.any { positionMs >= it.startMs && positionMs < it.endMs }
    }
}

/**
 * Whether the row carries real per-word timing. A line-synced row built as a [KaraokeLine] only to hang
 * a romanisation on has every "syllable" pegged at [MIN_KARAOKE_SYLLABLE_DURATION_MS] and every one of
 * them starting at the line's own time; a word-synced row has at least one genuinely timed word and
 * more than one start. Both conditions are required: a single word spanning the line is a line-synced
 * line too, and sweeping it would light the row in one go.
 */
internal fun ISyncedLine.hasWordTiming(): Boolean {
    if (this !is KaraokeLine) return false
    val timed = syllables
    if (timed.size < 2) return false
    if (timed.all { it.start == timed.first().start }) return false
    return timed.any { it.end - it.start > MIN_KARAOKE_SYLLABLE_DURATION_MS }
}

/**
 * When each line activates, [LyricsActivation.leadMs] ahead of its timed start, measured against the
 * furthest point the earlier lines reach — the same gap the interlude dots measure. The word sweep
 * still follows the real word times; only the lit line moves early, so a seek lands on the same frame.
 *
 * [wordSynced] marks the rows that carry word timing, and only those take the lead: on a line-synced
 * row the lead stacked on the LRC lead and the visual tuning offset and lit it up to a second before it
 * was sung, so line-synced rows activate at their timed start.
 */
internal fun enhancedActivationStarts(
    starts: IntArray,
    ends: IntArray,
    wordSynced: BooleanArray,
): IntArray {
    val result = IntArray(starts.size)
    var reachedMs = 0L
    for (index in starts.indices) {
        val startMs = starts[index].toLong()
        // Rows sharing a timed start light together: a duet voice reuses the lead of the row before it.
        val lead =
            if (index > 0 && starts[index] == starts[index - 1]) {
                startMs - result[index - 1]
            } else if (index < wordSynced.size && wordSynced[index]) {
                LyricsActivation.leadMs(if (reachedMs > 0L) startMs - reachedMs else null)
            } else {
                0L
            }
        // A lead never reaches back past the row before it: a word-synced row right after a line-synced
        // one would otherwise light first, and the binary search over these starts needs them in order.
        val previousMs = if (index > 0) result[index - 1].toLong() else null
        val cappedLead = if (previousMs == null) lead else lead.coerceIn(0L, maxOf(0L, startMs - previousMs))
        result[index] = (startMs - cappedLead).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
        reachedMs = maxOf(reachedMs, ends[index].toLong())
    }
    return result
}

/** The dots' motion over one break. Each function reads the position and keeps no state, so a seek lands on the right frame. */
internal object EnhancedInterludeMotion {
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
     * [END_SWELL_MS] of the break, easing to [END_SWELL_TO] as the next line arrives.
     *
     * It is a factor rather than a second scale the draw picks with `maxOf`, which is what made the
     * indicator jump: a `maxOf` against a function that started at 1.05 snapped the dots 5% larger on
     * the frame the window opened, and snapped them back when it closed. A factor of 1 is neutral in
     * a product, so the curve leaves and re-enters the breath with nothing to see.
     */
    fun endSwell(interlude: EnhancedInterlude, positionMs: Long): Float {
        val remaining = interlude.endMs - positionMs
        if (remaining <= 0L || remaining > END_SWELL_MS) return 1f
        val through = 1f - remaining.toFloat() / END_SWELL_MS
        // Smoothstep, so the swell starts and stops without a corner.
        val eased = through * through * (3f - 2f * through)
        return 1f + (END_SWELL_TO - 1f) * eased
    }

    /**
     * The dots' scale at [positionMs]: the slow breath, multiplied by the end swell. A product of two
     * continuous, seamless cycles, so the indicator never restarts and never jumps — at a break's end
     * the factor returns to 1 and the breath carries on where it was.
     */
    fun scale(interlude: EnhancedInterlude, positionMs: Long, reduceMotion: Boolean): Float {
        val breath =
            if (reduceMotion) {
                1f
            } else {
                breath(positionMs)
            }
        return breath * endSwell(interlude, positionMs)
    }

    /** How visible the whole indicator is at [positionMs]. It fades in as the break starts and out before it ends. */
    fun visibility(interlude: EnhancedInterlude, positionMs: Long): Float {
        if (positionMs < interlude.startMs || positionMs >= interlude.endMs) return 0f
        val enter = (positionMs - interlude.startMs).toFloat() / ENTER_MS
        val exit = (interlude.endMs - positionMs).toFloat() / EXIT_MS
        return minOf(enter, exit, 1f)
    }

    /**
     * How lit dot [index] is at [positionMs], from [IDLE_LIGHT] to 1: a travelling pulse whose per-dot
     * brightness is one half of a cosine, each dot a third of a cycle behind the one before it.
     *
     * The loop is seamless by construction. A cosine of the phase returns the same value and the same
     * slope at the wrap, so the pulse never restarts; and because the phase comes from the lyrics
     * timeline rather than from the break's own progress, a long break and a short one pulse at the same
     * rhythm instead of a fill that begins again at every break. Reduced motion lights one dot at a time
     * without easing, so nothing moves but the order does.
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
 * Three dots that light in turn over an instrumental break, with a slow breath. They read the lyrics position in the draw
 * phase, so they move without recomposing the row, and they draw nothing outside their break.
 */
@Composable
internal fun EnhancedInterludeDots(
    interlude: EnhancedInterlude,
    currentPosition: () -> Int,
    color: Color,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    Spacer(
        modifier
            .size(width = DOTS_WIDTH_DP.dp, height = DOTS_HEIGHT_DP.dp)
            .drawBehind {
                drawEnhancedInterludeDots(interlude, currentPosition().toLong(), color, reduceMotion)
            },
    )
}

// The size ArchiveTune's breathing dots draw at, which is what they were asked to match:
// KaraokeBreathingDotsDefaults is `size = 16.dp`, `margin = 12.dp`, three of them — 3 * 16 + 2 * 12 =
// 72 dp wide. The previous 14 dp dots 8 dp apart read as three specks under a 26 sp lyric line. The
// band is 22 dp tall: 16 dp for the dot and the room the 1.3x end swell needs.
internal const val DOTS_WIDTH_DP = 72
internal const val DOTS_HEIGHT_DP = 22
internal const val DOT_DIAMETER_DP = 16
internal const val DOT_GAP_DP = 12

private fun DrawScope.drawEnhancedInterludeDots(
    interlude: EnhancedInterlude,
    positionMs: Long,
    color: Color,
    reduceMotion: Boolean,
) {
    val visibility = EnhancedInterludeMotion.visibility(interlude, positionMs)
    if (visibility <= 0f) return
    val breath = EnhancedInterludeMotion.scale(interlude, positionMs, reduceMotion)
    val diameter = DOT_DIAMETER_DP.dp.toPx()
    val gap = DOT_GAP_DP.dp.toPx()
    val radius = diameter / 2f * breath
    val centerY = size.height / 2f
    for (index in 0 until EnhancedInterludeMotion.DOT_COUNT) {
        drawCircle(
            color = color,
            radius = radius,
            center = Offset(size.width / 2f - (diameter + gap) + index * (diameter + gap), centerY),
            alpha = visibility * EnhancedInterludeMotion.dotLight(positionMs, index, reduceMotion),
        )
    }
}
