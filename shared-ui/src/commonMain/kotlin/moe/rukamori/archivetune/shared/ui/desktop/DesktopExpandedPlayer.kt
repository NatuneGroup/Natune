package moe.rukamori.archivetune.shared.ui.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.ui.player.PlayerAction
import moe.rukamori.archivetune.shared.ui.player.PlayerIcon
import moe.rukamori.archivetune.shared.ui.player.PlayerRepeat
import moe.rukamori.archivetune.shared.ui.player.PlayerSlots
import moe.rukamori.archivetune.shared.ui.player.PlayerState
import moe.rukamori.archivetune.shared.ui.player.PlayerSymbol
import moe.rukamori.archivetune.shared.ui.resources.*
import moe.rukamori.archivetune.shared.ui.screens.mediainfo.formatTrackDuration
import moe.rukamori.archivetune.shared.ui.theme.IconSizes
import moe.rukamori.archivetune.shared.ui.theme.Space
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * The expanded player, opened from the player bar's expand action, `T` or a double click on the bar: the sleeve and
 * the transport on the left, the same lyrics renderer the lyrics pane uses on the right.
 *
 * A window at least 560 dp tall lays out in two columns; a shorter one stacks in a scrolling column, so the artwork,
 * the transport and the lyric lines are all reachable in a 500 dp window instead of being clipped. The lyrics here are
 * Natune's own renderer from [PlayerSlots.lyrics] — the pane and this view share one renderer and one scroll owner.
 */
