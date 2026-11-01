package moe.rukamori.archivetune.ui.component

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeSyllable
import moe.rukamori.archivetune.lyrics.LyricsUtils
import moe.rukamori.archivetune.shared.domain.lyrics.unicode.GraphemeClusterSegmenter
import moe.rukamori.archivetune.shared.ui.lyrics.LyricsEmphasis
import moe.rukamori.archivetune.shared.ui.lyrics.LyricsClipRect
import moe.rukamori.archivetune.shared.ui.lyrics.LyricsLineBands
import moe.rukamori.archivetune.shared.ui.lyrics.LyricsSweepGeometry
import moe.rukamori.archivetune.shared.ui.lyrics.LyricsWordBox

/** Colours of one swept line: [dim] is the unsung text, [bright] the sung part and [glow] the bloom behind a held word. */
internal data class EnhancedLyricPalette(
    val dim: Color,
    val bright: Color,
    val glow: Color,
)

/**
 * The active karaoke line drawn with a word sweep. The text is shaped once per width and style, and the sweep is read in the
 * draw phase from [currentPosition], so the sweep moves without recomposing the row. Only a word held for at least a second
 * earns a glow and a lift, so quick syllables stay plain. Reduced motion jumps from word to word instead.
 */
@Composable
internal fun EnhancedSweptLine(
    syllables: List<KaraokeSyllable>,
    style: TextStyle,
    textColor: Color,
    reduceMotion: Boolean,
    currentPosition: () -> Int,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val palette = remember(textColor) {
        EnhancedLyricPalette(
            dim = textColor.copy(alpha = EnhancedLyricsMotion.INACTIVE_ALPHA),
            bright = textColor,
            glow = textColor,
        )
    }
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.roundToPx() }
        val shaped = remember(syllables, widthPx, style, measurer) {
            shapeEnhancedSyllables(measurer, syllables, style, widthPx)
        }
        Spacer(
            Modifier
                .width(maxWidth)
                .height(with(density) { shaped.layout.size.height.toDp() })
                // The sweep is drawn on canvas, so the sung text is exposed to semantics here.
                .semantics { text = AnnotatedString(shaped.text) }
                .drawBehind {
                    drawEnhancedSweep(shaped, currentPosition().toLong(), reduceMotion, palette)
                },
        )
    }
}

/** The whole line in one pass when motion is reduced, otherwise the feathered sweep with its glow and lift. */
private fun DrawScope.drawEnhancedSweep(
    shaped: EnhancedShapedLine,
    positionMs: Long,
    reduceMotion: Boolean,
    palette: EnhancedLyricPalette,
) {
    val layout = shaped.layout
    val boxes = shaped.boxes
    if (boxes.isEmpty()) {
        drawText(layout, color = palette.bright)
        return
    }
    if (reduceMotion) {
        drawText(layout, color = palette.dim)
        val rects = LyricsSweepGeometry.clipRects(boxes, shaped.bands, positionMs, shaped.rtl, reduceMotion = true)
        if (rects.isNotEmpty()) {
            clipPath(rectPath(rects)) { drawText(layout, color = palette.bright) }
        }
        return
    }
    drawGlow(shaped, positionMs, palette)
    drawRefinedSweep(shaped, positionMs, palette)
}

/**
 * The sung line of an active row. The dim line draws first. An emphasized word — one that passed
 * [LyricsEmphasis] — is cut out of the line and drawn again letter by letter, so its letters ripple
 * while the sweep crosses them. Every other word stays in the plain feathered sweep.
 */
private fun DrawScope.drawRefinedSweep(
    shaped: EnhancedShapedLine,
    positionMs: Long,
    palette: EnhancedLyricPalette,
) {
    val layout = shaped.layout
    val boxes = shaped.boxes
    val box = boxes.getOrNull(LyricsSweepGeometry.activeIndex(boxes, positionMs))
    val hole = if (box != null && box.letters != null) {
        Rect(box.leftPx, shaped.bands.tops[box.line], box.rightPx, shaped.bands.bottoms[box.line])
    } else {
        null
    }
    if (hole == null) {
        drawText(layout, color = palette.dim)
    } else {
        clipPath(outsideOf(hole)) { drawText(layout, color = palette.dim) }
    }
    drawFeatheredSweep(shaped, positionMs, palette, hole, box)
    if (box != null && hole != null) {
        drawEmphasizedWord(shaped, box, hole, sweepEdge(box, positionMs, shaped.rtl), positionMs, palette)
    }
}

