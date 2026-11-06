package moe.rukamori.archivetune.shared.ui.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.domain.playback.PlaybackSnapshot
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.ui.marquee.MarqueeText
import moe.rukamori.archivetune.shared.ui.player.PlayerAction
import moe.rukamori.archivetune.shared.ui.player.LocalPlayerControlMotion
import moe.rukamori.archivetune.shared.ui.player.PlayerControlMotionEnvironment
import moe.rukamori.archivetune.shared.ui.player.PlayerIcon
import moe.rukamori.archivetune.shared.ui.player.PlayerInteractionState
import moe.rukamori.archivetune.shared.ui.player.PlayerNativeState
import moe.rukamori.archivetune.shared.ui.player.PlayerRepeat
import moe.rukamori.archivetune.shared.ui.player.PlayerState
import moe.rukamori.archivetune.shared.ui.player.PlayerSymbol
import moe.rukamori.archivetune.shared.ui.player.matches
import moe.rukamori.archivetune.shared.ui.player.spaceTogglesPlayback
import moe.rukamori.archivetune.shared.ui.player.withNativeState
import moe.rukamori.archivetune.shared.ui.resources.*
import moe.rukamori.archivetune.shared.ui.screens.ContentArtwork
import moe.rukamori.archivetune.shared.ui.screens.mediainfo.formatTrackDuration
import moe.rukamori.archivetune.shared.ui.theme.IconSizes
import moe.rukamori.archivetune.shared.ui.theme.Space
import org.jetbrains.compose.resources.stringResource

/**
 * The bottom player bar, shaped like the reference desktop app's: a full-window strip, a slim seek track along its
 * top edge, and one row under it with the sleeve and titles at the left, the transport in the middle and the panels,
 * volume and expand action at the right.
 *
 * It is the same bar as the phone's mini player in what it does, not in how it looks: the pane buttons open the
 * docked queue and lyrics panes instead of a sheet, and the expand button opens the expanded player. Nothing here
 * owns playback; every control dispatches to the host's one player.
 */
@Composable
fun DesktopPlayerBar(
    current: ContentOccurrence,
    playback: PlaybackSnapshot?,
    nativeState: PlayerNativeState,
    artwork: ContentArtwork,
    motion: Boolean,
    panes: DesktopShellState,
    onExpand: () -> Unit,
    onPlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onAction: (PlayerAction) -> Unit,
    onVolumeChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val playing = playback?.playWhenReady == true
    val matching = nativeState.matches(playback)
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
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier
            .fillMaxWidth()
            .height(DesktopShellMetrics.playerBarHeight)
            .testTag("desktop-player-bar"),
    ) {
        CompositionLocalProvider(
            LocalPlayerControlMotion provides PlayerControlMotionEnvironment(motion, nativeState.repeat),
        ) {
        Column(Modifier.fillMaxSize()) {
            DesktopSeekTrack(
                fraction = fraction,
                seekable = seekable,
                onPreview = { seekInteraction.preview(it, seekState) },
                onCommit = { seekInteraction.commit(seekState, onSeekTo) },
                onCancel = { seekInteraction.cancelPreview() },
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(DesktopShellMetrics.playerControlsHeight)
                    .padding(horizontal = Space.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(10.dp))
                        .spaceTogglesPlayback(onPlayPause)
                        .clickable(onClick = onExpand)
                        .testTag("desktop-player-title"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.xs),
                ) {
                    artwork(current.item, Modifier.size(DesktopShellMetrics.playerArtworkSize).clip(RoundedCornerShape(10.dp)))
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
                    DesktopPlayPause(playing = playing, enabled = matching, onClick = onPlayPause)
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
                        stateLabel = if (nativeState.repeat == PlayerRepeat.ONE) stringResource(Res.string.m5_repeat_one) else null,
                        size = 36.dp,
                        iconSize = IconSizes.small,
                    )
                }
                Row(
                    Modifier.weight(1f).fillMaxHeight(),
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
                    DesktopPlayerPosition(position = position, duration = duration)
                    PlayerIcon(
                        symbol = PlayerSymbol.LYRICS,
                        label = stringResource(Res.string.desktop_shell_lyrics_pane),
                        onClick = { panes.toggle(DesktopPane.Lyrics) },
                        selected = panes.lyricsOpen,
                        size = 36.dp,
                        iconSize = IconSizes.small,
                    )
                    PlayerIcon(
                        symbol = PlayerSymbol.QUEUE,
                        label = stringResource(Res.string.desktop_shell_queue_pane),
                        onClick = { panes.toggle(DesktopPane.Queue) },
                        selected = panes.queueOpen,
                        size = 36.dp,
                        iconSize = IconSizes.small,
                    )
                    val volume = nativeState.volume?.takeIf { it.isFinite() }
                    if (volume != null) {
                        Slider(
                            value = volume.coerceIn(0f, 1f),
                            onValueChange = onVolumeChange,
                            modifier = Modifier
                                .width(DesktopShellMetrics.playerVolumeWidth)
                                .height(24.dp)
                                .testTag("desktop-player-volume"),
                        )
                    }
                    PlayerIcon(
                        symbol = PlayerSymbol.EXPAND,
                        label = stringResource(Res.string.desktop_shell_open_player),
                        onClick = onExpand,
                        size = 36.dp,
                        iconSize = IconSizes.small,
                    )
                }
            }
        }
    }
    }
}

