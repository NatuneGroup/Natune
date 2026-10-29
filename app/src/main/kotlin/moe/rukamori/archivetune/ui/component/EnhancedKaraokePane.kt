package moe.rukamori.archivetune.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mocharealm.accompanist.lyrics.core.model.ISyncedLine
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.ui.composable.lyrics.KaraokeLyricsView
import moe.rukamori.archivetune.shared.ui.lyrics.LyricsViewportFade
import moe.rukamori.archivetune.shared.ui.lyrics.lyricsViewportFade

/**
 * The synced Enhanced lyrics list: the library's [KaraokeLyricsView] inside a box whose top and bottom
 * edges fade, wherever the list is scrolled. The active line sits 8% of the box height below the top.
 */
@Composable
internal fun EnhancedKaraokePane(
    listState: LazyListState,
    lyrics: SyncedLyrics,
    currentPosition: () -> Int,
    onLineClicked: (ISyncedLine) -> Unit,
    onLinePressed: (ISyncedLine) -> Unit,
    textColor: Color,
    normalLineTextStyle: TextStyle,
    accompanimentLineTextStyle: TextStyle,
    phoneticTextStyle: TextStyle,
    useBlurEffect: Boolean,
    showTranslation: Boolean,
    showPhonetic: Boolean,
    maxContentWidth: Dp,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier) {
        val anchorOffset = remember(maxHeight) { maxHeight * 0.08f }
        Box(
            modifier =
                Modifier
                    .widthIn(max = minOf(maxWidth, maxContentWidth))
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .align(Alignment.TopCenter)
                    // The top ramp ends at the anchor, so the line being sung is never dimmed by it.
                    .lyricsViewportFade(top = minOf(anchorOffset, LyricsViewportFade.DefaultTop)),
        ) {
            Box(modifier = Modifier.fillMaxSize().outsetKaraokeLibraryMask()) {
                KaraokeLyricsView(
                    listState = listState,
                    lyrics = lyrics,
                    currentPosition = currentPosition,
                    onLineClicked = onLineClicked,
                    onLinePressed = onLinePressed,
                    textColor = textColor,
                    normalLineTextStyle = normalLineTextStyle,
                    accompanimentLineTextStyle = accompanimentLineTextStyle,
                    phoneticTextStyle = phoneticTextStyle,
                    blendMode = BlendMode.SrcOver,
                    useBlurEffect = useBlurEffect,
                    showTranslation = showTranslation,
                    showPhonetic = showPhonetic,
                    // The view starts KaraokeLibraryTopMask above the box, so the anchor moves down by
                    // the same amount to keep the active line at anchorOffset on screen.
                    offset = anchorOffset + KaraokeLibraryTopMask,
                    // Reduced from 36.dp → 20.dp → 8.dp.
                    keepAliveZone = 8.dp,
                    // The library applies this modifier to its outer box and to its list, so it must
                    // stay free of layout changes that would compound.
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

// lyrics-ui 1.0.x hard-codes a DstIn ramp over KaraokeLyricsView (20dp in at the top, 100dp out at
// the bottom) and offers no parameter to turn it off. Left in place it stacks with the box fade, and
// its short top ramp reads as the first visible line fading rather than the box edge. Measuring the
// view that much taller and lifting it by the top ramp parks both ramps outside the box, where the
// box's clip hides them and its own fade is the only one left. Re-check these against the library
// if it is upgraded.
private val KaraokeLibraryTopMask = 20.dp
private val KaraokeLibraryBottomMask = 100.dp

private fun Modifier.outsetKaraokeLibraryMask(): Modifier =
    layout { measurable, constraints ->
        if (!constraints.hasBoundedHeight) {
            val placeable = measurable.measure(constraints)
            return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        }
        val topPx = KaraokeLibraryTopMask.roundToPx()
        val tallHeight = constraints.maxHeight + topPx + KaraokeLibraryBottomMask.roundToPx()
        val placeable = measurable.measure(constraints.copy(minHeight = tallHeight, maxHeight = tallHeight))
        layout(placeable.width, constraints.maxHeight) { placeable.place(0, -topPx) }
    }
