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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
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
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.ui.player.LocalPlayerControlMotion
import moe.rukamori.archivetune.shared.ui.player.LocalPlayerTrackMotion
import moe.rukamori.archivetune.shared.ui.player.PlayerAction
import moe.rukamori.archivetune.shared.ui.player.PlayerControlMotionEnvironment
import moe.rukamori.archivetune.shared.ui.player.PlayerIcon
import moe.rukamori.archivetune.shared.ui.player.PlayerInteractionState
import moe.rukamori.archivetune.shared.ui.player.PlayerProgress
import moe.rukamori.archivetune.shared.ui.player.PlayerRepeat
import moe.rukamori.archivetune.shared.ui.player.PlayerSeekSlider
import moe.rukamori.archivetune.shared.ui.player.PlayerSlots
import moe.rukamori.archivetune.shared.ui.player.PlayerState
import moe.rukamori.archivetune.shared.ui.player.PlayerSurfaceTransition
import moe.rukamori.archivetune.shared.ui.player.PlayerSymbol
import moe.rukamori.archivetune.shared.ui.player.PlayerTextSwap
import moe.rukamori.archivetune.shared.ui.player.PlayerTrackSwap
import moe.rukamori.archivetune.shared.ui.player.fades
import moe.rukamori.archivetune.shared.ui.player.moves
import moe.rukamori.archivetune.shared.ui.player.rememberPlayerMotionLevel
import moe.rukamori.archivetune.shared.ui.player.rememberPlayerProgress
import moe.rukamori.archivetune.shared.ui.player.rememberPlayerTrackMotion
import moe.rukamori.archivetune.shared.ui.player.tabularFigures
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
        // The cap has to be the outer constraint: a fillMaxWidth first would pin the sleeve to the column measure and
        // the cap would never apply. The reference player centres a 392 dp square in its 648 dp column.
        .widthIn(max = DesktopShellMetrics.expandedArtworkMax)
        .fillMaxWidth()
        .aspectRatio(1f)
        .clip(RoundedCornerShape(DesktopShellMetrics.contentCorner))
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier
            .fillMaxSize()
            .testTag("desktop-expanded-player"),
    ) {
        CompositionLocalProvider(
            LocalPlayerControlMotion provides rememberPlayerMotionLevel(!state.reducedMotion).let { PlayerControlMotionEnvironment(it.moves, state.repeat, it.fades) },
            LocalPlayerTrackMotion provides rememberPlayerTrackMotion(state.displayedOccurrenceId, state.occurrenceIds, rememberPlayerMotionLevel(!state.reducedMotion)),
        ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val twoColumns = maxHeight >= DesktopShellMetrics.expandedTwoColumnMinHeight
                val content: @Composable (Modifier) -> Unit = { columnModifier ->
                    if (twoColumns) {
                        // The reference layout is not a 50/50 split: the player keeps its own 648 dp measure and the lyrics
                        // take the rest of the row. The measure narrows only when the window cannot carry both columns.
                        BoxWithConstraints(
                            columnModifier.fillMaxSize().padding(DesktopShellMetrics.expandedPlayerPadding),
                        ) {
                            val playerColumn = desktopExpandedPlayerColumnWidth(maxWidth)
                            Row(
                                Modifier.fillMaxSize(),
                                horizontalArrangement = Arrangement.spacedBy(DesktopShellMetrics.expandedPlayerPadding),
                            ) {
                                Box(Modifier.width(playerColumn).fillMaxHeight().testTag("desktop-expanded-column")) {
                                    DesktopPlayerColumn(
                                        state, slots, artworkModifier, Modifier.fillMaxSize(),
                                        onPlayPause, onSeekTo, onAction, onVolumeChange,
                                    )
                                }
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .clip(RoundedCornerShape(DesktopShellMetrics.contentCorner))
                                        .background(MaterialTheme.colorScheme.surfaceContainer)
                                        .testTag("desktop-expanded-lyrics"),
                                ) { slots.lyrics(Modifier.fillMaxSize()) }
                            }
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
}

/**
 * Lifts the expanded player in over the shell and lowers it out again. A close or reopen mid-way reverses from where the
 * transition is; the last occurrence stays on screen while it leaves. Without motion it simply appears and disappears.
 */
@Composable
internal fun DesktopExpandedPlayerTransition(
    current: ContentOccurrence?,
    motion: Boolean,
    content: @Composable (ContentOccurrence) -> Unit,
) {
    PlayerSurfaceTransition(current, rememberPlayerMotionLevel(motion), content = content)
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
        // The reference player left-aligns its metadata to the column measure and centres only the sleeve and the
        // transport, so the title reads as the start of the column rather than as a caption under the artwork.
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            PlayerTrackSwap(state.displayedOccurrenceId, rememberPlayerMotionLevel(!state.reducedMotion), artworkModifier) { slots.artwork(it, Modifier.fillMaxSize()) }
        }
        PlayerTextSwap(state.title, !state.reducedMotion) {
            Text(
                it,
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("desktop-expanded-title"),
            )
        }
        if (state.artist.isNotBlank()) {
            PlayerTextSwap(state.artist, !state.reducedMotion) {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        DesktopExpandedSeek(state, onSeekTo)
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.xs, Alignment.CenterHorizontally),
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
    val draft = remember { PlayerInteractionState() }
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val progress = rememberPlayerProgress(state.playback, state.active, { draft.previewMs }, state.positionMs)
    LaunchedEffect(state.playback?.occurrenceId, state.playback?.playbackGeneration, state.playback?.discontinuityGeneration) {
        draft.reconcile(state.playback)
    }
    LaunchedEffect(source) {
        source.interactions.collect { if (it is androidx.compose.foundation.interaction.DragInteraction.Cancel) draft.cancelPreview() }
    }
    DisposableEffect(draft) { onDispose { draft.cancelPreview() } }
    Column(Modifier.fillMaxWidth()) {
        PlayerSeekSlider(progress, duration, state.canSeek && state.playback?.isSeekable == true,
            rememberPlayerMotionLevel(!state.reducedMotion).moves, source,
            { draft.preview(it, state) }, { draft.commit(state, onSeekTo) },
            Modifier.fillMaxWidth().testTag("desktop-expanded-seek"))
        DesktopExpandedTime(progress, duration)
    }
}

@Composable
private fun DesktopExpandedTime(progress: PlayerProgress, duration: Long) {
    val seconds by remember(progress) { derivedStateOf { progress.positionMs / 1000L } }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(formatTrackDuration(seconds * 1000L) ?: "0:00", style = MaterialTheme.typography.labelSmall.tabularFigures())
        Text(formatTrackDuration(duration) ?: "0:00", style = MaterialTheme.typography.labelSmall.tabularFigures(),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
