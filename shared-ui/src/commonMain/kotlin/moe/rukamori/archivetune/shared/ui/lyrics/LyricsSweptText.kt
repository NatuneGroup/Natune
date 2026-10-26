/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.ui.lyrics

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp

/** Colours for one lyric look. [glow] is the bloom behind the word being sung. */
internal data class LyricsPalette(
    val dim: Color,
    val bright: Color,
    val glow: Color,
)

/**
 * One row drawn with a word sweep. The text is shaped once per width and style through [shapes]. The frame loop
 * changes only [clock], and that is read in the draw phase, so the sweep moves without recomposing the row.
 */
@Composable
internal fun SweptLyricLine(
    row: LyricsRenderRow,
    style: TextStyle,
    palette: LyricsPalette,
    active: Boolean,
    clock: LyricsClock,
    shapes: LyricsShapeCache<LyricsShapeKey, LyricsShapedRow>,
    minSweepMs: Long,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.roundToPx() }
        val shaped = shapes.get(LyricsShapeKey(row.index, widthPx, style))
        val heightDp = with(density) { shaped.layout.size.height.toDp() }
        Box(
            Modifier
                .width(maxWidth)
                .height(heightDp)
                // The sweep is drawn on canvas, so the source text is exposed to semantics here.
                .semantics { text = AnnotatedString(row.text) }
                .drawBehind {
                    drawSweptLine(shaped, row, active, clock, palette, minSweepMs, reduceMotion)
                },
        )
    }
}

/**
 * Draws one row in the draw phase. Inactive rows draw once in the dim colour and never read the clock, so they do
 * not redraw each frame. An active row draws dim text, then clips the bright text to the sweep region.
 */
private fun DrawScope.drawSweptLine(
    shaped: LyricsShapedRow,
    row: LyricsRenderRow,
    active: Boolean,
    clock: LyricsClock,
    palette: LyricsPalette,
    minSweepMs: Long,
    reduceMotion: Boolean,
) {
    val layout = shaped.layout
    if (!active) {
        drawText(layout, color = palette.dim)
        return
    }
    val boxes = shaped.boxes
    if (boxes.isEmpty()) {
        drawText(layout, color = palette.bright)
        return
    }
    val position = clock.feedMs.longValue
    if (!reduceMotion) drawGlow(shaped, boxes, position, palette)
    drawText(layout, color = palette.dim)
    val rects = LyricsSweepGeometry.clipRects(boxes, shaped.bands, position, row.isRtl, reduceMotion, minSweepMs)
    if (rects.isEmpty()) return
    val path = Path()
    for (rect in rects) {
        path.addRect(Rect(rect.left, rect.top, rect.right, rect.bottom))
    }
    clipPath(path) { drawText(layout, color = palette.bright) }
}

/**
 * The bloom behind the word being sung. It is drawn with a colour alpha, not a layer alpha, so the text keeps its
 * own opacity while the glow rises and falls.
 */
private fun DrawScope.drawGlow(
    shaped: LyricsShapedRow,
    boxes: List<LyricsWordBox>,
    position: Long,
    palette: LyricsPalette,
) {
    val index = LyricsSweepGeometry.activeIndex(boxes, position)
    if (index < 0) return
    val box = boxes[index]
    val glow = LyricsSweepGeometry.glowIntensity(box, position)
    val width = box.rightPx - box.leftPx
    if (glow <= 0f || width <= 0f) return
    val top = shaped.bands.tops[box.line]
    val bottom = shaped.bands.bottoms[box.line]
    drawRoundRect(
        color = palette.glow.copy(alpha = palette.glow.alpha * GLOW_ALPHA * glow),
        topLeft = Offset(box.leftPx, top),
        size = Size(width, bottom - top),
        cornerRadius = CornerRadius(GLOW_CORNER_DP.dp.toPx()),
    )
}

private const val GLOW_ALPHA = 0.35f
private const val GLOW_CORNER_DP = 8
