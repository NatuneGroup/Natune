/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.ui.lyrics

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import moe.rukamori.archivetune.shared.domain.lyrics.unicode.GraphemeClusterSegmenter

/** Identity of one shaped row: which row, at which width, in which style. */
internal data class LyricsShapeKey(
    val rowIndex: Int,
    val widthPx: Int,
    val style: TextStyle,
)

/**
 * A row laid out once. [boxes] locate the timed words on [layout], sorted by start, and [bands] hold each visual
 * line's vertical extent. The draw phase builds its clip regions from these values and never measures again.
 */
internal class LyricsShapedRow(
    val layout: TextLayoutResult,
    val boxes: List<LyricsWordBox>,
    val bands: LyricsLineBands,
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
    )
    val boxes = row.words.orEmpty()
        .mapNotNull { timing -> timing.range?.let { wordBox(row.text, it, timing, layout) } }
        .sortedBy { it.startMs }
    return LyricsShapedRow(layout, boxes, bands)
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
): LyricsWordBox? {
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
    return LyricsWordBox(timing.startMs, timing.endMs, left, right, line)
}

private const val SHAPE_CACHE_ENTRIES = 256
private const val PREWARM_ROWS_BEHIND = 2
private const val PREWARM_ROWS_AHEAD = 4
