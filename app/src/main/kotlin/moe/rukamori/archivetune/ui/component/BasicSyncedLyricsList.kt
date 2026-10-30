package moe.rukamori.archivetune.ui.component

import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mocharealm.accompanist.lyrics.core.model.ISyncedLine
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeAlignment
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import kotlinx.coroutines.delay
import moe.rukamori.archivetune.LocalAnimationsDisabled

/**
 * Index of the last line whose start time has been reached, or -1 before the first line.
 * [starts] must be sorted ascending, which synced lyrics always are.
 */
internal fun activeSyncedLineIndex(starts: IntArray, position: Int): Int {
    var low = 0
    var high = starts.lastIndex
    var result = -1
    while (low <= high) {
        val mid = (low + high) ushr 1
        if (starts[mid] <= position) {
            result = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return result
}

/** How long auto-scroll must still hold off so it resumes [resumeAfterMs] after the last user drag. */
internal fun syncedLyricsAutoScrollDelayMs(nowMs: Long, lastUserDragMs: Long, resumeAfterMs: Long): Long {
    if (lastUserDragMs <= 0L) return 0L
    return (resumeAfterMs - (nowMs - lastUserDragMs)).coerceAtLeast(0L)
}

/**
 * The synced-lyrics list used where lyrics-ui's KaraokeLyricsView cannot run (it needs API 29).
 * It owns its auto-scroll the way the library does, so callers that wait for the active line to be
 * visible behave the same on both paths. List indices match [SyncedLyrics.lines] one to one.
 */
@Composable
internal fun BasicSyncedLyricsList(
    listState: LazyListState,
    lyrics: SyncedLyrics,
    currentPosition: () -> Int,
    onLineClicked: (ISyncedLine) -> Unit,
    onLinePressed: (ISyncedLine) -> Unit,
    textColor: Color,
    normalLineTextStyle: TextStyle,
    accompanimentLineTextStyle: TextStyle,
    phoneticTextStyle: TextStyle,
    showTranslation: Boolean,
    showPhonetic: Boolean,
    anchorOffset: Dp,
    viewportHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val lines = lyrics.lines
    val starts = remember(lines) { IntArray(lines.size) { lines[it].start } }
    val activeIndex by remember(starts) {
        derivedStateOf { activeSyncedLineIndex(starts, currentPosition()) }
    }
    val animationsDisabled = LocalAnimationsDisabled.current
    var lastUserDragMs by remember(listState) { mutableLongStateOf(0L) }

    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction) lastUserDragMs = SystemClock.uptimeMillis()
        }
    }

    LaunchedEffect(listState, activeIndex, lastUserDragMs) {
        val target = activeIndex
        if (target !in lines.indices) return@LaunchedEffect
        val wait = syncedLyricsAutoScrollDelayMs(SystemClock.uptimeMillis(), lastUserDragMs, AUTO_SCROLL_RESUME_MS)
        if (wait > 0L) delay(wait)
        if (listState.isScrollInProgress) return@LaunchedEffect
        val alreadyAnchored = listState.firstVisibleItemIndex == target && listState.firstVisibleItemScrollOffset == 0
        if (alreadyAnchored) return@LaunchedEffect
        val firstFocus = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        if (animationsDisabled || firstFocus) {
            listState.scrollToItem(target)
        } else {
            listState.animateScrollToItem(target)
        }
    }

    val bottomPadding = (viewportHeight - anchorOffset).coerceAtLeast(0.dp)
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(top = anchorOffset, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(LINE_SPACING),
        modifier = modifier,
    ) {
        itemsIndexed(lines) { index, line ->
            BasicSyncedLyricLine(
                line = line,
                isActive = index == activeIndex,
                currentPosition = currentPosition,
                onClick = { onLineClicked(line) },
                onLongClick = { onLinePressed(line) },
                textColor = textColor,
                normalLineTextStyle = normalLineTextStyle,
                accompanimentLineTextStyle = accompanimentLineTextStyle,
                phoneticTextStyle = phoneticTextStyle,
                showTranslation = showTranslation,
                showPhonetic = showPhonetic,
                animationsDisabled = animationsDisabled,
            )
        }
    }
}

@Composable
private fun BasicSyncedLyricLine(
    line: ISyncedLine,
    isActive: Boolean,
    currentPosition: () -> Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    textColor: Color,
    normalLineTextStyle: TextStyle,
    accompanimentLineTextStyle: TextStyle,
    phoneticTextStyle: TextStyle,
    showTranslation: Boolean,
    showPhonetic: Boolean,
    animationsDisabled: Boolean,
) {
    val targetAlpha = if (isActive) 1f else INACTIVE_ALPHA
    val alpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(durationMillis = if (animationsDisabled) 0 else 240),
        label = "basic-synced-line-alpha",
    )
    val karaoke = line as? KaraokeLine
    val endAligned = karaoke?.alignment == KaraokeAlignment.End
    val horizontal = if (endAligned) Alignment.End else Alignment.Start
    val textAlign = if (endAligned) TextAlign.End else TextAlign.Start
    Column(
        horizontalAlignment = horizontal,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 24.dp, vertical = 6.dp)
                .graphicsLayer { this.alpha = alpha },
    ) {
        if (showPhonetic && karaoke != null) {
            val phonetic =
                (karaoke as? KaraokeLine.MainKaraokeLine)?.phonetic?.takeIf { it.isNotBlank() }
                    ?: karaoke.syllables
                        .mapNotNull { it.phonetic?.trim() }
                        .filter { it.isNotEmpty() }
                        .joinToString(" ")
            if (phonetic.isNotEmpty()) {
                Text(text = phonetic, style = phoneticTextStyle, color = textColor.copy(alpha = 0.75f), textAlign = textAlign)
            }
        }
        when {
            karaoke != null && isActive ->
                KaraokeSweepText(
                    syllables = karaoke.syllables,
                    currentPosition = currentPosition,
                    textColor = textColor,
                    textStyle = normalLineTextStyle,
                )
            karaoke != null ->
                Text(text = karaoke.syllables.joinSyllableContents(), style = normalLineTextStyle, color = textColor, textAlign = textAlign)
            line is SyncedLine ->
                Text(text = line.content, style = normalLineTextStyle, color = textColor, textAlign = textAlign)
        }
        (karaoke as? KaraokeLine.MainKaraokeLine)?.accompanimentLines?.forEach { accompaniment ->
            val accompanimentText = (accompaniment as? KaraokeLine)?.syllables?.joinSyllableContents().orEmpty()
            if (accompanimentText.isNotBlank()) {
                Text(
                    text = accompanimentText,
                    style = accompanimentLineTextStyle,
                    color = textColor.copy(alpha = 0.6f),
                    textAlign = textAlign,
                )
            }
        }
        if (showTranslation) {
            TranslationStack(
                translation = karaoke?.translation ?: (line as? SyncedLine)?.translation,
                textColor = textColor,
                phoneticTextStyle = phoneticTextStyle,
            )
        }
    }
}

private const val INACTIVE_ALPHA = 0.45f
private const val AUTO_SCROLL_RESUME_MS = 3_000L
private val LINE_SPACING = 4.dp
