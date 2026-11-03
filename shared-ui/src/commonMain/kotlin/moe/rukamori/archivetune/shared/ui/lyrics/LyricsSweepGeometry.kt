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
    /** True when the word's glyphs sit on more than one visual line; a completion lift skips it. */
    val wrapsLines: Boolean = false,
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
    ): Float = progressAt(box.startMs, box.endMs, positionMs, reduceMotion, minSweepMs)

    /**
     * The same sweep share for a word that runs [startMs] to [endMs], for callers that do not lay text
     * out into [LyricsWordBox]es — the platform lyric renderer is one.
     */
    fun progressAt(
        startMs: Long,
        endMs: Long,
        positionMs: Long,
        reduceMotion: Boolean,
        minSweepMs: Long = MIN_SWEEP_MS,
    ): Float {
        if (reduceMotion) return if (positionMs >= startMs) 1f else 0f
        if (positionMs <= startMs) return 0f
        val duration = maxOf(endMs - startMs, minSweepMs).toFloat()
        return ((positionMs - startMs) / duration).coerceIn(0f, 1f)
    }

    /** A word swept to [progress] is finished: it draws lit to its own bounds, without the feathered edge. */
    fun sweepComplete(progress: Float): Boolean = progress >= 1f

    /** The longest a completion lift may run; a word never rises longer than this. */
    const val LIFT_MAX_MS = 300L

    /** The shortest lift window: even a tiny word reads its landing, never a snap. */
    const val LIFT_MIN_MS = 120L

    /** How far an unstarted word sits below its seat and rises through its lift, in dp. */
    const val WORD_LIFT_DP = 2f

    /**
     * The window a word's completion lift runs in: half its own span, clamped to
     * [LIFT_MIN_MS]..[LIFT_MAX_MS], and never longer than the silence to the next word past
     * [LIFT_MIN_MS] — a tight hand-off still gets the floor so the rise does not outrun the
     * following word's own lift.
     */
    fun liftDurationMs(wordStartMs: Long, wordEndMs: Long, nextWordStartMs: Long): Long {
        val wordMs = (wordEndMs - wordStartMs).coerceAtLeast(0L)
        val gapMs = (nextWordStartMs - wordEndMs).coerceAtLeast(0L)
        val base = (wordMs / 2).coerceIn(LIFT_MIN_MS, LIFT_MAX_MS)
        return base.coerceAtMost(maxOf(gapMs, LIFT_MIN_MS))
    }

    /**
     * The instant a word's lift begins: inside the word, never before its start, so the rise
     * overlaps the sweep's tail instead of waiting for it. For a word at least as long as its
     * window that is [wordEndMs] - [liftDurationMs]; a shorter word starts lifting at its start.
     */
    fun liftStartMs(wordStartMs: Long, wordEndMs: Long, nextWordStartMs: Long): Long =
        wordEndMs - minOf(
            liftDurationMs(wordStartMs, wordEndMs, nextWordStartMs),
            (wordEndMs - wordStartMs).coerceAtLeast(0L),
        )

    /** The instant a word's lift has fully settled — the end of its live span. */
    fun liftEndMs(wordStartMs: Long, wordEndMs: Long, nextWordStartMs: Long): Long =
        liftStartMs(wordStartMs, wordEndMs, nextWordStartMs) +
            liftDurationMs(wordStartMs, wordEndMs, nextWordStartMs)

    /**
     * How far a word has risen into its seat at [positionMs], from 0 to 1: an ease-out over
     * [liftStartMs]..[liftEndMs]. Unsung words sit at 0 — [WORD_LIFT_DP] below their seat — and
     * each word eases up while the sweep finishes it, with no pause and no jump. Reduced motion is
     * always settled (1), so the word sits where it is sung, the same no-bounce behaviour as before.
     * A pure function of the position, so a seek lands on the right frame.
     */
    fun liftAt(
        wordStartMs: Long,
        wordEndMs: Long,
        nextWordStartMs: Long,
        positionMs: Long,
        reduceMotion: Boolean,
    ): Float {
        if (reduceMotion) return 1f
        val durationMs = liftDurationMs(wordStartMs, wordEndMs, nextWordStartMs)
        if (durationMs <= 0L) return 1f
        val startMs = wordEndMs - minOf(durationMs, (wordEndMs - wordStartMs).coerceAtLeast(0L))
        val t = ((positionMs - startMs).toFloat() / durationMs).coerceIn(0f, 1f)
        val inv = 1f - t
        return 1f - inv * inv * inv
    }

    /**
     * How long an active row's unsung words take to settle into their low seat, ending at the row's
     * first word. Inactive rows draw seated, so without this the whole unsung row would drop
     * [WORD_LIFT_DP] in one frame when it lights. It stays inside the shortest activation lead
     * ([LyricsActivation.LEAD_DEFAULT_MS]), so a row that lights on its lead starts the sink from the seat.
     */
    const val SINK_MS = 200L

    /**
     * How far an active row has sunk into its low seat at [positionMs], from 0 (seated, as an inactive
     * row draws) to 1 (every unsung word one lift low): a smoothstep over the [SINK_MS] before
     * [firstWordStartMs]. Reduced motion never sinks.
     */
    fun sinkAt(firstWordStartMs: Long, positionMs: Long, reduceMotion: Boolean): Float {
        if (reduceMotion) return 0f
        val t = ((positionMs - (firstWordStartMs - SINK_MS)).toFloat() / SINK_MS).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /**
     * How far below its seat a word draws at [positionMs], as a share of [WORD_LIFT_DP]: the row's
     * [sinkAt] times what is left of the word's [liftAt] rise. Every renderer offsets a word by exactly
     * this, so the sink, the low wait and the completion rise are one continuous curve per word.
     */
    fun dropAt(
        firstWordStartMs: Long,
        wordStartMs: Long,
        wordEndMs: Long,
        nextWordStartMs: Long,
        positionMs: Long,
        reduceMotion: Boolean,
    ): Float =
        sinkAt(firstWordStartMs, positionMs, reduceMotion) *
            (1f - liftAt(wordStartMs, wordEndMs, nextWordStartMs, positionMs, reduceMotion))

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
