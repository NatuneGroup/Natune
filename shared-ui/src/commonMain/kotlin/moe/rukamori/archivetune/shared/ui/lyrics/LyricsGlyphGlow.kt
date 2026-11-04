package moe.rukamori.archivetune.shared.ui.lyrics

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText

/** How much larger than its own ink a glowing word or letter draws: the rim the light leaves around the glyphs. */
internal const val GLYPH_GLOW_SCALE = 1.12f

/**
 * The bloom behind the word being sung, drawn from the text's own ink instead of a filled box. The
 * word's layout draws again [scale] larger about the middle of [bounds] in [color] at [alpha], inside
 * a layer clipped to [bounds]: the ink inside those bounds is the word being lit, so the light keeps
 * the letter shapes and fades out with them.
 *
 * A rectangle behind the word is what a listener sees as a tile sitting on the background instead of
 * the word lighting up, and one rectangle per grapheme — what the emphasis bloom used to draw — reads
 * as a row of boxes behind the glyphs of a wrapped or held last word. [inkTopLeft] places the layout's
 * own origin (a lifted word or letter draws above its seat), so the bloom follows the ink it belongs to.
 */
internal fun DrawScope.drawGlyphGlow(
    layout: TextLayoutResult,
    ink: Rect,
    inkTopLeft: Offset,
    color: Color,
    alpha: Float,
    scale: Float = GLYPH_GLOW_SCALE,
) {
    if (alpha <= 0f || ink.width <= 0f || ink.height <= 0f) return
    // The room the enlarged ink needs past its own box, so the rim is not cut at the word's edges.
    val reach = maxOf(ink.width, ink.height) * (scale - 1f) / 2f + 1f
    val paint = Paint()
    paint.alpha = alpha.coerceIn(0f, 1f)
    drawIntoCanvas { canvas ->
        canvas.saveLayer(ink.inflate(reach), paint)
        // The clip is applied before the scale, so only the ink this word or letter owns is enlarged:
        // a neighbouring word can never be dragged into its bloom, and the rim may still reach past it.
        clipRect(ink.left, ink.top, ink.right, ink.bottom) {
            withTransform({ scale(scale, scale, ink.center) }) {
                drawText(layout, color = color, topLeft = inkTopLeft)
            }
        }
        canvas.restore()
    }
}

/** The band box of one word: the seat its glyphs sit in on their own baseline. */
internal fun lyricsWordSeat(word: LyricsWordBox, bands: LyricsLineBands): Rect =
    Rect(word.leftPx, bands.tops[word.line], word.rightPx, bands.bottoms[word.line])

/**
 * Where the plain passes leave one word out while it draws in its own window: its [lyricsWordSeat]
 * grown by the glyph overshoot above and below, so accents and descenders move with the word instead
 * of being cut at the band or drawn a second time at the seat, and dipped [dipPx] further for the
 * word's lowered ink, so no low sliver survives under a rising word or an emphasized word's letters.
 */
internal fun lyricsWordHole(word: LyricsWordBox, bands: LyricsLineBands, dipPx: Float): Rect {
    val seat = lyricsWordSeat(word, bands)
    return Rect(seat.left, seat.top - bands.overshootPx, seat.right, seat.bottom + bands.overshootPx + dipPx)
}
