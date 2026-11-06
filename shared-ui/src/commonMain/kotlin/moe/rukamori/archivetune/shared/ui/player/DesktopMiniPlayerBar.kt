package moe.rukamori.archivetune.shared.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.domain.playback.PlaybackSnapshot
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.ui.marquee.MarqueeText
import moe.rukamori.archivetune.shared.ui.resources.*
import moe.rukamori.archivetune.shared.ui.screens.ContentArtwork
import moe.rukamori.archivetune.shared.ui.theme.IconSizes
import moe.rukamori.archivetune.shared.ui.theme.RowHeights
import moe.rukamori.archivetune.shared.ui.theme.Space
import org.jetbrains.compose.resources.stringResource

private val DesktopPlayerBarHeight = RowHeights.playerBar
private val DesktopSeekLineHeight = 2.dp
private val DesktopSeekTouchHeight = 12.dp

/**
 * The desktop transport bar: page-coloured (no card behind it), 72 dp tall, with a 2 dp seek line on its top edge and
 * three columns - sleeve/title, centred transport, right-hand actions. It is the window's bottom chrome rather than a
 * Material surface, so the page still reads as one sheet.
 */
@Composable
internal fun DesktopMiniPlayerBar(
    current: ContentOccurrence,
    playback: PlaybackSnapshot?,
    nativeState: PlayerNativeState,
    artwork: ContentArtwork,
    motion: Boolean,
    onExpand: () -> Unit,
    onOpenPanel: (PlayerPanel) -> Unit,
    onPlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onAction: (PlayerAction) -> Unit,
    onVolumeChange: (Float) -> Unit,
) {
    val playing = playback?.playWhenReady == true
    val matching = nativeState.matches(playback)
    val playMotion = rememberPlayerControlMotion(PlayerControlKind.PlayPause, playing, motion)
    val seekInteraction = remember { PlayerInteractionState() }
    val seekState = PlayerState(
        playback = playback,
        title = current.item.title,
        artist = current.item.subtitle,
        occurrenceIds = listOf(current.occurrenceId),
        canSeek = matching && nativeState.canSeek,
    )
    LaunchedEffect(playback?.occurrenceId, playback?.playbackGeneration, playback?.discontinuityGeneration) {
        seekInteraction.reconcile(playback)
    }
    DisposableEffect(seekInteraction) { onDispose { seekInteraction.cancelPreview() } }
    val duration = playback?.durationMs?.takeIf { it > 0L }
    val position = (seekInteraction.previewMs ?: playback?.positionMs ?: 0L).coerceAtLeast(0L)
    val fraction = if (duration != null) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val seekable = duration != null && matching && nativeState.canSeek && playback?.isSeekable == true
    Box(Modifier.fillMaxWidth().height(DesktopPlayerBarHeight).testTag("desktop-player-bar")) {
        Box(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().height(DesktopSeekLineHeight)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .testTag("desktop-player-seek-line"),
        ) {
            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
        }
        // A 12 dp hit band over the hairline keeps the seek gesture usable without drawing a slider track.
        Box(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().height(DesktopSeekTouchHeight)
                .pointerInput(seekable, duration) {
                    if (!seekable) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            seekInteraction.preview((offset.x / size.width).coerceIn(0f, 1f), seekState)
                        },
                        onHorizontalDrag = { change, _ ->
                            seekInteraction.preview((change.position.x / size.width).coerceIn(0f, 1f), seekState)
                        },
                        onDragEnd = { seekInteraction.commit(seekState, onSeekTo) },
                        onDragCancel = { seekInteraction.cancelPreview() },
                    )
                }
                .testTag("desktop-player-seek"),
        )
        Row(
            Modifier.align(Alignment.CenterStart).fillMaxWidth(0.33f).padding(start = Space.sm)
                .clip(RoundedCornerShape(10.dp))
                .spaceTogglesPlayback(onPlayPause)
                .clickable(onClick = onExpand)
                .testTag("desktop-player-title"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            artwork(current.item, Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)))
            Column(Modifier.weight(1f)) {
                MarqueeText(current.item.title, style = MaterialTheme.typography.titleSmall)
                if (current.item.subtitle.isNotBlank()) {
                    MarqueeText(
                        current.item.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Row(
            Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            PlayerIcon(
                symbol = PlayerSymbol.SHUFFLE,
                label = stringResource(Res.string.m5_shuffle),
                onClick = { onAction(PlayerAction.SHUFFLE) },
                selected = nativeState.shuffle,
                size = 36.dp,
                iconSize = IconSizes.small,
            )
            PlayerIcon(
                symbol = PlayerSymbol.PREVIOUS,
                label = stringResource(Res.string.m5_previous),
                onClick = { onAction(PlayerAction.PREVIOUS) },
                enabled = matching && nativeState.canPrevious,
                size = 36.dp,
                iconSize = IconSizes.medium,
            )
            TextButton(onClick = onPlayPause) {
                Text(
                    stringResource(if (playing) Res.string.integration_pause else Res.string.integration_play),
                    playMotion.modifier,
                )
            }
            PlayerIcon(
                symbol = PlayerSymbol.NEXT,
                label = stringResource(Res.string.m5_next),
                onClick = { onAction(PlayerAction.NEXT) },
                enabled = matching && nativeState.canNext,
                size = 36.dp,
                iconSize = IconSizes.medium,
            )
            PlayerIcon(
                symbol = PlayerSymbol.REPEAT,
                label = stringResource(Res.string.m5_repeat),
                onClick = { onAction(PlayerAction.REPEAT) },
                selected = nativeState.repeat != PlayerRepeat.OFF,
                stateLabel = if (nativeState.repeat == PlayerRepeat.ONE) {
                    stringResource(Res.string.m5_repeat_one)
                } else {
                    null
                },
                size = 36.dp,
                iconSize = IconSizes.small,
            )
        }
        Row(
            Modifier.align(Alignment.CenterEnd).fillMaxWidth(0.33f).padding(end = Space.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            if (PlayerAction.LIKE in nativeState.actionSupport) {
                PlayerIcon(
                    symbol = PlayerSymbol.HEART,
                    label = stringResource(Res.string.m5_like),
                    onClick = { onAction(PlayerAction.LIKE) },
                    selected = matching && nativeState.liked,
                    size = 36.dp,
                    iconSize = IconSizes.small,
                )
            }
            PlayerIcon(
                symbol = PlayerSymbol.LYRICS,
                label = stringResource(Res.string.m5_lyrics),
                onClick = { onOpenPanel(PlayerPanel.LYRICS) },
                size = 36.dp,
                iconSize = IconSizes.small,
            )
            PlayerIcon(
                symbol = PlayerSymbol.QUEUE,
                label = stringResource(Res.string.m5_queue),
                onClick = { onOpenPanel(PlayerPanel.QUEUE) },
                size = 36.dp,
                iconSize = IconSizes.small,
            )
            val volume = nativeState.volume?.takeIf { it.isFinite() }
            if (volume != null) {
                Slider(
                    value = volume.coerceIn(0f, 1f),
                    onValueChange = onVolumeChange,
                    modifier = Modifier.width(88.dp).height(24.dp).testTag("desktop-player-volume"),
                )
            }
            PlayerIcon(
                symbol = PlayerSymbol.EXPAND,
                label = stringResource(Res.string.native_chrome_open_player),
                onClick = onExpand,
                size = 36.dp,
                iconSize = IconSizes.small,
            )
        }
    }
}
