package moe.rukamori.archivetune.ui.component

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeSyllable
import moe.rukamori.archivetune.lyrics.LyricsUtils
import moe.rukamori.archivetune.shared.domain.lyrics.unicode.GraphemeClusterSegmenter
import moe.rukamori.archivetune.shared.ui.lyrics.GLYPH_GLOW_SCALE
import moe.rukamori.archivetune.shared.ui.lyrics.LyricsEmphasis
import moe.rukamori.archivetune.shared.ui.lyrics.LyricsClipRect
import moe.rukamori.archivetune.shared.ui.lyrics.LyricsLineBands
import moe.rukamori.archivetune.shared.ui.lyrics.LyricsSweepGeometry
import moe.rukamori.archivetune.shared.ui.lyrics.LyricsWordBox
import moe.rukamori.archivetune.shared.ui.lyrics.drawGlyphGlow
import moe.rukamori.archivetune.shared.ui.lyrics.lyricsWordHole
import moe.rukamori.archivetune.shared.ui.lyrics.lyricsWordSeat

/** Colours of one swept line: [dim] is the unsung text, [bright] the sung part and [glow] the bloom behind a held word. */
internal data class EnhancedLyricPalette(
    val dim: Color,
    val bright: Color,
    val glow: Color,
)

/**
 * The active karaoke line drawn with a word sweep. The text is shaped once per width and style, and the sweep is read in the
 * draw phase from [currentPosition], so the sweep moves without recomposing the row. Every word rises into its seat as
 * it completes; only a word held for at least a second earns a glow and a letter ripple. Reduced motion jumps from word
 * to word instead.
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
            // Scaled by the text's own alpha, so a translucent background voice dims in proportion.
            dim = textColor.copy(alpha = textColor.alpha * EnhancedLyricsMotion.INACTIVE_ALPHA),
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
    val liftMaxPx = LyricsSweepGeometry.WORD_LIFT_DP.dp.toPx()
    val index = LyricsSweepGeometry.activeIndex(boxes, positionMs)
    val box = boxes.getOrNull(index)
    // How far below its seat each word draws — the same curve the shared renderer and the iOS view
    // read, so every platform moves the same word the same way at the same instant. The row sinks
    // into the low seat just before its first word, then each word eases back up through its own
    // completion lift. A word wrapped over two bands has no single band to rise in, so it stays seated.
    val firstStart = boxes.first().startMs
    val sinkPx = liftMaxPx * LyricsSweepGeometry.sinkAt(firstStart, positionMs, false)
    val drop = FloatArray(boxes.size)
    for (i in boxes.indices) {
        val word = boxes[i]
        if (word.wrapsLines) continue
        val nextStart = boxes.getOrNull(i + 1)?.startMs ?: Long.MAX_VALUE
        drop[i] = liftMaxPx * LyricsSweepGeometry.dropAt(firstStart, word.startMs, word.endMs, nextStart, positionMs, false)
    }
    // A started word that has not landed draws in its own window, dim and lit at one offset, so the
    // sweep never lights it at another height than its dim ink. The rise costs a clip and a
    // translate, never a relayout. The sung emphasized word rides its letters in its own hole instead.
    val liftBoxes = ArrayList<LyricsWordBox>(2)
    val liftHoles = ArrayList<Rect>(2)
    val liftOffsets = ArrayList<Float>(2)
    for (i in boxes.indices) {
        val word = boxes[i]
        if (word.wrapsLines || positionMs < word.startMs || drop[i] <= 0f) continue
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
                if (word.line != band || word.wrapsLines || positionMs >= word.startMs) continue
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
        drawFeatheredSweep(shaped, positionMs, palette, holes, box)
    }
    for (i in liftBoxes.indices) {
        drawLiftedWord(layout, liftBoxes[i], liftHoles[i], liftOffsets[i], positionMs, shaped.rtl, palette)
    }
    if (box != null && hole != null) {
        // The emphasized word keeps the row's drop under its ripple, so it leaves the low seat with
        // the rest of the row instead of snapping up as it starts.
        withTransform({ translate(top = drop[index]) }) {
            drawEmphasizedWord(
                shaped, box, hole, lyricsWordSeat(box, shaped.bands), sweepEdge(box, positionMs, shaped.rtl),
                positionMs, palette, shaped.letterRuns.getOrNull(index),
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
    palette: EnhancedLyricPalette,
) {
    val edge = sweepEdge(word, positionMs, rtl)
    val feather = sweepFeather(word)
    clipPath(Path().apply { addRect(window) }) {
        withTransform({ translate(top = dropPx) }) {
            drawText(layout, color = palette.dim)
            drawIntoCanvas { canvas ->
                canvas.saveLayer(window.translate(0f, -dropPx), sweepLayerPaint)
                drawText(layout, color = palette.bright)
                val mask =
                    Brush.horizontalGradient(
                        fadeColors(rtl),
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
private fun sweepEdge(box: LyricsWordBox, positionMs: Long, rtl: Boolean): Float {
    val progress = LyricsSweepGeometry.progressAt(box, positionMs, reduceMotion = false)
    val span = box.rightPx - box.leftPx
    return if (rtl) box.rightPx - progress * span else box.leftPx + progress * span
}

/**
 * The sung part of the line. Lines above the sung one are fully bright. The sung line is bright up to the sweep edge and
 * fades to dim across a share of the active word's width on either side of it, so the fill has a soft
 * leading edge sized to the word it crosses. The fade is a mask on a layer
 * limited to the sung line's band, so the lines above and below are left alone. The [holes] are left out, because those words
 * draw in their own windows instead.
 */
