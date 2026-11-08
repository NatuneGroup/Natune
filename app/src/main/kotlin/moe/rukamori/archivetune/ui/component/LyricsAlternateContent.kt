package moe.rukamori.archivetune.ui.component

// SPDX-License-Identifier: GPL-3.0-only
// Portions © Rukamori — github.com/rukamori
// Portions © vossgraves — github.com/vossgraves

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mocharealm.accompanist.lyrics.core.model.ISyncedLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.shared.ui.lyrics.lyricsViewportFade
import moe.rukamori.archivetune.models.MediaMetadata

private const val LYRIC_FIRST_FOCUS_EMPTY_FRAMES = 6
private const val LYRIC_FIRST_FOCUS_SETTLE_FRAMES = 42
@Immutable
internal data class AlternatePlainLyrics(
    val items: List<AlternatePlainLyricLine>,
)

@Immutable
internal data class AlternatePlainLyricLine(
    val itemId: String,
    val selectionId: String,
    val text: String,
)

@Immutable
internal data class AlternateLyricSelectionLine(
    val itemId: String,
    val selectionId: String,
    val text: String,
)

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PlainLyricsView(
    lines: AlternatePlainLyrics,
    listState: LazyListState,
    selectedLineKeys: Set<String>,
    textColor: Color,
    textStyle: TextStyle,
    onLineClicked: (String) -> Unit,
    onLinePressed: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val contentPadding =
        remember {
            PaddingValues(
                start = 12.dp,
                top = 120.dp,
                end = 12.dp,
                bottom = 96.dp,
            )
        }

    LazyColumn(
        state = listState,
        modifier = modifier.lyricsViewportFade(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(
            items = lines.items,
            key = { line -> line.itemId },
            contentType = { "plain_lyric_line" },
        ) { line ->
            PlainLyricLineItem(
                line = line,
                selected = line.selectionId in selectedLineKeys,
                textColor = textColor,
                textStyle = textStyle,
                onLineClicked = onLineClicked,
                onLinePressed = onLinePressed,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PlainLyricLineItem(
    line: AlternatePlainLyricLine,
    selected: Boolean,
    textColor: Color,
    textStyle: TextStyle,
    onLineClicked: (String) -> Unit,
    onLinePressed: (String) -> Unit,
) {
    val contentColor =
        if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            textColor
        }

    Text(
        text = line.text,
        style = textStyle,
        color = contentColor,
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .combinedClickable(
                    onClick = { onLineClicked(line.selectionId) },
                    onLongClick = { onLinePressed(line.selectionId) },
                ).padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AlternateLyricsSelectionBottomSheet(
    lines: List<AlternateLyricSelectionLine>,
    selectedLineKeys: Set<String>,
    onToggleLine: (String) -> Unit,
    onDismissRequest: () -> Unit,
    onShareSelected: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val firstSelectedIndex =
        remember(lines, selectedLineKeys) {
            lines
                .indexOfFirst { line -> line.selectionId in selectedLineKeys }
                .coerceAtLeast(0)
        }
    val sheetListState =
        rememberLazyListState(
            initialFirstVisibleItemIndex = firstSelectedIndex,
        )
    val selectedCount = selectedLineKeys.size

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp),
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.share_selected),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = pluralStringResource(R.plurals.n_element, selectedCount, selectedCount),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onDismissRequest) {
                    Text(text = stringResource(R.string.close))
                }
            }

            LazyColumn(
                state = sheetListState,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(
                    items = lines,
                    key = { line -> line.itemId },
                    contentType = { "lyric_selection_line" },
                ) { line ->
                    AlternateLyricsSelectionLineItem(
                        line = line,
                        selected = line.selectionId in selectedLineKeys,
                        onClick = { onToggleLine(line.selectionId) },
                    )
                }
            }

            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismissRequest) {
                    Text(text = stringResource(R.string.cancel))
                }
                Button(
                    onClick = onShareSelected,
                    enabled = selectedCount > 0,
                    shape = MaterialTheme.shapes.extraLarge,
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.share),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(text = stringResource(R.string.share_selected))
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AlternateLyricsSelectionLineItem(
    line: AlternateLyricSelectionLine,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val containerColor =
        if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        }
    val contentColor =
        if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }

    Card(
        onClick = onClick,
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 18.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = line.text,
                style = MaterialTheme.typography.headlineSmall,
                color = contentColor,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            )
        }
    }
}