/** The x position of the sweep edge inside [box], the same edge [LyricsSweepGeometry.clipRects] draws. */
private fun sweepEdge(box: LyricsWordBox, positionMs: Long, rtl: Boolean): Float {
    val progress = LyricsSweepGeometry.progressAt(box, positionMs, reduceMotion = false)
    val span = box.rightPx - box.leftPx
    return if (rtl) box.rightPx - progress * span else box.leftPx + progress * span
}

/**
 * The sung part of the line. Lines above the sung one are fully bright. The sung line is bright up to the sweep edge and
 * fades to dim across a share of the active word's width on either side of it, so the fill has a soft
 * leading edge sized to the word it crosses. The fade is a mask on a layer
 * limited to the sung line's band, so the lines above and below are left alone. A [hole] is left out, because that word is
 * drawn lifted instead.
 */
private fun DrawScope.drawFeatheredSweep(
    shaped: EnhancedShapedLine,
    positionMs: Long,
    palette: EnhancedLyricPalette,
    hole: Rect?,
    box: LyricsWordBox?,
) {
    val rects = LyricsSweepGeometry.clipRects(shaped.boxes, shaped.bands, positionMs, shaped.rtl, reduceMotion = false)
    if (rects.isEmpty()) return
    val layout = shaped.layout
    val earlier = rects.dropLast(1)
    if (earlier.isNotEmpty()) {
        clipPath(rectPath(earlier)) { drawText(layout, color = palette.bright) }
    }
    val sung = rects.last()
    val top = sung.top
    val height = sung.bottom - sung.top
    val width = shaped.bands.widthPx
    val edge = if (shaped.rtl) sung.left else sung.right
    val feather = sweepFeather(box)
    val mask = Brush.horizontalGradient(fadeColors(shaped.rtl), startX = edge - feather, endX = edge + feather)
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
 * The emphasized word, one grapheme slice at a time. Each letter rides the [LyricsEmphasis.rippleAt]
 * wave — a small rise and swell that travels through the word — while the sweep's feathered edge fills
 * it. The letters that are not bumping yet still draw inside their own clip, so the word sits where the
 * plain text left it.
 */
private fun DrawScope.drawEmphasizedWord(
    shaped: EnhancedShapedLine,
    box: LyricsWordBox,
    hole: Rect,
    edge: Float,
    positionMs: Long,
    palette: EnhancedLyricPalette,
) {
    val layout = shaped.layout
    val letters = box.letters ?: return
    val count = letters.size / 2
    val duration = (box.endMs - box.startMs).coerceAtLeast(1L)
    val liftMax = LyricsEmphasis.LIFT_DP.dp.toPx()
    val feather = sweepFeather(box)
    for (index in 0 until count) {
        val left = letters[index * 2]
        val right = letters[index * 2 + 1]
        if (right - left < 0.01f) continue
        val bump = LyricsEmphasis.rippleAt(box.startMs, duration, count, index, positionMs)
        val lift = liftMax * bump
        val scale = 1f + LyricsEmphasis.SCALE_BOOST * bump
        val centreX = (left + right) / 2f
        val centreY = (hole.top + hole.bottom) / 2f
        val pad = (right - left) * 0.5f + liftMax + 4f
        if (bump > 0f) {
            drawRoundRect(
                color = palette.glow.copy(alpha = palette.glow.alpha * GLOW_ALPHA * bump),
                topLeft = Offset(left - pad * 0.3f, hole.top - lift - 2f),
                size = Size(right - left + pad * 0.6f, hole.height + 4f),
                cornerRadius = CornerRadius(GLOW_CORNER_DP.dp.toPx()),
            )
        }
        val window = Rect(left - pad, hole.top - lift - pad, right + pad, hole.bottom + pad)
        clipPath(Path().apply { addRect(window) }) {
            withTransform({
                translate(top = -lift)
                scale(scale, scale, Offset(centreX, centreY))
            }) {
                drawText(layout, color = palette.dim)
                // The line's feathered edge mapped into the letter's scaled frame: a canvas x lands
                // at centreX + (x - centreX) / scale, so the mask tracks the same edge.
                val edgeLocal = centreX + (edge - centreX) / scale
                val mask = Brush.horizontalGradient(
                    fadeColors(shaped.rtl),
                    startX = edgeLocal - feather / scale,
                    endX = edgeLocal + feather / scale,
                )
                drawIntoCanvas { canvas ->
                    canvas.saveLayer(window, Paint())
                    drawText(layout, color = palette.bright)
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
 * The bloom behind the word being sung. It is drawn with a colour alpha, not a layer alpha, so the text keeps its own
 * opacity while the glow rises and falls. It is scaled by how long the word is held, so quick syllables stay plain.
 */
private fun DrawScope.drawGlow(shaped: EnhancedShapedLine, positionMs: Long, palette: EnhancedLyricPalette) {
    val boxes = shaped.boxes
    val index = LyricsSweepGeometry.activeIndex(boxes, positionMs)
    if (index < 0) return
    val box = boxes[index]
    // The glow belongs to emphasis: a word that earns no ripple stays plain all the way.
    val gain = if (box.letters != null) LyricsSweepGeometry.emphasisGain(box) else 0f
    val glow = LyricsSweepGeometry.glowIntensity(box, positionMs) * gain
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

private fun rectPath(rects: List<LyricsClipRect>): Path {
    val path = Path()
    for (rect in rects) {
        path.addRect(Rect(rect.left, rect.top, rect.right, rect.bottom))
    }
    return path
}

/** The feather runs toward the sweep's leading edge, so right-to-left text fades in from the other side. */
private fun fadeColors(rtl: Boolean): List<Color> =
    if (rtl) listOf(Color.Transparent, Color.Black) else listOf(Color.Black, Color.Transparent)

/** A row's text with the character range each syllable occupies in it, or null for a syllable with no text. */
private class EnhancedSyllableText(
    val text: String,
    val ranges: List<IntRange?>,
)

/** One row laid out once, with the timed boxes that the draw phase clips against. */
private class EnhancedShapedLine(
    val text: String,
    val rtl: Boolean,
    val layout: TextLayoutResult,
    val boxes: List<LyricsWordBox>,
    val bands: LyricsLineBands,
)

/**
 * The text [joinSyllableContents] builds, with the range each syllable occupies in it. The two must stay identical, so the
 * sung line and the plain line lay out the same way.
 */
private fun List<KaraokeSyllable>.joinWithRanges(): EnhancedSyllableText {
    val builder = StringBuilder()
    val ranges = ArrayList<IntRange?>(size)
    forEachIndexed { index, syllable ->
        val first = builder.length
        builder.append(syllable.content)
        val range = if (builder.length > first) first until builder.length else null
        ranges += range
        val next = getOrNull(index + 1)
        if (next != null && needsLatinWordGap(syllable.content, next.content)) {
            builder.append(' ')
        }
    }
    return EnhancedSyllableText(builder.toString(), ranges)
}

private fun shapeEnhancedSyllables(
    measurer: TextMeasurer,
    syllables: List<KaraokeSyllable>,
    style: TextStyle,
    widthPx: Int,
): EnhancedShapedLine {
    val joined = syllables.joinWithRanges()
    val layout = measurer.measure(
        text = AnnotatedString(joined.text),
        style = style,
        // The layout takes the full row width, so centred and end-aligned lines sit against the row, not the text.
        constraints = Constraints(minWidth = widthPx, maxWidth = widthPx),
    )
    val lineCount = layout.lineCount
    val bands = LyricsLineBands(
        widthPx = layout.size.width.toFloat(),
        tops = FloatArray(lineCount) { layout.getLineTop(it) },
        bottoms = FloatArray(lineCount) { layout.getLineBottom(it) },
    )
    val clusters = GraphemeClusterSegmenter.clusterStarts(joined.text)
    val boxes = syllables.mapIndexedNotNull { index, syllable ->
        val range = joined.ranges[index] ?: return@mapIndexedNotNull null
        enhancedWordBox(joined.text, clusters, range, syllable.start.toLong(), syllable.end.toLong(), layout)
    }.sortedBy { it.startMs }
    return EnhancedShapedLine(
        text = joined.text,
        rtl = LyricsUtils.isRtlText(joined.text),
        layout = layout,
        boxes = boxes,
        bands = bands,
    )
}

/**
 * The box of one timed syllable. Its range is widened to whole grapheme clusters, so a cluster that joins several characters
 * is never split across the sweep edge.
 */
private fun enhancedWordBox(
    text: String,
    clusters: IntArray,
    range: IntRange,
    startMs: Long,
    endMs: Long,
    layout: TextLayoutResult,
): LyricsWordBox? {
    val start = clusters.lastOrNull { it <= range.first } ?: 0
    val end = clusters.firstOrNull { it > range.last } ?: text.length
    if (start >= text.length || end <= start) return null
    val line = layout.getLineForOffset(start)
    val lastLine = layout.getLineForOffset(end - 1)
    val first = layout.getBoundingBox(start)
    val last = layout.getBoundingBox(end - 1)
    val left = minOf(first.left, last.left)
    val right = if (lastLine == line) maxOf(first.right, last.right) else layout.getLineRight(line)
    return LyricsWordBox(
        startMs = startMs,
        endMs = endMs,
        leftPx = left,
        rightPx = right,
        line = line,
        letters =
            emphasisLetters(
                text, clusters, start, end, line, lastLine, left, right, endMs - startMs, layout,
            ),
    )
}

/**
 * The (left, right) slice of each grapheme cluster in a word that earned the [LyricsEmphasis] ripple,
 * in sung order, or null when the word keeps the plain sweep. A word that wraps to a second visual
 * line keeps the plain sweep, because its slices could not stay on one band.
 */
private fun emphasisLetters(
    text: String,
    clusterStarts: IntArray,
    start: Int,
    end: Int,
    line: Int,
    lastLine: Int,
    boxLeft: Float,
    boxRight: Float,
    durationMs: Long,
    layout: TextLayoutResult,
): FloatArray? {
    if (line != lastLine) return null
    // Edge clusters that only carry whitespace leave no letter to move.
    var first = start
    var last = end
    while (first < last && text[first].isWhitespace()) first++
    while (last > first && text[last - 1].isWhitespace()) last--
    if (first >= last) return null
    val count = clusterStarts.count { it in first until last }
    if (!LyricsEmphasis.isEmphasized(text.substring(first, last), count, durationMs)) return null
    val slices = FloatArray(count * 2)
    var index = 0
    for (clusterStart in clusterStarts) {
        if (clusterStart < first || clusterStart >= last) continue
        val clusterEnd = clusterStarts.firstOrNull { it > clusterStart }?.let { minOf(it, last) } ?: last
        val head = layout.getBoundingBox(clusterStart)
        val tail = layout.getBoundingBox(clusterEnd - 1)
        slices[index * 2] = minOf(head.left, tail.left).coerceIn(boxLeft, boxRight)
        slices[index * 2 + 1] = maxOf(head.right, tail.right).coerceIn(boxLeft, boxRight)
        index++
    }
    return slices
}

/** The feathered edge width for [box], a share of its own width clamped to the sweep bounds. */
private fun DrawScope.sweepFeather(box: LyricsWordBox?): Float =
    LyricsSweepGeometry.sweepFeatherPx(
        wordWidthPx = box?.let { it.rightPx - it.leftPx } ?: 0f,
        minPx = LyricsSweepGeometry.SWEEP_FEATHER_MIN_DP.dp.toPx(),
        maxPx = LyricsSweepGeometry.SWEEP_FEATHER_MAX_DP.dp.toPx(),
    )

private const val GLOW_ALPHA = 0.35f
private const val GLOW_CORNER_DP = 8