private fun DrawScope.drawFeatheredSweep(
    shaped: EnhancedShapedLine,
    positionMs: Long,
    palette: EnhancedLyricPalette,
    holes: List<Rect>,
    box: LyricsWordBox?,
) {
    val rects = LyricsSweepGeometry.clipRects(shaped.boxes, shaped.bands, positionMs, shaped.rtl, reduceMotion = false)
    if (rects.isEmpty()) return
    val layout = shaped.layout
    val earlier = if (rects.size > 1) rects.subList(0, rects.size - 1) else emptyList()
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
        canvas.saveLayer(Rect(0f, top, width, top + height), sweepLayerPaint)
        clipOutside(holes) { drawText(layout, color = palette.bright) }
        drawRect(brush = mask, topLeft = Offset(0f, top), size = Size(width, height), blendMode = BlendMode.DstIn)
        canvas.restore()
    }
}

/**
 * The emphasized word, one grapheme slice at a time. Each letter rides the [LyricsEmphasis.rippleAt]
 * wave — a small rise and swell that travels through the word — while the sweep's feathered edge fills
 * it. The letters that are not bumping yet still draw inside their own clip, so the word sits where the
 * plain text left it: each letter is placed on [seat], the word's band, while [hole] is the wider cut
 * the plain passes left for it.
 */