@Composable
fun DesktopExpandedPlayer(
    state: PlayerState,
    slots: PlayerSlots,
    onClose: () -> Unit,
    onPlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onAction: (PlayerAction) -> Unit,
    onVolumeChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val artworkModifier = Modifier
        .fillMaxWidth()
        .widthIn(max = DesktopShellMetrics.expandedArtworkMax)
        .aspectRatio(1f)
        .clip(RoundedCornerShape(DesktopShellMetrics.contentCorner))
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier
            .fillMaxSize()
            .testTag("desktop-expanded-player"),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val twoColumns = maxHeight >= DesktopShellMetrics.expandedTwoColumnMinHeight
            val content: @Composable (Modifier) -> Unit = { columnModifier ->
                if (twoColumns) {
                    Row(
                        columnModifier.fillMaxSize().padding(DesktopShellMetrics.expandedPlayerPadding),
                        horizontalArrangement = Arrangement.spacedBy(DesktopShellMetrics.expandedPlayerPadding),
                    ) {
                        DesktopPlayerColumn(
                            state, slots, artworkModifier, Modifier.weight(1f).fillMaxHeight(),
                            onPlayPause, onSeekTo, onAction, onVolumeChange,
                        )
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(DesktopShellMetrics.contentCorner))
                                .background(MaterialTheme.colorScheme.surfaceContainer)
                                .testTag("desktop-expanded-lyrics"),
                        ) { slots.lyrics(Modifier.fillMaxSize()) }
                    }
                } else {
                    Column(
                        columnModifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(DesktopShellMetrics.expandedPlayerPadding),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(Space.md),
                    ) {
                        DesktopPlayerColumn(
                            state, slots, artworkModifier, Modifier.fillMaxWidth(),
                            onPlayPause, onSeekTo, onAction, onVolumeChange,
                        )
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 320.dp)
                                .clip(RoundedCornerShape(DesktopShellMetrics.contentCorner))
                                .background(MaterialTheme.colorScheme.surfaceContainer)
                                .testTag("desktop-expanded-lyrics"),
                        ) { slots.lyrics(Modifier.fillMaxSize()) }
                    }
                }
            }
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                    content(Modifier.widthIn(max = DesktopShellMetrics.expandedPlayerMaxWidth))
                }
            }
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(Space.xs)
                    .testTag("desktop-expanded-close"),
            ) {
                Icon(
                    painter = painterResource(Res.drawable.m4c_icon_close),
                    contentDescription = stringResource(Res.string.desktop_shell_close_player),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun DesktopPlayerColumn(
    state: PlayerState,
    slots: PlayerSlots,
    artworkModifier: Modifier,
    columnModifier: Modifier,
    onPlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onAction: (PlayerAction) -> Unit,
    onVolumeChange: (Float) -> Unit,
) {
    Column(
        columnModifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            slots.artwork(state.playback?.occurrenceId, artworkModifier)
        }
        Text(
            state.title,
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("desktop-expanded-title"),
        )
        if (state.artist.isNotBlank()) {
            Text(
                state.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DesktopExpandedSeek(state, onSeekTo)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            PlayerIcon(
                symbol = PlayerSymbol.SHUFFLE,
                label = stringResource(Res.string.m5_shuffle),
                onClick = { onAction(PlayerAction.SHUFFLE) },
                selected = state.shuffle,
                size = 44.dp,
                iconSize = IconSizes.medium,
            )
            PlayerIcon(
                symbol = PlayerSymbol.PREVIOUS,
                label = stringResource(Res.string.m5_previous),
                onClick = { onAction(PlayerAction.PREVIOUS) },
                enabled = state.canPrevious,
                size = 44.dp,
                iconSize = IconSizes.large,
            )
            PlayerIcon(
                symbol = if (state.playback?.playWhenReady == true) PlayerSymbol.PAUSE else PlayerSymbol.PLAY,
                label = stringResource(
                    if (state.playback?.playWhenReady == true) Res.string.integration_pause else Res.string.integration_play,
                ),
                onClick = onPlayPause,
                large = true,
                size = 64.dp,
                iconSize = 30.dp,
                modifier = Modifier.testTag("desktop-expanded-play"),
            )
            PlayerIcon(
                symbol = PlayerSymbol.NEXT,
                label = stringResource(Res.string.m5_next),
                onClick = { onAction(PlayerAction.NEXT) },
                enabled = state.canNext,
                size = 44.dp,
                iconSize = IconSizes.large,
            )
            PlayerIcon(
                symbol = PlayerSymbol.REPEAT,
                label = stringResource(Res.string.m5_repeat),
                onClick = { onAction(PlayerAction.REPEAT) },
                selected = state.repeat != PlayerRepeat.OFF,
                stateLabel = if (state.repeat == PlayerRepeat.ONE) stringResource(Res.string.m5_repeat_one) else null,
                size = 44.dp,
                iconSize = IconSizes.medium,
            )
        }
        val volume = state.volume?.takeIf { it.isFinite() }
        if (volume != null) {
            Slider(
                value = volume.coerceIn(0f, 1f),
                onValueChange = onVolumeChange,
                modifier = Modifier.width(220.dp).testTag("desktop-expanded-volume"),
            )
        }
    }
}

/** The expanded player's seek bar with its two clock labels; a drag previews, release seeks the player. */
@Composable
private fun DesktopExpandedSeek(state: PlayerState, onSeekTo: (Long) -> Unit) {
    val duration = state.playback?.durationMs?.takeIf { it > 0L } ?: return
    val live = state.playback?.positionMs ?: 0L
    var preview by remember(state.playback?.occurrenceId) { mutableStateOf<Float?>(null) }
    val fraction = preview ?: (live.toFloat() / duration).coerceIn(0f, 1f)
    val enabled = state.canSeek && state.playback?.isSeekable == true
    Column(Modifier.fillMaxWidth()) {
        Slider(
            value = fraction,
            onValueChange = { if (enabled) preview = it },
            onValueChangeFinished = {
                preview?.let { value -> onSeekTo((duration * value.toDouble()).toLong().coerceIn(0L, duration)) }
                preview = null
            },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().testTag("desktop-expanded-seek"),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                formatTrackDuration((duration * fraction.toDouble()).toLong()) ?: "0:00",
                style = MaterialTheme.typography.labelSmall,
                color = Color.Unspecified,
            )
            Text(
                formatTrackDuration(duration) ?: "0:00",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
