package moe.rukamori.archivetune.ui.component

import kotlin.math.abs

/**
 * How far a row sits from the sung line. The further it is, the smaller, fainter and softer it reads, so the eye stays on
 * the line being sung.
 */
internal data class EnhancedDepth(
    val scale: Float,
    val blurDp: Float,
    val alpha: Float,
) {
    companion object {
        val Flat = EnhancedDepth(scale = 1f, blurDp = 0f, alpha = 1f)
    }
}

/**
 * The ENHANCED list's motion rules as plain functions, so the stagger cap, the depth steps and the blur gate can be checked
 * without a composition.
 */
internal object EnhancedLyricsMotion {
    /** Unsung lines sit at this alpha. The swept line dims its unsung words to the same value. */
    const val INACTIVE_ALPHA = 0.4f

    /** A line change ripples outward: each row waits one step per row of distance from the sung line. */
    const val STAGGER_STEP_MS = 45L

    /** Rows further than this share the furthest step, so a far jump settles in the same time as a near one. */
    const val MAX_STEPS = 4

    /** Below this the softening is invisible, and skipping it spares the row an offscreen layer. */
    const val BLUR_VISIBLE_DP = 0.05f

    private const val SCALE_STEP = 0.025f

    /** Every non-sung row gets the same soft veil while the list follows the playhead, not a per-step ramp. */
    private const val AUTO_BLUR_DP = 2f
    private const val ALPHA_STEP = 0.12f

    /** Rows from the sung line, or null for the sung row itself and while no line is sung yet. */
    fun distance(index: Int, primary: Int): Int? =
        if (primary < 0 || index == primary) null else abs(index - primary)

    /** How long a row waits before it follows a line change. The sung row and rows with no distance wait not at all. */
    fun staggerDelayMs(distance: Int?): Long =
        if (distance == null || distance <= 0) 0L else STAGGER_STEP_MS * distance.coerceAtMost(MAX_STEPS)

    /** Reduced motion keeps every row at full size, so a line change never animates a scale. */
    fun depthOf(distance: Int?, reduceMotion: Boolean): EnhancedDepth {
        if (distance == null || distance <= 0) return EnhancedDepth.Flat
        val steps = distance.coerceAtMost(MAX_STEPS)
        return EnhancedDepth(
            scale = if (reduceMotion) 1f else 1f - SCALE_STEP * steps,
            blurDp = AUTO_BLUR_DP,
            alpha = 1f - ALPHA_STEP * steps,
        )
    }

    /**
     * Whether rows may be softened by distance. The user's line-blur setting decides first. The platform render effect
     * (Android 12+) is required, and the blur is lifted under reduced motion, on a low-end device, and while the user is
     * scrolling, so the lines being read stay sharp.
     */
    fun blurAllowed(
        blurPreference: Boolean,
        blurRenderAllowed: Boolean,
        animationsDisabled: Boolean,
        lowEnd: Boolean,
        manualScrolling: Boolean,
    ): Boolean =
        blurPreference && blurRenderAllowed && !animationsDisabled && !lowEnd && !manualScrolling
}