// Waits, without scrolling, until the list has placed the active line and stopped moving.
// Both loops are frame-bounded so an empty list or a target the list never shows cannot hold
// the lyrics hidden; LYRIC_FIRST_FOCUS_TIMEOUT_MS remains the outer ceiling.
internal suspend fun LazyListState.awaitKaraokeFirstFocus(index: Int) {
    var framesAwaited = 0
    var emptyFrames = 0
    while (framesAwaited < LYRIC_FIRST_FOCUS_SETTLE_FRAMES) {
        withFrameNanos { }
        framesAwaited++
        if (layoutInfo.totalItemsCount == 0) {
            emptyFrames++
            if (emptyFrames >= LYRIC_FIRST_FOCUS_EMPTY_FRAMES) return
        } else {
            emptyFrames = 0
            val itemInfo = layoutInfo.visibleItemsInfo.firstOrNull { item -> item.index == index }
            if (itemInfo != null && !isScrollInProgress) return
        }
    }
}

internal fun ISyncedLine.alternateLineText(): String =
    when (this) {
        is KaraokeLine -> syllables.joinToString("") { it.content }
        is SyncedLine -> content
        else -> ""
    }

internal fun ISyncedLine.alternateSelectionKey(text: String = alternateLineText()): String = "$start:$end:${text.hashCode()}"

