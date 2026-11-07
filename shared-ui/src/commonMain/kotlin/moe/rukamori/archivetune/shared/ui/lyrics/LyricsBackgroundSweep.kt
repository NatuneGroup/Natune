package moe.rukamori.archivetune.shared.ui.lyrics

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints

/**
 * The trailing background-vocal subline (TTML `x-bg` spans and enhanced-LRC bracket runs) is split
 * out of the lead line by [LyricsRenderRows]. It has its own word timings, so it must SWEEP over
 * its own spans on the SAME feed timeline as the lead — drawing it as a plain
 * [androidx.compose.material3.Text] left the "gimme some day" backing vocal lit as a static block
 * while the lead line swept above it.
 *
 * The pure [sungFraction] drives a fractional-width clip over one measured text layout. Words map
 * to contiguous horizontal slices by their character ranges, extending each slice to the next
 * word's start so inter-word whitespace is covered as the word completes. The result is a
 * continuous karaoke sweep in sync with the lead, with no text layout per frame.
 */
internal object LyricsBackgroundSweep {
    /**
     * The share of the subline width (0..1) that is bright at [positionMs]. Words before the active
     * one are fully lit; the active word fills over exactly its own span (floored by [minSweepMs]);
     * unstarted words stay dim. Reduced motion snaps each word at its start.
     */
    fun sungFraction(
        words: List<LyricsWordTiming>,
        textLength: Int,
        positionMs: Long,
        minSweepMs: Long,
        reduceMotion: Boolean,
    ): Float {
        if (words.isEmpty() || textLength <= 0) return 0f
        val timed = words.filter { it.range != null }
        if (timed.isEmpty()) return 0f
        // The last word whose span has begun. Before the first word nothing is sung.
        var active = -1
        for (index in timed.indices) {
            if (timed[index].startMs <= positionMs) active = index else break
        }
        if (active < 0) return 0f
        val word = timed[active]
        val leftChar = word.range!!.first.coerceIn(0, textLength)
        val rightChar =
            (timed.getOrNull(active + 1)?.range?.first ?: textLength).coerceIn(leftChar, textLength)
        if (positionMs >= word.endMs) {
            // Word finished: its whole slice is lit. If it is the last word, that slice ends at the
            // subline's right edge (1.0), so the finished line stays fully bright.
            return rightChar.toFloat() / textLength
        }
        val span = maxOf(word.endMs - word.startMs, minSweepMs)
        val progress =
            if (reduceMotion) {
                1f
            } else {
                ((positionMs - word.startMs).toFloat() / span).coerceIn(0f, 1f)
            }
        val left = leftChar.toFloat() / textLength
        val right = rightChar.toFloat() / textLength
        return left + (right - left) * progress
    }
}

/**
 * Draws one background-vocal subline with a per-word sweep on the shared lyric clock. Inactive rows
 * draw once (dim or passed) and never subscribe to the clock; active rows read [clock.feedMs] in
 * the draw phase, so a frame costs one clip and one extra [drawText], not a recomposition.
 */
@Composable
internal fun SweptBackgroundLine(
    text: String,
    words: List<LyricsWordTiming>?,
    style: TextStyle,
    palette: LyricsPalette,
    clock: LyricsClock,
    active: Boolean,
    passed: Boolean,
    minSweepMs: Long,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.roundToPx() }
        val layout =
            remember(text, style, widthPx, measurer) {
                measureBackground(measurer, text, style, widthPx)
            }
        Box(
            Modifier.width(maxWidth)
                .height(with(density) { layout.size.height.toDp() })
                .drawBehind {
                    if (!active) {
                        drawText(layout, color = if (passed) palette.passed else palette.dim)
                        return@drawBehind
                    }
                    // No locatable word ranges: keep the old fully-lit subline rather than a dim
                    // block.
                    val timed = words.orEmpty()
                    if (timed.none { it.range != null }) {
                        drawText(layout, color = palette.bright)
                        return@drawBehind
                    }
                    val fraction =
                        LyricsBackgroundSweep.sungFraction(
                            timed,
                            text.length,
                            clock.feedMs.longValue,
                            minSweepMs,
                            reduceMotion,
                        )
                    drawText(layout, color = palette.dim)
                    if (fraction <= 0f) return@drawBehind
                    clipRect(0f, 0f, size.width * fraction, size.height) {
                        drawText(layout, color = palette.bright)
                    }
                },
        )
    }
}

private fun measureBackground(
    measurer: TextMeasurer,
    text: String,
    style: TextStyle,
    widthPx: Int,
) =
    measurer.measure(
        text = AnnotatedString(text),
        style = style,
        constraints = Constraints(minWidth = widthPx, maxWidth = widthPx),
    )
