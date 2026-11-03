package moe.rukamori.archivetune.shared.ui.lyrics

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import moe.rukamori.archivetune.shared.domain.lyrics.unicode.GraphemeClusterSegmenter

/** Identity of one shaped row: which row, at which width, in which style. */
internal data class LyricsShapeKey(
    val rowIndex: Int,
    val widthPx: Int,
    val style: TextStyle,
)

/**
 * A row laid out once. [boxes] locate the timed words on [layout], sorted by start, and [bands] hold each visual
 * line's vertical extent. [letterRuns] mirrors [boxes]: for a box that earned the [LyricsEmphasis] ripple it holds
 * one shaped run per grapheme cluster, laid out once so the draw phase transforms only that grapheme's own ink —
 * redrawing the full-line layout per letter would repaint its neighbours at the wrong transform.
 */
internal class LyricsShapedRow(
    val layout: TextLayoutResult,
    val boxes: List<LyricsWordBox>,
    val bands: LyricsLineBands,
    val letterRuns: List<List<TextLayoutResult>?>,
)

/** Shaped-row cache for one row list. Build a new cache whenever the list changes, so stale words never survive. */
internal fun lyricsShapeCache(
    rows: List<LyricsRenderRow>,
    measurer: TextMeasurer,
    maxEntries: Int = SHAPE_CACHE_ENTRIES,
): LyricsShapeCache<LyricsShapeKey, LyricsShapedRow> =
    LyricsShapeCache(maxEntries) { key -> shapeLyricRow(measurer, rows[key.rowIndex], key) }

/**
 * The rows around [centre] that are about to become active, as shape keys at [widthPx]. Each row takes the style
 * it will be drawn with, which depends on its alignment.
 */
internal fun lyricsPrewarmKeys(
    rowCount: Int,
    centre: Int,
    widthPx: Int,
    styleFor: (rowIndex: Int) -> TextStyle,
): List<LyricsShapeKey> {
    val from = (centre - PREWARM_ROWS_BEHIND).coerceAtLeast(0)
    val to = (centre + PREWARM_ROWS_AHEAD).coerceAtMost(rowCount - 1)
    return (from..to).map { LyricsShapeKey(it, widthPx, styleFor(it)) }
}

internal fun shapeLyricRow(
    measurer: TextMeasurer,
    row: LyricsRenderRow,
    key: LyricsShapeKey,
): LyricsShapedRow {
    val layout = measurer.measure(
        text = AnnotatedString(row.text),
        style = key.style,
        // The layout takes the full row width, so centred and end-aligned lines sit against the row, not the text.
        constraints = Constraints(minWidth = key.widthPx, maxWidth = key.widthPx),
    )
    val lineCount = layout.lineCount
    val bands = LyricsLineBands(
        widthPx = layout.size.width.toFloat(),
        tops = FloatArray(lineCount) { layout.getLineTop(it) },
        bottoms = FloatArray(lineCount) { layout.getLineBottom(it) },
        overshootPx =
            if (key.style.fontSize == TextUnit.Unspecified) {
                0f
            } else {
                with(layout.layoutInput.density) { key.style.fontSize.toPx() * LyricsLineBands.OVERSHOOT_EM }
            },
    )
    val words = row.words.orEmpty()
        .mapNotNull { timing -> timing.range?.let { wordBox(row.text, it, timing, layout) } }
        .sortedBy { it.first.startMs }
    val boxes = words.map { it.first }
    val letterRuns = words.map { (_, ranges) ->
        if (ranges == null) {
            null
        } else {
            List(ranges.size / 2) { i ->
                measurer.measure(
                    text = AnnotatedString(row.text.substring(ranges[i * 2], ranges[i * 2 + 1])),
                    style = key.style,
                )
            }
        }
    }
    return LyricsShapedRow(layout, boxes, bands, letterRuns)
}

/**
 * The box of one timed word. The word's range is widened to whole grapheme clusters, so a cluster that joins
 * several characters is never split across the sweep edge.
 */
private fun wordBox(
    text: String,
    range: IntRange,
    timing: LyricsWordTiming,
    layout: TextLayoutResult,
): Pair<LyricsWordBox, IntArray?>? {
    val starts = GraphemeClusterSegmenter.clusterStarts(text)
    val start = starts.lastOrNull { it <= range.first } ?: 0
    val end = starts.firstOrNull { it > range.last } ?: text.length
    if (start >= text.length || end <= start) return null
    val line = layout.getLineForOffset(start)
    val lastLine = layout.getLineForOffset(end - 1)
    val first = layout.getBoundingBox(start)
    val last = layout.getBoundingBox(end - 1)
    val left = minOf(first.left, last.left)
    val right = if (lastLine == line) maxOf(first.right, last.right) else layout.getLineRight(line)
    val letters = emphasisLetters(
        text, starts, start, end, line, lastLine, left, right,
        timing.endMs - timing.startMs, layout,
    )
    return LyricsWordBox(
        startMs = timing.startMs,
        endMs = timing.endMs,
        leftPx = left,
        rightPx = right,
        line = line,
        letters = letters?.first,
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

private const val SHAPE_CACHE_ENTRIES = 256
private const val PREWARM_ROWS_BEHIND = 2
private const val PREWARM_ROWS_AHEAD = 4
