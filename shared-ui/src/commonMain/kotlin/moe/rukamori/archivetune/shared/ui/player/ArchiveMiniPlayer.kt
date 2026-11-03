package moe.rukamori.archivetune.shared.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.domain.playback.PlaybackSnapshot
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.ui.marquee.MarqueeText
import moe.rukamori.archivetune.shared.ui.resources.*
import moe.rukamori.archivetune.shared.ui.screens.ContentArtwork
import org.jetbrains.compose.resources.stringResource

internal fun isWideTransportBar(maxWidth: Dp): Boolean = maxWidth >= 600.dp

@Composable
internal fun ArchiveMiniPlayer(
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
    modifier: Modifier = Modifier,
) {
    val playing = playback?.playWhenReady == true
    val matching = nativeState.matches(playback)
    val playMotion = rememberPlayerControlMotion(PlayerControlKind.PlayPause, playing, motion)
    val seekInteraction = remember { PlayerInteractionState() }
    val seekInteractionSource = remember { MutableInteractionSource() }
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
    LaunchedEffect(seekInteractionSource) {
        seekInteractionSource.interactions.collect { event ->
            if (event is DragInteraction.Cancel) seekInteraction.cancelPreview()
        }
    }
    CompositionLocalProvider(
        LocalPlayerControlMotion provides PlayerControlMotionEnvironment(motion, nativeState.repeat),
    ) {
        BoxWithConstraints(modifier.fillMaxWidth()) {
            val barWidth = maxWidth
            if (!isWideTransportBar(barWidth)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clickable(onClick = onExpand)
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .testTag("compact-mini-player"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    artwork(current.item, Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)))
                    Column(Modifier.weight(1f)) {
                        MarqueeText(current.item.title, style = MaterialTheme.typography.bodyMedium)
                        if (current.item.subtitle.isNotBlank()) {
                            MarqueeText(
                                current.item.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
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
                        iconSize = 20.dp,
                    )
                }
            } else {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 72.dp)
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .testTag("desktop-transport-bar"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Row(
                        Modifier.weight(0.3f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable(onClick = onExpand),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            artwork(current.item, Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)))
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
                        PlayerIcon(
                            symbol = PlayerSymbol.HEART,
                            label = stringResource(Res.string.m5_like),
                            onClick = { onAction(PlayerAction.LIKE) },
                            selected = matching && nativeState.liked,
                            size = 36.dp,
                            iconSize = 18.dp,
                        )
                    }
                    Column(
                        Modifier.weight(0.45f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            PlayerIcon(
                                symbol = PlayerSymbol.SHUFFLE,
                                label = stringResource(Res.string.m5_shuffle),
                                onClick = { onAction(PlayerAction.SHUFFLE) },
                                selected = nativeState.shuffle,
                                size = 36.dp,
                                iconSize = 18.dp,
                            )
                            PlayerIcon(
                                symbol = PlayerSymbol.PREVIOUS,
                                label = stringResource(Res.string.m5_previous),
                                onClick = { onAction(PlayerAction.PREVIOUS) },
                                enabled = matching && nativeState.canPrevious,
                                size = 36.dp,
                                iconSize = 20.dp,
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
                                iconSize = 20.dp,
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
                                iconSize = 18.dp,
                            )
                        }
                        val duration = playback?.durationMs?.takeIf { it > 0L }
                        val position = (seekInteraction.previewMs ?: playback?.positionMs ?: 0L).coerceAtLeast(0L)
                        val seekable = duration != null && matching && nativeState.canSeek && playback.isSeekable
                        Row(
                            Modifier.fillMaxWidth().testTag("mini-player-seek"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(playerTime(position), style = MaterialTheme.typography.labelSmall)
                            Slider(
                                value = if (duration != null) {
                                    (position.toDouble() / duration).toFloat().coerceIn(0f, 1f)
                                } else {
                                    0f
                                },
                                onValueChange = { fraction ->
                                    seekInteraction.preview(fraction, seekState)
                                },
                                onValueChangeFinished = {
                                    seekInteraction.commit(seekState, onSeekTo)
                                },
                                interactionSource = seekInteractionSource,
                                enabled = seekable,
                                modifier = Modifier.weight(1f).height(24.dp),
                            )
                            Text(
                                if (duration != null) playerTime(duration) else "—",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                    Row(
                        Modifier.weight(0.25f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End,
                    ) {
                        PlayerIcon(
                            symbol = PlayerSymbol.LYRICS,
                            label = stringResource(Res.string.m5_lyrics),
                            onClick = { onOpenPanel(PlayerPanel.LYRICS) },
                            size = 36.dp,
                            iconSize = 18.dp,
                        )
                        PlayerIcon(
                            symbol = PlayerSymbol.QUEUE,
                            label = stringResource(Res.string.m5_queue),
                            onClick = { onOpenPanel(PlayerPanel.QUEUE) },
                            size = 36.dp,
                            iconSize = 18.dp,
                        )
                        val volume = nativeState.volume?.takeIf { it.isFinite() }
                        if (barWidth >= 840.dp && volume != null) {
                            Slider(
                                value = volume.coerceIn(0f, 1f),
                                onValueChange = onVolumeChange,
                                modifier = Modifier.width(88.dp).height(24.dp).testTag("mini-player-volume"),
                            )
                        }
                        PlayerIcon(
                            symbol = PlayerSymbol.EXPAND,
                            label = stringResource(Res.string.native_chrome_open_player),
                            onClick = onExpand,
                            size = 36.dp,
                            iconSize = 18.dp,
                        )
                    }
                }
            }
        }
    }
}
