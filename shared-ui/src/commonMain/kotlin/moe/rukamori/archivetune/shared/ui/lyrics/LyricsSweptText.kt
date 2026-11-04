package moe.rukamori.archivetune.shared.ui.lyrics

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp

/** Colours for one lyric look. [glow] is the bloom behind the word being sung. */
internal data class LyricsPalette(
    val dim: Color,
    val bright: Color,
    val glow: Color,
) {
    /**
     * A line already sung reads between dim and bright — the active colour at [PASSED_ALPHA] — so
     * it stays distinct from the lines still ahead instead of falling back to the idle colour.
     */
    val passed: Color
        get() = bright.copy(alpha = PASSED_ALPHA)

    companion object {
        const val PASSED_ALPHA = 0.7f
    }
}

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
    /** A synced row the playhead already left draws in [LyricsPalette.passed], not the idle dim. */
    passed: Boolean = false,
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
                    drawSweptLine(shaped, row, active, clock, palette, minSweepMs, reduceMotion, refined, passed)
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
    passed: Boolean = false,
) {
    val layout = shaped.layout
    if (!active) {
        drawText(layout, color = if (passed) palette.passed else palette.dim)
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
 * The refined sweep of an active row. The dim line draws first. An emphasized word — one that passed
 * [LyricsEmphasis] — is cut out of the line and drawn again letter by letter, so its letters ripple
 * while the sweep crosses them. Every other word stays in the plain feathered sweep.
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
    val liftMaxPx = LyricsSweepGeometry.WORD_LIFT_DP.dp.toPx()
    val index = LyricsSweepGeometry.activeIndex(boxes, position)
    val box = boxes.getOrNull(index)
    // How far below its seat each word draws: the row sinks into the low seat just before its first
    // word, then each word eases back up through its own completion lift. A word wrapped over two
    // bands has no single band to rise in, so it stays seated.
    val firstStart = boxes.first().startMs
    val sinkPx = liftMaxPx * LyricsSweepGeometry.sinkAt(firstStart, position, false)
    val drop = FloatArray(boxes.size)
    for (i in boxes.indices) {
        val word = boxes[i]
        if (word.wrapsLines) continue
        val nextStart = boxes.getOrNull(i + 1)?.startMs ?: Long.MAX_VALUE
        drop[i] = liftMaxPx * LyricsSweepGeometry.dropAt(firstStart, word.startMs, word.endMs, nextStart, position, false)
    }
    // A started word that has not landed draws in its own window, dim and lit at one offset, so the
    // sweep never lights it at another height than its dim ink. The rise costs a clip and a
    // translate, never a relayout. The sung emphasized word rides its letters in its own hole instead.
    val liftHoles = ArrayList<Rect>(2)
    val liftOffsets = ArrayList<Float>(2)
    val liftBoxes = ArrayList<LyricsWordBox>(2)
    for (i in boxes.indices) {
        val word = boxes[i]
        if (word.wrapsLines || position < word.startMs || drop[i] <= 0f) continue
        if (i == index && word.letters != null) continue
        liftBoxes += word
        liftHoles += lyricsWordHole(word, shaped.bands, liftMaxPx)
        liftOffsets += drop[i]
    }
    val hole = if (box != null && box.letters != null) lyricsWordHole(box, shaped.bands, liftMaxPx) else null
    val holes = if (hole != null) liftHoles + hole else liftHoles

    // The words not yet started sit one sink low. They are the tail of each visual band in sung
    // order, so per band one rect from the first to the last of them holds them all; the rest of
    // the band, sung words and wrapped words included, draws seated.
    val seatedRegion = Path()
    val unsungRegion = Path()
    val overshoot = shaped.bands.overshootPx
    val width = shaped.bands.widthPx
    for (band in 0 until shaped.bands.lineCount) {
        val top = shaped.bands.tops[band] - overshoot
        val bottom = shaped.bands.bottoms[band] + overshoot
        var left = Float.POSITIVE_INFINITY
        var right = Float.NEGATIVE_INFINITY
        if (sinkPx > 0f) {
            for (word in boxes) {
                if (word.line != band || word.wrapsLines || position >= word.startMs) continue
                left = minOf(left, word.leftPx)
                right = maxOf(right, word.rightPx)
            }
        }
        if (left < right) {
            if (left > 0f) seatedRegion.addRect(Rect(0f, top, left, bottom))
            if (right < width) seatedRegion.addRect(Rect(right, top, width, bottom))
            unsungRegion.addRect(Rect(left, top, right, bottom + liftMaxPx))
        } else {
            seatedRegion.addRect(Rect(0f, top, width, bottom))
        }
    }

    clipOutside(holes) {
        clipPath(seatedRegion) { drawText(layout, color = palette.dim) }
        if (sinkPx > 0f) {
            clipPath(unsungRegion) {
                withTransform({ translate(top = sinkPx) }) {
                    drawText(layout, color = palette.dim)
                }
            }
        }
        drawFeatheredSweep(shaped, boxes, position, rtl, minSweepMs, palette, holes, box)
    }
    for (i in liftBoxes.indices) {
        drawLiftedWord(layout, liftBoxes[i], liftHoles[i], liftOffsets[i], position, rtl, minSweepMs, palette)
    }
    if (box != null && hole != null) {
        // The emphasized word keeps the row's drop under its ripple, so it leaves the low seat with
        // the rest of the row instead of snapping up as it starts.
        withTransform({ translate(top = drop[index]) }) {
            drawEmphasizedWord(
                layout, box, hole, lyricsWordSeat(box, shaped.bands), sweepEdge(box, position, rtl, minSweepMs), rtl,
                position, palette, shaped.bands.overshootPx, shaped.letterRuns.getOrNull(index),
            )
        }
    }
}

/**
 * Runs [block] with every rect in [holes] clipped out. Each hole clips to everything outside itself, so
 * nesting them subtracts them all — the lifted words and the emphasized word leave no ink in the
 * plain passes to double up with their own windows.
 */
private fun DrawScope.clipOutside(holes: List<Rect>, index: Int = 0, block: DrawScope.() -> Unit) {
    if (index >= holes.size) {
        block()
        return
    }
    clipPath(outsideOf(holes[index])) { clipOutside(holes, index + 1, block) }
}

/**
 * One started word that has not landed yet, drawn whole out of the line layout [dropPx] below its
 * seat, inside [window] — exactly the hole the plain passes leave, so no neighbouring ink is drawn
 * twice at two heights. The word keeps the line's own shaping — dim under, then lit through the same
 * feathered edge the rest of the row uses — so it crosses the sweep edge with no seam.
 */
private fun DrawScope.drawLiftedWord(
    layout: TextLayoutResult,
    word: LyricsWordBox,
    window: Rect,
    dropPx: Float,
    positionMs: Long,
    rtl: Boolean,
    minSweepMs: Long,
    palette: LyricsPalette,
) {
    val edge = sweepEdge(word, positionMs, rtl, minSweepMs)
    val feather = sweepFeather(word)
    clipPath(Path().apply { addRect(window) }) {
        withTransform({ translate(top = dropPx) }) {
            drawText(layout, color = palette.dim)
            drawIntoCanvas { canvas ->
                canvas.saveLayer(window.translate(0f, -dropPx), Paint())
                drawText(layout, color = palette.bright)
                val mask =
                    Brush.horizontalGradient(
                        if (rtl) {
                            listOf(Color.Transparent, Color.Black)
                        } else {
                            listOf(Color.Black, Color.Transparent)
                        },
                        startX = edge - feather,
                        endX = edge + feather,
                    )
                drawRect(
                    brush = mask,
                    topLeft = Offset(window.left, window.top - dropPx),
                    size = Size(window.width, window.height),
                    blendMode = BlendMode.DstIn,
                )
                canvas.restore()
            }
        }
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
 * to the sweep edge and fades to dim across a share of the active word's width on either side of it,
 * so the fill has a soft leading edge sized to the word it crosses.
 * The fade is a mask on a layer limited to the sung line's band, so the lines above and below are left alone. The
 * [holes] are left out of the sung line, because those words draw in their own windows instead.
 */
private fun DrawScope.drawFeatheredSweep(
    shaped: LyricsShapedRow,
    boxes: List<LyricsWordBox>,
    position: Long,
    rtl: Boolean,
    minSweepMs: Long,
    palette: LyricsPalette,
    holes: List<Rect>,
    box: LyricsWordBox?,
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
    val feather = sweepFeather(box)
    val fade = if (rtl) {
        listOf(Color.Transparent, Color.Black)
    } else {
        listOf(Color.Black, Color.Transparent)
    }
    val mask = Brush.horizontalGradient(fade, startX = edge - feather, endX = edge + feather)
    drawIntoCanvas { canvas ->
        canvas.saveLayer(Rect(0f, top, width, top + height), Paint())
        clipOutside(holes) { drawText(layout, color = palette.bright) }
        drawRect(brush = mask, topLeft = Offset(0f, top), size = Size(width, height), blendMode = BlendMode.DstIn)
        canvas.restore()
    }
}

/**
 * The emphasized word, one grapheme slice at a time. Each letter rides the [LyricsEmphasis.rippleAt]
 * wave — a small rise and swell that travels through the word — while the sweep's feathered edge fills
 * it. The letters that are not bumping yet still draw inside their own clip, so the word sits where the
 * plain text left it.
 *
 * Every slice draws the grapheme's own [TextLayoutResult] — shaped once beside the line in
 * [LyricsShapedRow.letterRuns] — inside a window that only reaches the grapheme's own overhang and
 * swell. Isolated ink is what keeps a held word's overlapping windows from repainting neighbouring
 * glyphs at a different transform: foreign ink simply is not in the run. Slices without runs take the
 * plain feathered fill inside the hole instead. Each letter is placed on [seat], the word's band, while
 * [hole] is the wider cut the plain passes left for it.
 */
private fun DrawScope.drawEmphasizedWord(
    layout: TextLayoutResult,
    box: LyricsWordBox,
    hole: Rect,
    seat: Rect,
    edge: Float,
    rtl: Boolean,
    positionMs: Long,
    palette: LyricsPalette,
    overshootPx: Float,
    letterRun: List<TextLayoutResult>?,
) {
    val letters = box.letters ?: return
    val count = letters.size / 2
    val duration = (box.endMs - box.startMs).coerceAtLeast(1L)
    val liftMax = LyricsEmphasis.LIFT_DP.dp.toPx()
    val feather = sweepFeather(box)
    // The reach of a letter's repaint window: just past its own overhang and swell, never into the
    // ink of the lines above or below.
    val vPad = overshootPx + seat.height * LyricsEmphasis.SCALE_BOOST * 0.5f
    val fade = if (rtl) {
        listOf(Color.Transparent, Color.Black)
    } else {
        listOf(Color.Black, Color.Transparent)
    }
    if (letterRun == null || letterRun.size != count) {
        clipPath(Path().apply { addRect(hole) }) { drawText(layout, color = palette.dim) }
        drawIntoCanvas { canvas ->
            canvas.saveLayer(hole, Paint())
            clipPath(Path().apply { addRect(hole) }) { drawText(layout, color = palette.bright) }
            val mask = Brush.horizontalGradient(fade, startX = edge - feather, endX = edge + feather)
            drawRect(
                brush = mask,
                topLeft = Offset(hole.left, hole.top),
                size = Size(hole.width, hole.height),
                blendMode = BlendMode.DstIn,
            )
            canvas.restore()
        }
        return
    }
    for (index in 0 until count) {
        val left = letters[index * 2]
        val right = letters[index * 2 + 1]
        if (right - left < 0.01f) continue
        val letter = letterRun[index]
        val bump = LyricsEmphasis.rippleAt(box.startMs, duration, count, index, positionMs)
        val lift = liftMax * bump
        val scale = 1f + LyricsEmphasis.SCALE_BOOST * bump
        val centreX = (left + right) / 2f
        val centreY = (seat.top + seat.bottom) / 2f
        if (bump > 0f) {
            // The bloom rides the letter's own ink — a slightly larger copy of the glyph under it —
            // so a held letter lights up as a letter and never as a box drawn behind it.
            drawGlyphGlow(
                layout = letter,
                ink = Rect(left, seat.top - lift, right, seat.bottom - lift),
                inkTopLeft = Offset(left, seat.top - lift),
                color = palette.glow,
                alpha = GLOW_ALPHA * bump,
                scale = GLYPH_GLOW_SCALE * scale,
            )
        }
        val window = Rect(left - vPad, seat.top - lift - vPad, right + vPad, seat.bottom + vPad)
        clipPath(Path().apply { addRect(window) }) {
            withTransform({
                translate(top = -lift)
                scale(scale, scale, Offset(centreX, centreY))
            }) {
                // The letter's own layout sits on its slice's advance position; only its ink exists
                // inside the window, so nothing neighbouring can be torn along.
                drawText(letter, color = palette.dim, topLeft = Offset(left, seat.top))
                // The line's feathered edge mapped into the letter's scaled frame: a canvas x lands
                // at centreX + (x - centreX) / scale, so the mask tracks the same edge.
                val edgeLocal = centreX + (edge - centreX) / scale
                val featherLocal = feather / scale
                val mask =
                    Brush.horizontalGradient(
                        fade,
                        startX = edgeLocal - featherLocal,
                        endX = edgeLocal + featherLocal,
                    )
                drawIntoCanvas { canvas ->
                    canvas.saveLayer(window, Paint())
                    drawText(letter, color = palette.bright, topLeft = Offset(left, seat.top))
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

/**
 * Everything but [hole], as four rectangles, so a clip can leave one word out of its line. Glyph ink
 * overflows the component — accents above the first band, descenders and the sunk row below the last —
 * so the outside reaches well past the canvas instead of clipping that ink away.
 */
private fun DrawScope.outsideOf(hole: Rect): Path {
    val reach = maxOf(size.width, size.height)
    val left = -reach
    val top = -reach
    val right = size.width + reach
    val bottom = size.height + reach
    return Path().apply {
        addRect(Rect(left, top, right, hole.top))
        addRect(Rect(left, hole.bottom, right, bottom))
        addRect(Rect(left, hole.top, hole.left, hole.bottom))
        addRect(Rect(hole.right, hole.top, right, hole.bottom))
    }
}

/**
 * The bloom behind the word being sung: the word's own ink, enlarged and tinted, so the light keeps the letter
 * shapes instead of painting a box on the background. It rises and falls with the word's envelope, and the refined
 * sweep scales it by how long the word is held, so quick syllables stay plain.
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
    // The refined glow belongs to emphasis: a word that earns no ripple stays plain all the way.
    val gain = if (refined) {
        if (box.letters != null) LyricsSweepGeometry.emphasisGain(box) else 0f
    } else {
        1f
    }
    val glow = LyricsSweepGeometry.glowIntensity(box, position) * gain
    val width = box.rightPx - box.leftPx
    if (glow <= 0f || width <= 0f) return
    val overshoot = shaped.bands.overshootPx
    drawGlyphGlow(
        layout = shaped.layout,
        ink = Rect(
            box.leftPx,
            shaped.bands.tops[box.line] - overshoot,
            box.rightPx,
            shaped.bands.bottoms[box.line] + overshoot,
        ),
        inkTopLeft = Offset.Zero,
        color = palette.glow,
        alpha = GLOW_ALPHA * glow,
    )
}

private const val GLOW_ALPHA = 0.35f

/** The feathered edge width for [box], a share of its own width clamped to the sweep bounds. */
private fun DrawScope.sweepFeather(box: LyricsWordBox?): Float =
    LyricsSweepGeometry.sweepFeatherPx(
        wordWidthPx = box?.let { it.rightPx - it.leftPx } ?: 0f,
        minPx = LyricsSweepGeometry.SWEEP_FEATHER_MIN_DP.dp.toPx(),
        maxPx = LyricsSweepGeometry.SWEEP_FEATHER_MAX_DP.dp.toPx(),
    )