/**
 * Renders exactly one karaoke line cluster — the joined phonetic (romanisation) row above, the
 * main line with its per-word colour sweep and the translation row(s) below — as a compact custom
 * renderer.
 *
 * Deliberately NOT the library's [KaraokeLyricsView]: that view draws a permanent vertical
 * fading-edge mask over its whole viewport (a 20dp fade-in at the top and a 100dp fade-out at the
 * bottom, applied with DstIn over everything). In a ~168dp strip that mask dominates — a long line
 * that wraps onto a second row pushes that row (and the translation) into the ramp and visibly
 * fades "the more line there is". This renderer clips nothing and fades nothing, so wrapped lines
 * stay fully opaque.
 *
 * [AnimatedContent] handles the line-change cross-fade; its content is bottom-aligned so the
 * cluster sits directly above the song-info block.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AlternateSingleActiveKaraokeLine(
    activeLine: ISyncedLine?,
    currentPosition: () -> Int,
    textColor: Color,
    normalTextStyle: TextStyle,
    accompanimentTextStyle: TextStyle,
    phoneticTextStyle: TextStyle,
    showTranslation: Boolean,
    onLineClick: (ISyncedLine) -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedContent(
        targetState = activeLine,
        transitionSpec = {
            (
                fadeIn(tween(durationMillis = 240, easing = LinearEasing)) +
                    slideInVertically(
                        animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
                        initialOffsetY = { it / 5 },
                    )
            ).togetherWith(fadeOut(tween(durationMillis = 160, easing = LinearEasing)))
        },
        contentAlignment = Alignment.BottomStart,
        label = "single-active-lyric-line",
        modifier = modifier,
    ) { line ->
        if (line != null) {
            AlternateSingleActiveLineCluster(
                line = line,
                currentPosition = currentPosition,
                textColor = textColor,
                normalTextStyle = normalTextStyle,
                accompanimentTextStyle = accompanimentTextStyle,
                phoneticTextStyle = phoneticTextStyle,
                showTranslation = showTranslation,
                onLineClick = onLineClick,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(modifier = Modifier.fillMaxSize())
        }
    }
}

/** The one-line cluster itself; the karaoke sweep recomposes only this Text. */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AlternateSingleActiveLineCluster(
    line: ISyncedLine,
    currentPosition: () -> Int,
    textColor: Color,
    normalTextStyle: TextStyle,
    accompanimentTextStyle: TextStyle,
    phoneticTextStyle: TextStyle,
    showTranslation: Boolean,
    onLineClick: (ISyncedLine) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    LaunchedEffect(line.start) { scrollState.scrollTo(0) }
    Column(
        modifier =
            modifier
                .verticalScroll(scrollState)
                .clickable(enabled = line.start > 0) { onLineClick(line) },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        when (line) {
            is KaraokeLine -> {
                (line as? KaraokeLine.MainKaraokeLine)?.accompanimentLines?.forEach { accompaniment ->
                    val accompanimentText =
                        (accompaniment as? KaraokeLine)?.syllables?.joinSyllableContents().orEmpty()
                    if (accompanimentText.isNotBlank()) {
                        Text(
                            text = accompanimentText,
                            style = accompanimentTextStyle,
                            color = textColor.copy(alpha = 0.45f),
                        )
                    }
                }

                val phonetic =
                    (line as? KaraokeLine.MainKaraokeLine)?.phonetic?.takeIf { it.isNotBlank() }
                        ?: line.syllables
                            .mapNotNull { it.phonetic?.trim() }
                            .filter { it.isNotEmpty() }
                            .joinToString(" ")
                if (phonetic.isNotEmpty()) {
                    Text(
                        text = phonetic,
                        style = phoneticTextStyle,
                        color = textColor.copy(alpha = 0.75f),
                    )
                }

                // Only a row with real per-word spans may sweep. A line-synced LRC row is published as a
                // KaraokeLine just to hang its reading on, with every syllable pegged at the line start;
                // sweeping those lit the whole line at one instant. Gate the single-active strip too.
                if (line.hasWordTiming()) {
                    KaraokeSweepText(
                        syllables = line.syllables,
                        currentPosition = currentPosition,
                        textColor = textColor,
                        textStyle = normalTextStyle,
                    )
                } else {
                    Text(
                        text = line.alternateLineText(),
                        style = normalTextStyle,
                        color = textColor,
                    )
                }

                if (showTranslation) {
                    TranslationStack(
                        translation = line.translation,
                        textColor = textColor,
                        phoneticTextStyle = phoneticTextStyle,
                    )
                }
            }

            else -> {
                // Plain line-synced lyrics: no word timing — the whole active line renders bright,
                // with romanisation/translation stacked underneath it.
                Text(
                    text = line.alternateLineText(),
                    style = normalTextStyle,
                    color = textColor,
                )
                if (showTranslation) {
                    TranslationStack(
                        translation = (line as? SyncedLine)?.translation,
                        textColor = textColor,
                        phoneticTextStyle = phoneticTextStyle,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AlternateLyricsShareDialog(
    data: Triple<String, String, String>,
    mediaMetadata: MediaMetadata?,
    onDismiss: () -> Unit,
    onShareImage: () -> Unit,
) {
    val context = LocalContext.current
    val (lyricsText, songTitle, artists) = data
    BasicAlertDialog(onDismissRequest = { onDismiss() }) {
        Card(
            shape = RoundedCornerShape(28.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier =
                Modifier
                    .padding(16.dp)
                    .fillMaxWidth(0.85f),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = stringResource(R.string.share_lyrics),
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                shareLyricsAsText(
                                    context = context,
                                    payload = LyricsSharePayload(lyricsText, songTitle, artists),
                                    songId = mediaMetadata?.id,
                                )
                                onDismiss()
                            }.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.share),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.share_as_text),
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                onShareImage()
                            }.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.share),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.share_as_image),
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Text(
                        text = stringResource(R.string.cancel),
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Medium,
                        modifier =
                            Modifier
                                .clickable { onDismiss() }
                                .padding(vertical = 8.dp, horizontal = 12.dp),
                    )
                }
            }
        }
    }
}
