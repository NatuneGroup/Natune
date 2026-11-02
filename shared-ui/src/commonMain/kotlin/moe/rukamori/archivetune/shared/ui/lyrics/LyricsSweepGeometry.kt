package moe.rukamori.archivetune.shared.ui.lyrics

/**
 * One word's box on the laid-out text. [leftPx] is at most [rightPx]. [line] is the visual line the word
 * starts on, and it is always below [LyricsLineBands.lineCount]. [letters] is set only on a word that
 * earned the [LyricsEmphasis] ripple: the (left, right) pair of each grapheme cluster's slice, in sung
 * order, so the draw phase can move the letters one at a time.
 */
data class LyricsWordBox(
    val startMs: Long,
    val endMs: Long,
    val leftPx: Float,
    val rightPx: Float,
    val line: Int,
    val letters: FloatArray? = null,
)

/**
 * Vertical bands of the laid-out lines plus the layout width, all in pixels. [overshootPx] is how far
 * glyphs may paint past a band's top or bottom — ascenders, descenders and accents spill over when the
 * line height falls short of the font's natural metrics — so bright clip regions extend that far past
 * each band instead of slicing the glyphs that cross it.
 */
class LyricsLineBands(
    val widthPx: Float,
    val tops: FloatArray,
    val bottoms: FloatArray,
    val overshootPx: Float = 0f,
) {
    val lineCount: Int
        get() = tops.size

    companion object {
        /** Clip regions extend this share of the font size past a band, covering glyph overhang. */
        const val OVERSHOOT_EM = 0.10f
    }
}

/** A rectangle of laid-out text that is painted in the bright state. */
data class LyricsClipRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/**
 * The draw-phase sweep. Every value is a function of the clock position, so a backward seek or a repeat
 * resets the sweep without any cached state, and a word never lights before its start.
 */
object LyricsSweepGeometry {
    /** The shortest visible sweep. A very short word still travels across its glyphs. */
    const val MIN_SWEEP_MS = 150L

    /** Glow rises over this share of a word, and falls back over [GLOW_RELEASE]. */
    const val GLOW_ATTACK = 0.18f
    const val GLOW_RELEASE = 0.38f

    /** The dimmest glow a word gets, so quick patter keeps a faint bloom. */
    const val GLOW_FLOOR = 0.22f

    /** Words at or below [GLOW_FAST_MS] get the floor; words at or above [GLOW_SLOW_MS] get the full bloom. */
    const val GLOW_FAST_MS = 130L
    const val GLOW_SLOW_MS = 800L

    /** A word earns glow only from [EMPHASIS_MIN_MS]; it reaches its full share [EMPHASIS_RAMP_MS] later. */
    const val EMPHASIS_MIN_MS = 1_000L
    const val EMPHASIS_RAMP_MS = 1_000L

    /** The feathered sweep edge is this share of the active word's width, clamped to the bounds below. */
    const val SWEEP_FEATHER_FRACTION = 0.2f
    const val SWEEP_FEATHER_MIN_DP = 4f
    const val SWEEP_FEATHER_MAX_DP = 28f

    /** The feather width in px for a word [wordWidthPx] wide, clamped to [minPx]..[maxPx]. */
    fun sweepFeatherPx(wordWidthPx: Float, minPx: Float, maxPx: Float): Float =
        (wordWidthPx * SWEEP_FEATHER_FRACTION).coerceIn(minPx, maxPx)

    /** Share of the active word that is sung, from 0 to 1. Reduced motion jumps between 0 and 1. */
    fun progressAt(
        box: LyricsWordBox,
        positionMs: Long,
        reduceMotion: Boolean,
        minSweepMs: Long = MIN_SWEEP_MS,
    ): Float {
        if (reduceMotion) return if (positionMs >= box.startMs) 1f else 0f
        if (positionMs <= box.startMs) return 0f
        val duration = maxOf(box.endMs - box.startMs, minSweepMs).toFloat()
        return ((positionMs - box.startMs) / duration).coerceIn(0f, 1f)
    }

    /** Index of the last box that has started by [positionMs], or -1 before the first word. Boxes are time ordered. */
    fun activeIndex(boxes: List<LyricsWordBox>, positionMs: Long): Int {
        var low = 0
        var high = boxes.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (boxes[mid].startMs <= positionMs) low = mid + 1 else high = mid
        }
        return low - 1
    }

    /**
     * The bright regions for [positionMs]. Earlier lines are fully bright. On the active word's line, the bright
     * region ends at the sweep edge, which is on the right for left-to-right text and on the left for
     * right-to-left text. Words after the active one stay dim.
     */
    fun clipRects(
        boxes: List<LyricsWordBox>,
        bands: LyricsLineBands,
        positionMs: Long,
        rtl: Boolean,
        reduceMotion: Boolean,
        minSweepMs: Long = MIN_SWEEP_MS,
    ): List<LyricsClipRect> {
        val index = activeIndex(boxes, positionMs)
        if (index < 0 || bands.lineCount == 0) return emptyList()
        val box = boxes[index]
        val progress = progressAt(box, positionMs, reduceMotion, minSweepMs)
        val span = box.rightPx - box.leftPx
        val edge = if (rtl) box.rightPx - progress * span else box.leftPx + progress * span
        val overshoot = bands.overshootPx
        val rects = ArrayList<LyricsClipRect>(box.line + 1)
        for (line in 0 until box.line) {
            rects += LyricsClipRect(0f, bands.tops[line] - overshoot, bands.widthPx, bands.bottoms[line] + overshoot)
        }
        val top = bands.tops[box.line] - overshoot
        val bottom = bands.bottoms[box.line] + overshoot
        rects += if (rtl) {
            LyricsClipRect(edge, top, bands.widthPx, bottom)
        } else {
            LyricsClipRect(0f, top, edge, bottom)
        }
        return rects
    }

    /**
     * The glow for the word at [positionMs], from 0 to 1. It follows the Android envelope: a window at the start
     * and another at the end, scaled by how long the word is held. Outside the word it is zero.
     */
    fun glowIntensity(box: LyricsWordBox, positionMs: Long): Float {
        if (positionMs < box.startMs || positionMs >= box.endMs) return 0f
        val held = (box.endMs - box.startMs).coerceAtLeast(1L)
        val through = ((positionMs - box.startMs).toFloat() / held).coerceIn(0f, 1f)
        val envelope = when {
            through < GLOW_ATTACK -> through / GLOW_ATTACK
            through > 1f - GLOW_RELEASE -> (1f - through) / GLOW_RELEASE
            else -> 1f
        }
        val pace = ((held - GLOW_FAST_MS).toFloat() / (GLOW_SLOW_MS - GLOW_FAST_MS)).coerceIn(0f, 1f)
        return (GLOW_FLOOR + (1f - GLOW_FLOOR) * pace) * envelope.coerceIn(0f, 1f)
    }

    /**
     * How much of its glow a word earns, from 0 to 1. A word shorter than [EMPHASIS_MIN_MS] earns none, so quick
     * syllables do not flicker. From there the share rises from half to full over [EMPHASIS_RAMP_MS].
     */
    fun emphasisGain(box: LyricsWordBox): Float {
        val held = box.endMs - box.startMs
        if (held < EMPHASIS_MIN_MS) return 0f
        return (0.5f + 0.5f * ((held - EMPHASIS_MIN_MS).toFloat() / EMPHASIS_RAMP_MS)).coerceAtMost(1f)
    }

}
