package moe.rukamori.archivetune.shared.ui.lyrics

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Fades the top and bottom edges of a lyrics box. The ramps belong to the box, not to any line: they
 * stay put while the list scrolls under them, whichever lines happen to be there.
 *
 * The mask is drawn into the box's own offscreen layer, so it only takes alpha away from the lyrics.
 * A glass, Haze or artwork backdrop drawn behind the box is not inside that layer and is never faded.
 * Each ramp is limited to [maxEdgeFraction] of the box height, so a short card gets proportionally
 * shorter fades instead of a mask that swallows it.
 */
fun Modifier.lyricsViewportFade(
    top: Dp = LyricsViewportFade.DefaultTop,
    bottom: Dp = LyricsViewportFade.DefaultBottom,
    maxEdgeFraction: Float = LyricsViewportFade.DEFAULT_MAX_EDGE_FRACTION,
): Modifier =
    graphicsLayer {
        compositingStrategy = CompositingStrategy.Offscreen
        // The layer clip also bounds hit testing, so content laid out past the box edge cannot take taps.
        clip = true
    }.drawWithCache {
        val stops = LyricsViewportFade.alphaStops(size.height, top.toPx(), bottom.toPx(), maxEdgeFraction)
        val brush =
            stops?.let { list ->
                Brush.verticalGradient(
                    *Array(list.size) { i -> list[i].position to Color.Black.copy(alpha = list[i].alpha) },
                )
            }
        onDrawWithContent {
            drawContent()
            if (brush != null) drawRect(brush = brush, blendMode = BlendMode.DstIn)
        }
    }

/** Geometry of [lyricsViewportFade], kept free of Compose drawing so it can be checked directly. */
object LyricsViewportFade {
    val DefaultTop: Dp = 56.dp
    val DefaultBottom: Dp = 96.dp
    const val DEFAULT_MAX_EDGE_FRACTION: Float = 0.22f

    private const val RAMP_STEPS = 8

    data class Stop(val position: Float, val alpha: Float)

    /** Top and bottom ramp heights in pixels for a box [heightPx] tall. */
    fun edgeHeights(heightPx: Float, topPx: Float, bottomPx: Float, maxEdgeFraction: Float): Pair<Float, Float> {
        if (heightPx <= 0f) return 0f to 0f
        val cap = heightPx * maxEdgeFraction.coerceIn(0f, 0.5f)
        return topPx.coerceIn(0f, cap) to bottomPx.coerceIn(0f, cap)
    }

    /**
     * Mask stops (position 0..1 down the box, mask alpha) or null when nothing would fade. Each ramp is
     * smoothstep-eased, so the lines dissolve into the edge rather than meeting a visible band.
     */
    fun alphaStops(heightPx: Float, topPx: Float, bottomPx: Float, maxEdgeFraction: Float): List<Stop>? {
        val (top, bottom) = edgeHeights(heightPx, topPx, bottomPx, maxEdgeFraction)
        if (top < 1f && bottom < 1f) return null
        val stops = ArrayList<Stop>(RAMP_STEPS * 2 + 2)
        if (top >= 1f) {
            for (step in 0..RAMP_STEPS) {
                val t = step.toFloat() / RAMP_STEPS
                stops += Stop(t * top / heightPx, smoothstep(t))
            }
        } else {
            stops += Stop(0f, 1f)
        }
        if (bottom >= 1f) {
            val start = (heightPx - bottom) / heightPx
            for (step in 0..RAMP_STEPS) {
                val t = step.toFloat() / RAMP_STEPS
                stops += Stop(start + t * bottom / heightPx, smoothstep(1f - t))
            }
        } else {
            stops += Stop(1f, 1f)
        }
        return stops
    }

    private fun smoothstep(t: Float): Float = t * t * (3f - 2f * t)
}
