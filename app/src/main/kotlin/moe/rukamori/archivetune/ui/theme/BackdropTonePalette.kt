package moe.rukamori.archivetune.ui.theme

import androidx.compose.ui.graphics.Color

/** Three vertical gradient stops derived from one artwork colour, tuned for a page background. */
data class BackdropTonePalette(
    val top: Color,
    val mid: Color,
    val bottom: Color,
) {
    companion object {
        /**
         * Desaturated tones of the first of [colors]: mid-dark stops in a dark theme and near-white
         * stops in a light one, so theme text stays legible on any artwork.
         */
        fun fromColorsLight(
            colors: List<Color>,
            fallbackColor: Color,
            darkTheme: Boolean,
        ): BackdropTonePalette {
            val source = colors.firstOrNull()
            val ranges = if (darkTheme) DarkRanges else LightRanges
            val fallback = fallbackColor.backdropTone(ranges.fallback.start, ranges.fallback.endInclusive, MUTED_SCALE, MUTED_CAP)
            val top = source?.backdropTone(ranges.top.start, ranges.top.endInclusive, MUTED_SCALE, MUTED_CAP) ?: fallback
            val mid = source?.backdropTone(ranges.mid.start, ranges.mid.endInclusive, MUTED_SCALE, MUTED_CAP) ?: top
            val bottom = source?.backdropTone(ranges.bottom.start, ranges.bottom.endInclusive, MUTED_SCALE, MUTED_CAP) ?: mid
            return BackdropTonePalette(top = top, mid = mid, bottom = bottom)
        }

        private class ToneRanges(
            val fallback: ClosedFloatingPointRange<Float>,
            val top: ClosedFloatingPointRange<Float>,
            val mid: ClosedFloatingPointRange<Float>,
            val bottom: ClosedFloatingPointRange<Float>,
        )

        private val DarkRanges = ToneRanges(0.12f..0.38f, 0.32f..0.66f, 0.24f..0.52f, 0.15f..0.38f)
        private val LightRanges = ToneRanges(0.72f..0.98f, 0.88f..0.98f, 0.82f..0.96f, 0.74f..0.92f)
        private const val MUTED_SCALE = 0.45f
        private const val MUTED_CAP = 0.30f
    }
}

/**
 * The colour with its HSV value clamped to [valueMin]..[valueMax] and its saturation scaled, so
 * near-grey sources stay grey instead of being pushed towards a hue they barely have.
 */
fun Color.backdropTone(
    valueMin: Float,
    valueMax: Float,
    saturationScale: Float = 1.27f,
    saturationCap: Float = 1f,
): Color {
    val r = red
    val g = green
    val b = blue
    val max = maxOf(r, g, b)
    val delta = max - minOf(r, g, b)
    val hue =
        when {
            delta == 0f -> 0f
            max == r -> 60f * (((g - b) / delta).mod(6f))
            max == g -> 60f * ((b - r) / delta + 2f)
            else -> 60f * ((r - g) / delta + 4f)
        }
    val saturation = if (max == 0f) 0f else delta / max
    val tunedSaturation =
        if (saturation < 0.12f) saturation.coerceAtMost(0.08f) else (saturation * saturationScale).coerceIn(0f, saturationCap)
    return Color.hsv(hue.coerceIn(0f, 360f), tunedSaturation, max.coerceIn(valueMin, valueMax))
}
