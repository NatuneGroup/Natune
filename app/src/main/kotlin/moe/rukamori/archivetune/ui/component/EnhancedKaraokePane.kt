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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mocharealm.accompanist.lyrics.core.model.ISyncedLine
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import moe.rukamori.archivetune.shared.domain.lyrics.LyricsCredits
import moe.rukamori.archivetune.shared.ui.lyrics.LyricsViewportFade
import moe.rukamori.archivetune.shared.ui.lyrics.lyricsViewportFade

/**
 * The synced Enhanced lyrics list inside a box whose top and bottom edges fade, wherever the list is scrolled. The
 * active line sits 15% of the box height below the top. The alternate styles retain [BasicSyncedLyricsList].
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
    manualScrolling: Boolean = false,
    /** The document's credits, for the writer footer at the end of the list. */
    credits: LyricsCredits? = null,
) {
    BoxWithConstraints(modifier = modifier) {
        // The inner Box's layout scope shadows this one, so the height is read here and handed down.
        val viewportHeight = maxHeight
        val anchorOffset = remember(maxHeight) { maxHeight * 0.15f }
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
            BasicSyncedLyricsList(
                listState = listState,
                lyrics = lyrics,
                currentPosition = currentPosition,
                onLineClicked = onLineClicked,
                onLinePressed = onLinePressed,
                textColor = textColor,
                normalLineTextStyle = normalLineTextStyle,
                accompanimentLineTextStyle = accompanimentLineTextStyle,
                phoneticTextStyle = phoneticTextStyle,
                showTranslation = showTranslation,
                showPhonetic = showPhonetic,
                anchorOffset = anchorOffset,
                viewportHeight = viewportHeight,
                modifier = Modifier.fillMaxSize(),
                useBlurEffect = useBlurEffect,
                manualScrolling = manualScrolling,
                credits = credits,
            )
        }
    }
}

/** The alternate styles keep their existing in-house renderer on every Android API level. */
internal val enhancedLibraryViewActive: Boolean
    get() = false

internal val enhancedLineSyncedBreakMs: Long
    get() = ENHANCED_INTERLUDE_MIN_BREAK_MS

internal fun enhancedPaneLineStarts(lyrics: SyncedLyrics): IntArray {
    val starts = IntArray(lyrics.lines.size) { lyrics.lines[it].start }
    return enhancedActivationStarts(
        starts,
        IntArray(lyrics.lines.size) { lyrics.lines[it].end },
        BooleanArray(lyrics.lines.size) { lyrics.lines[it].hasWordTiming() },
    )
}
