package moe.rukamori.archivetune.shared.ui.lyrics

import kotlin.math.abs

/**
 * How a row reads by its distance from the sung line: rows further away are a little smaller, fainter and softer,
 * so the eye stays on the active line. A sung row and every row of unsynced text are [Flat].
 */
internal data class LyricsDepth(
    val scale: Float,
    val blurDp: Float,
    val alpha: Float,
) {
    companion object {
        val Flat = LyricsDepth(scale = 1f, blurDp = 0f, alpha = 1f)

        /** Rows further than this read like the furthest step, so a long list keeps a steady look. */
        const val MAX_STEPS = 4
        private const val SCALE_STEP = 0.025f
        private const val BLUR_STEP_DP = 0.75f
        private const val ALPHA_STEP = 0.12f

        /** Steps from the primary row, or null while nothing is sung yet or the row itself is sung. */
        fun distance(index: Int, primary: Int, active: Boolean): Int? =
            if (primary < 0 || active) null else abs(index - primary)

        /** Reduced motion keeps every row at full size, so a line change never animates a scale. */
        fun of(distance: Int?, reduceMotion: Boolean): LyricsDepth {
            if (distance == null || distance <= 0) return Flat
            val steps = distance.coerceAtMost(MAX_STEPS)
            return LyricsDepth(
                scale = if (reduceMotion) 1f else 1f - SCALE_STEP * steps,
                blurDp = BLUR_STEP_DP * steps,
                alpha = 1f - ALPHA_STEP * steps,
            )
        }
    }
}