private fun DrawScope.drawEmphasizedWord(
    shaped: EnhancedShapedLine,
    box: LyricsWordBox,
    hole: Rect,
    seat: Rect,
    edge: Float,
    positionMs: Long,
    palette: EnhancedLyricPalette,
    letterRun: List<TextLayoutResult>?,
) {
    val layout = shaped.layout
    val letters = box.letters ?: return
    val count = letters.size / 2
    val duration = (box.endMs - box.startMs).coerceAtLeast(1L)
    val liftMax = LyricsEmphasis.LIFT_DP.dp.toPx()
    val feather = sweepFeather(box)
    // The reach of a letter's repaint window: just past its own overhang and swell, never into the
    // ink of the lines above or below.
    val vPad = shaped.bands.overshootPx + seat.height * LyricsEmphasis.SCALE_BOOST * 0.5f
    if (letterRun == null || letterRun.size != count) {
        clipPath(Path().apply { addRect(hole) }) { drawText(layout, color = palette.dim) }
        drawIntoCanvas { canvas ->
            canvas.saveLayer(hole, sweepLayerPaint)
            clipPath(Path().apply { addRect(hole) }) { drawText(layout, color = palette.bright) }
            val mask = Brush.horizontalGradient(
                fadeColors(shaped.rtl),
                startX = edge - feather,
                endX = edge + feather,
            )
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
                val mask = Brush.horizontalGradient(
                    fadeColors(shaped.rtl),
                    startX = edgeLocal - feather / scale,
                    endX = edgeLocal + feather / scale,
                )
                drawIntoCanvas { canvas ->
                    canvas.saveLayer(window, sweepLayerPaint)
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
 * shapes instead of painting a box on the background. It rises and falls with the word's envelope, scaled by how
 * long the word is held, so quick syllables stay plain.
 */
private fun DrawScope.drawGlow(shaped: EnhancedShapedLine, positionMs: Long, palette: EnhancedLyricPalette) {
    val boxes = shaped.boxes
    val index = LyricsSweepGeometry.activeIndex(boxes, positionMs)
    if (index < 0) return
    val box = boxes[index]
    // The glow belongs to emphasis: a word that earns no ripple stays plain all the way.
    val gain = if (box.letters != null) LyricsSweepGeometry.emphasisGain(box) else 0f
    val glow = LyricsSweepGeometry.glowIntensity(box, positionMs) * gain
    if (glow <= 0f || box.rightPx <= box.leftPx) return
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

private fun rectPath(rects: List<LyricsClipRect>): Path {
    val path = Path()
    for (rect in rects) {
        path.addRect(Rect(rect.left, rect.top, rect.right, rect.bottom))
    }
    return path
}

/**
 * The paint every sweep layer is saved with. It is never mutated, and the sweep draws on the UI thread only, so one
 * instance serves every frame instead of a fresh [Paint] per saveLayer per frame.
 */
private val sweepLayerPaint = Paint()

/** The feather runs toward the sweep's leading edge, so right-to-left text fades in from the other side. */
private fun fadeColors(rtl: Boolean): List<Color> =
    if (rtl) listOf(Color.Transparent, Color.Black) else listOf(Color.Black, Color.Transparent)

/** A row's text with the character range each syllable occupies in it, or null for a syllable with no text. */
private class EnhancedSyllableText(
    val text: String,
    val ranges: List<IntRange?>,
)

/**
 * One row laid out once, with the timed boxes that the draw phase clips against. [letterRuns] mirrors
 * [boxes]: for a box that earned the [LyricsEmphasis] ripple it holds one shaped run per grapheme
 * cluster, laid out once so the draw phase transforms only that grapheme's own ink — redrawing the
 * full-line layout per letter would repaint its neighbours at the wrong transform. A word whose
 * isolated runs shaped different advances than the line gets no runs and keeps the plain feathered
 * sweep: the glyphs changed between contexts, so moving one would tear the text.
 */
private class EnhancedShapedLine(
    val text: String,
    val rtl: Boolean,
    val layout: TextLayoutResult,
    val boxes: List<LyricsWordBox>,
    val bands: LyricsLineBands,
    val letterRuns: List<List<TextLayoutResult>?>,
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
        overshootPx =
            if (style.fontSize == TextUnit.Unspecified) {
                0f
            } else {
                with(layout.layoutInput.density) { style.fontSize.toPx() * LyricsLineBands.OVERSHOOT_EM }
            },
    )
    val clusters = GraphemeClusterSegmenter.clusterStarts(joined.text)
    val words = syllables.mapIndexedNotNull { index, syllable ->
        val range = joined.ranges[index] ?: return@mapIndexedNotNull null
        enhancedWordBox(joined.text, clusters, range, syllable.start.toLong(), syllable.end.toLong(), layout)
    }.sortedBy { it.first.startMs }
    val boxes = words.map { it.first }
    val letterRuns = words.map { (box, ranges) ->
        if (ranges == null) {
            null
        } else {
            val runs = List(ranges.size / 2) { i ->
                measurer.measure(
                    text = AnnotatedString(joined.text.substring(ranges[i * 2], ranges[i * 2 + 1])),
                    style = style,
                )
            }
            // A cluster measured alone can shape a different glyph than the line shaped for it — a
            // ligature broken apart or a contextual form lost — and that letter would overlap or
            // jump under the ripple, so the word keeps the plain feathered sweep.
            if (LyricsEmphasis.isolatedRunsMatchLine(box.letters, FloatArray(runs.size) { isolatedRunWidth(runs[it]) })) {
                runs
            } else {
                null
            }
        }
    }
    return EnhancedShapedLine(
        text = joined.text,
        rtl = LyricsUtils.isRtlText(joined.text),
        layout = layout,
        boxes = boxes,
        bands = bands,
        letterRuns = letterRuns,
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
): Pair<LyricsWordBox, IntArray?>? {
    val start = clusters.lastOrNull { it <= range.first } ?: 0
    val end = clusters.firstOrNull { it > range.last } ?: text.length
    if (start >= text.length || end <= start) return null
    val line = layout.getLineForOffset(start)
    val lastLine = layout.getLineForOffset(end - 1)
    val first = layout.getBoundingBox(start)
    val last = layout.getBoundingBox(end - 1)
    val left = minOf(first.left, last.left)
    val right = if (lastLine == line) maxOf(first.right, last.right) else layout.getLineRight(line)
    val letters = emphasisLetters(
        text, clusters, start, end, line, lastLine, left, right, endMs - startMs, layout,
    )
    return LyricsWordBox(
        startMs = startMs,
        endMs = endMs,
        leftPx = left,
        rightPx = right,
        line = line,
        letters = letters?.first,
        wrapsLines = lastLine != line,
    ) to letters?.second
}

/**
 * The (left, right) slice and the (start, end) character range of each grapheme cluster in a word that
 * earned the [LyricsEmphasis] ripple, in sung order, or null when the word keeps the plain sweep. The
 * ranges feed the per-grapheme runs the draw phase transforms, so a moving letter never drags its
 * neighbours' ink along. A word that wraps to a second visual line keeps the plain sweep, because its
 * slices could not stay on one band.
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
): Pair<FloatArray, IntArray>? {
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
    val ranges = IntArray(count * 2)
    var index = 0
    for (clusterStart in clusterStarts) {
        if (clusterStart < first || clusterStart >= last) continue
        val clusterEnd = clusterStarts.firstOrNull { it > clusterStart }?.let { minOf(it, last) } ?: last
        val head = layout.getBoundingBox(clusterStart)
        val tail = layout.getBoundingBox(clusterEnd - 1)
        slices[index * 2] = minOf(head.left, tail.left).coerceIn(boxLeft, boxRight)
        slices[index * 2 + 1] = maxOf(head.right, tail.right).coerceIn(boxLeft, boxRight)
        ranges[index * 2] = clusterStart
        ranges[index * 2 + 1] = clusterEnd
        index++
    }
    return slices to ranges
}

/** The advance an isolated run reports for its cluster, the same bounding-box metric [emphasisLetters] read in the line. */
private fun isolatedRunWidth(run: TextLayoutResult): Float {
    val head = run.getBoundingBox(0)
    val tail = run.getBoundingBox(run.layoutInput.text.length - 1)
    return maxOf(head.right, tail.right) - minOf(head.left, tail.left)
}

/** The feathered edge width for [box], a share of its own width clamped to the sweep bounds. */
private fun DrawScope.sweepFeather(box: LyricsWordBox?): Float =
    LyricsSweepGeometry.sweepFeatherPx(
        wordWidthPx = box?.let { it.rightPx - it.leftPx } ?: 0f,
        minPx = LyricsSweepGeometry.SWEEP_FEATHER_MIN_DP.dp.toPx(),
        maxPx = LyricsSweepGeometry.SWEEP_FEATHER_MAX_DP.dp.toPx(),
    )

private const val GLOW_ALPHA = 0.35f