/**
 * The seek track along the bar's top edge: a drag or a tap seeks, and a cancel restores the live position. The bar is
 * thin on purpose so the transport row stays at 72 dp in an 800 x 500 window.
 */
@Composable
private fun DesktopSeekTrack(
    fraction: Float,
    seekable: Boolean,
    onPreview: (Float) -> Unit,
    onCommit: () -> Unit,
    onCancel: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(DesktopShellMetrics.playerSeekTrackHeight)
            .padding(horizontal = Space.md),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .semantics { progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f) }
                .pointerInput(seekable) {
                    if (!seekable) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { offset -> onPreview((offset.x / size.width).coerceIn(0f, 1f)) },
                        onHorizontalDrag = { change, _ -> onPreview((change.position.x / size.width).coerceIn(0f, 1f)) },
                        onDragEnd = { onCommit() },
                        onDragCancel = { onCancel() },
                    )
                }
                .pointerInput(seekable) {
                    if (!seekable) return@pointerInput
                    detectTapGestures(
                        onTap = { offset ->
                            onPreview((offset.x / size.width).coerceIn(0f, 1f))
                            onCommit()
                        },
                    )
                }
                .testTag("desktop-player-seek"),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary)
                    .testTag("desktop-player-seek-fill"),
            )
        }
    }
}

@Composable
private fun DesktopPlayPause(
    playing: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    PlayerIcon(
        symbol = if (playing) PlayerSymbol.PAUSE else PlayerSymbol.PLAY,
        label = stringResource(if (playing) Res.string.integration_pause else Res.string.integration_play),
        onClick = onClick,
        enabled = enabled,
        large = true,
        size = 44.dp,
        iconSize = IconSizes.large,
    )
}

/** Position over duration at the right of the transport row, hidden while the duration is unknown. */
@Composable
private fun DesktopPlayerPosition(position: Long, duration: Long?) {
    val total = duration ?: return
    val elapsed = formatTrackDuration(position) ?: "0:00"
    val remaining = formatTrackDuration(total) ?: "0:00"
    Text(
        "$elapsed / $remaining",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Space.xs).testTag("desktop-player-position"),
    )
}

/**
 * The display state both desktop player surfaces share: the current occurrence's titles plus everything the native
 * owner actually answers. A control outside [PlayerNativeState.actionSupport] is not drawn by either surface.
 */
internal fun desktopPlayerState(
    current: ContentOccurrence,
    playback: PlaybackSnapshot?,
    native: PlayerNativeState,
    motion: Boolean,
): PlayerState {
    val base = PlayerState(
        playback = playback,
        title = current.item.title,
        artist = current.item.subtitle,
        occurrenceIds = listOf(current.occurrenceId),
        canSeek = native.matches(playback) && native.canSeek,
    )
    return base.withNativeState(native, reducedMotion = !motion)
}
