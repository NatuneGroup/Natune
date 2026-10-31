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
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
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
 * [refined] selects the ENHANCED sweep: a feathered edge, and glow only for words held long enough.
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
    refined: Boolean = false,
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
                    drawSweptLine(shaped, row, active, clock, palette, minSweepMs, reduceMotion, refined)
                },
        )
    }
}

/**
 * Draws one row in the draw phase. Inactive rows draw once in the dim colour and never read the clock, so they do
 * not redraw each frame. An active row draws dim text, then brightens the sung part. The default sweep clips at a
 * hard edge. The refined sweep feathers that edge, and only with motion on, since reduced motion jumps word to word.
 */
private fun DrawScope.drawSweptLine(
    shaped: LyricsShapedRow,
    row: LyricsRenderRow,
    active: Boolean,
    clock: LyricsClock,
    palette: LyricsPalette,
    minSweepMs: Long,
    reduceMotion: Boolean,
    refined: Boolean,
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
    if (!reduceMotion) drawGlow(shaped, boxes, position, palette, refined)
    if (refined && !reduceMotion) {
        drawRefinedLine(shaped, boxes, position, row.isRtl, minSweepMs, palette)
        return
    }
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
 * The refined sweep of an active row. The dim line draws first. A word held for at least a second lifts while it is
 * sung: it is cut out of the line and drawn again higher, so the lift moves that word alone.
 */
private fun DrawScope.drawRefinedLine(
    shaped: LyricsShapedRow,
    boxes: List<LyricsWordBox>,
    position: Long,
    rtl: Boolean,
    minSweepMs: Long,
    palette: LyricsPalette,
) {
    val layout = shaped.layout
    val box = boxes.getOrNull(LyricsSweepGeometry.activeIndex(boxes, position))
    val lift = if (box == null) 0f else LIFT_MAX_DP.dp.toPx() * LyricsSweepGeometry.liftShare(box, position)
    val hole = if (box != null && lift > 0f) {
        Rect(box.leftPx, shaped.bands.tops[box.line], box.rightPx, shaped.bands.bottoms[box.line])
    } else {
        null
    }
    if (hole == null) {
        drawText(layout, color = palette.dim)
    } else {
        clipPath(outsideOf(hole)) { drawText(layout, color = palette.dim) }
    }
    drawFeatheredSweep(shaped, boxes, position, rtl, minSweepMs, palette, hole)
    if (box != null && hole != null) {
        drawLiftedWord(layout, hole, lift, sweepEdge(box, position, rtl, minSweepMs), rtl, palette)
    }
}

/** The x position of the sweep edge inside [box], the same edge [LyricsSweepGeometry.clipRects] draws. */
private fun sweepEdge(box: LyricsWordBox, positionMs: Long, rtl: Boolean, minSweepMs: Long): Float {
    val progress = LyricsSweepGeometry.progressAt(box, positionMs, reduceMotion = false, minSweepMs = minSweepMs)
    val span = box.rightPx - box.leftPx
    return if (rtl) box.rightPx - progress * span else box.leftPx + progress * span
}

/**
 * The refined sweep of the sung line. Lines above the sung one are fully bright, as before. The sung line is bright up
 * to the sweep edge and fades to dim across [WIPE_FEATHER_DP] either side of it, so the fill has a soft leading edge.
 * The fade is a mask on a layer limited to the sung line's band, so the lines above and below are left alone. A
 * [hole] is left out of the sung line, because that word is drawn lifted instead.
 */
private fun DrawScope.drawFeatheredSweep(
    shaped: LyricsShapedRow,
    boxes: List<LyricsWordBox>,
    position: Long,
    rtl: Boolean,
    minSweepMs: Long,
    palette: LyricsPalette,
    hole: Rect?,
) {
    val rects = LyricsSweepGeometry.clipRects(boxes, shaped.bands, position, rtl, false, minSweepMs)
    if (rects.isEmpty()) return
    val layout = shaped.layout
    val earlier = rects.dropLast(1)
    if (earlier.isNotEmpty()) {
        val path = Path()
        for (rect in earlier) {
            path.addRect(Rect(rect.left, rect.top, rect.right, rect.bottom))
        }
        clipPath(path) { drawText(layout, color = palette.bright) }
    }
    val sung = rects.last()
    val top = sung.top
    val height = sung.bottom - sung.top
    val width = shaped.bands.widthPx
    val edge = if (rtl) sung.left else sung.right
    val feather = WIPE_FEATHER_DP.dp.toPx()
    val fade = if (rtl) {
        listOf(Color.Transparent, Color.Black)
    } else {
        listOf(Color.Black, Color.Transparent)
    }
    val mask = Brush.horizontalGradient(fade, startX = edge - feather, endX = edge + feather)
    drawIntoCanvas { canvas ->
        canvas.saveLayer(Rect(0f, top, width, top + height), Paint())
        if (hole == null) {
            drawText(layout, color = palette.bright)
        } else {
            clipPath(outsideOf(hole)) { drawText(layout, color = palette.bright) }
        }
        drawRect(brush = mask, topLeft = Offset(0f, top), size = Size(width, height), blendMode = BlendMode.DstIn)
        canvas.restore()
    }
}

/**
 * The lifted word: its dim fill, then its sung fill with the same feathered edge, drawn [lift] pixels higher inside
 * its own box. The clip is the shifted box, so only that word's band shows, and the text of the lines above and below
 * cannot show through.
 */
private fun DrawScope.drawLiftedWord(
    layout: TextLayoutResult,
    hole: Rect,
    lift: Float,
    edge: Float,
    rtl: Boolean,
    palette: LyricsPalette,
) {
    val window = Rect(hole.left, hole.top - lift, hole.right, hole.bottom - lift)
    val offset = Offset(0f, -lift)
    val feather = WIPE_FEATHER_DP.dp.toPx()
    val fade = if (rtl) {
        listOf(Color.Transparent, Color.Black)
    } else {
        listOf(Color.Black, Color.Transparent)
    }
    val mask = Brush.horizontalGradient(fade, startX = edge - feather, endX = edge + feather)
    clipPath(Path().apply { addRect(window) }) {
        drawText(layout, color = palette.dim, topLeft = offset)
        drawIntoCanvas { canvas ->
            canvas.saveLayer(window, Paint())
            drawText(layout, color = palette.bright, topLeft = offset)
            drawRect(
                brush = mask,
                topLeft = Offset(window.left, window.top),
                size = Size(window.width, window.height),
                blendMode = BlendMode.DstIn,
            )
            canvas.restore()
        }
    }
}

/** The canvas without [hole], as up to four rectangles, so a clip can leave one word out of its line. */
private fun DrawScope.outsideOf(hole: Rect): Path {
    val width = size.width
    val height = size.height
    return Path().apply {
        if (hole.top > 0f) addRect(Rect(0f, 0f, width, hole.top))
        if (hole.bottom < height) addRect(Rect(0f, hole.bottom, width, height))
        if (hole.left > 0f) addRect(Rect(0f, hole.top, hole.left, hole.bottom))
        if (hole.right < width) addRect(Rect(hole.right, hole.top, width, hole.bottom))
    }
}

/**
 * The bloom behind the word being sung. It is drawn with a colour alpha, not a layer alpha, so the text keeps its
 * own opacity while the glow rises and falls. The refined sweep scales it by how long the word is held, so quick
 * syllables stay plain.
 */
private fun DrawScope.drawGlow(
    shaped: LyricsShapedRow,
    boxes: List<LyricsWordBox>,
    position: Long,
    palette: LyricsPalette,
    refined: Boolean,
) {
    val index = LyricsSweepGeometry.activeIndex(boxes, position)
    if (index < 0) return
    val box = boxes[index]
    val gain = if (refined) LyricsSweepGeometry.emphasisGain(box) else 1f
    val glow = LyricsSweepGeometry.glowIntensity(box, position) * gain
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
private const val WIPE_FEATHER_DP = 6
private const val LIFT_MAX_DP = 2.5f
