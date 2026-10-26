package moe.rukamori.archivetune.shared.ui.glass

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

const val NAVIGATION_BAR_GLASS_GLOW_INTENSITY_DEFAULT = 1f

private val GlowEdgeStrokeWidth = 0.75.dp

/**
 * The baseline glass glow: a bright top edge, a top-down sheen and a faint base lift drawn on top of a glass or solid
 * navigation surface. Strength scales the colour stops rather than the draw alpha — paint alpha clamps at 1, which
 * would make every intensity above 100% look the same. A strength at or below ~0 draws nothing.
 */
fun Modifier.glassGlowOverlay(
    strength: Float,
    shape: Shape,
): Modifier =
    if (strength <= 0.01f) {
        this
    } else {
        drawWithCache {
            fun glow(alpha: Float) = Color.White.copy(alpha = (alpha * strength).coerceIn(0f, 1f))
            val outline = shape.createOutline(size, layoutDirection, this)
            val corner =
                when (val r = (outline as? Outline.Rounded)?.roundRect?.topLeftCornerRadius) {
                    null -> CornerRadius(size.height / 2f)
                    else -> CornerRadius(r.x, r.y)
                }
            val stroke = GlowEdgeStrokeWidth.toPx()
            val edgeBrush = Brush.verticalGradient(listOf(glow(0.27f), glow(0.02f)))
            val sheenBrush =
                Brush.verticalGradient(
                    0f to glow(0.10f),
                    0.45f to Color.Transparent,
                    1f to glow(0.05f),
                )
            val baseBrush =
                Brush.verticalGradient(
                    0.72f to Color.Transparent,
                    1f to glow(0.14f * 0.7f),
                )
            val edgeStyle = Stroke(stroke)
            val edgeTopLeft = Offset(stroke / 2f, stroke / 2f)
            val edgeSize = Size(size.width - stroke, size.height - stroke)

            onDrawWithContent {
                drawContent()
                drawRoundRect(
                    brush = edgeBrush,
                    topLeft = edgeTopLeft,
                    size = edgeSize,
                    cornerRadius = corner,
                    style = edgeStyle,
                )
                drawRoundRect(brush = sheenBrush, cornerRadius = corner)
                drawRoundRect(brush = baseBrush, cornerRadius = corner)
            }
        }
    }
