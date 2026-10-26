/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.ui.lyrics

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.StateFlow
import moe.rukamori.archivetune.shared.domain.playback.PlaybackSnapshot

/** The compact lyric strips. BitChord reserves two lines; SimpMusic is a single line in a fixed band. */
enum class LyricsCompactStyle {
    BITCHORD,
    SIMPMUSIC,
}

private val BITCHORD_HEIGHT = 56.dp
private val SIMPMUSIC_WIDTH = 300.dp
private val SIMPMUSIC_HEIGHT = 48.dp

/**
 * A compact strip that shows the line being sung. It shares the fullscreen clock rules: one snapshot feeds the
 * clock, the frame loop runs only while visible and resumed with motion on, and a tap seeks to the line.
 */
@Composable
fun LyricsCompactStrip(
    rows: List<LyricsRenderRow>,
    playback: StateFlow<PlaybackSnapshot?>,
    style: LyricsCompactStyle,
    actions: LyricsSurfaceActions,
    modifier: Modifier = Modifier,
    tapToSeek: Boolean = true,
    syncOffsetMs: Long = 0L,
    reduceMotion: Boolean = false,
    visible: Boolean = true,
    resumed: Boolean = true,
) {
    val snapshot by playback.collectAsState()
    val frameLoop = LyricsFrameGate.runsFrameLoop(visible, resumed, reduceMotion, snapshot)
    val clock = rememberLyricsClock(snapshot, syncOffsetMs, LyricsFeedTiming.leadMsFor(rows), frameLoop)
    val scanner = remember(rows) { LyricsRenderRows.scanner(rows) }
    val activeRows by remember(scanner) { derivedStateOf { scanner.activeAt(clock.feedMs.longValue) } }
    val primary = activeRows.lastOrNull() ?: -1
    val current = rows.getOrNull(primary)
    val next = if (primary >= 0) rows.getOrNull(primary + 1) else null

    val measurer = rememberTextMeasurer()
    val shapes = remember(rows, measurer) { lyricsShapeCache(rows, measurer) }
    val colors = MaterialTheme.colorScheme
    val palette = remember(colors) {
        LyricsPalette(
            dim = colors.onSurface.copy(alpha = STRIP_IDLE_ALPHA),
            bright = colors.onSurface,
            glow = colors.primary,
        )
    }
    val textStyle = remember(style) { stripTextStyle(style) }
    val onTap: () -> Unit = {
        if (current != null) {
            val action = LyricsTapPolicy.onTap(current, selectionActive = false, tapToSeek = tapToSeek)
            if (action is LyricsTapAction.Seek) actions.onSeek(action.positionMs)
        }
    }

    when (style) {
        LyricsCompactStyle.BITCHORD -> Column(
            modifier
                .fillMaxWidth()
                .height(BITCHORD_HEIGHT)
                .clickable(onClick = onTap),
        ) {
            if (current != null) {
                SweptLyricLine(
                    row = current,
                    style = textStyle,
                    palette = palette,
                    active = true,
                    clock = clock,
                    shapes = shapes,
                    minSweepMs = LyricsSweepGeometry.MIN_SWEEP_MS,
                    reduceMotion = reduceMotion,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            next?.let { Text(text = it.text, style = textStyle.copy(fontWeight = FontWeight.Normal), color = palette.dim, maxLines = 1) }
        }

        LyricsCompactStyle.SIMPMUSIC -> Box(
            modifier
                .width(SIMPMUSIC_WIDTH)
                .height(SIMPMUSIC_HEIGHT)
                .clickable(onClick = onTap),
        ) {
            if (current != null) {
                SweptLyricLine(
                    row = current,
                    style = textStyle,
                    palette = palette,
                    active = true,
                    clock = clock,
                    shapes = shapes,
                    minSweepMs = LyricsSweepGeometry.MIN_SWEEP_MS,
                    reduceMotion = reduceMotion,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private fun stripTextStyle(style: LyricsCompactStyle): TextStyle =
    when (style) {
        LyricsCompactStyle.BITCHORD -> TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        LyricsCompactStyle.SIMPMUSIC -> TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }

private const val STRIP_IDLE_ALPHA = 0.55f
