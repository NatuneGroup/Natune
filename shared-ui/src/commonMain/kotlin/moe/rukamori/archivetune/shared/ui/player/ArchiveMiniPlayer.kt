package moe.rukamori.archivetune.shared.ui.player

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateTo
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.*
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.ui.host.LocalAndroidPhonePresentation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.LayoutDirection
import kotlin.time.TimeSource
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlin.math.abs
import kotlin.math.exp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
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
    preferences: PreferenceSnapshot = PreferenceSnapshot.EMPTY,
    playOrder: List<String> = listOf(current.occurrenceId),
    active: Boolean = true,
) {
    val phone = LocalAndroidPhonePresentation.current
    val playing = playback?.playWhenReady == true && playback.isEnded != true
    val matching = nativeState.matches(playback)
    val motionLevel = rememberPlayerMotionLevel(motion)
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
        LocalPlayerControlMotion provides PlayerControlMotionEnvironment(motionLevel.moves, nativeState.repeat, motionLevel.fades),
        LocalPlayerTrackMotion provides rememberPlayerTrackMotion(current.occurrenceId, playOrder, motionLevel),
    ) {
        // Space stays the player's key on each clickable expansion surface, not on this container: a focused bar would
        // otherwise click itself and re-open the sheet after Escape returns focus here, while an ancestor handler would
        // take Space from every focused control inside the strip. The claim is per surface and leaves text fields alone.
        BoxWithConstraints(modifier.fillMaxWidth()) {
            val barWidth = maxWidth
            val compact = barWidth < 300.dp || (phone && preferences[CompactMiniPlayerKey, false])
            val direction = LocalLayoutDirection.current
            val haptic = LocalHapticFeedback.current
            // Not keyed on the track: after a swipe changes it, the bar springs back while the new title fades in.
            val dragOffset = remember { mutableFloatStateOf(0f) }
            val dragReturn = remember { arrayOfNulls<Job>(1) }
            val dragScope = rememberCoroutineScope()
            val dragSprings by rememberUpdatedState(motionLevel.moves)
            // The bar springs back after a swipe the way the Android mini player does.
            val settleDrag = {
                dragReturn[0]?.cancel()
                if (!dragSprings) dragOffset.floatValue = 0f
                else dragReturn[0] = dragScope.launch {
                    AnimationState(dragOffset.floatValue).animateTo(0f, PlayerMotionSpecs.travel()) {
                        dragOffset.floatValue = value
                    }
                }
            }
            LaunchedEffect(playback?.occurrenceId, motionLevel) { settleDrag() }
            val sensitivity = preferences[SwipeSensitivityKey, .73f].takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: .73f
            val swipe = if (phone && preferences[SwipeThumbnailKey, true]) Modifier.pointerInput(playback?.occurrenceId, playback?.playbackGeneration, sensitivity, nativeState.canNext, nativeState.canPrevious) {
                var started = TimeSource.Monotonic.markNow()
                var distance = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dragReturn[0]?.cancel(); started = TimeSource.Monotonic.markNow(); distance = 0f },
                    onDragCancel = { settleDrag() },
                    onHorizontalDrag = { change, amount ->
                        val logical = if (direction == LayoutDirection.Rtl) -amount else amount
                        if ((logical < 0 && nativeState.canNext) || (logical > 0 && nativeState.canPrevious)) {
                            change.consume(); distance += abs(logical)
                            dragOffset.floatValue += logical
                        }
                    },
                    onDragEnd = {
                        val offset = dragOffset.floatValue
                        val velocity = distance / started.elapsedNow().inWholeMilliseconds.coerceAtLeast(1L)
                        val autoThreshold = (600.0 / (1.0 + exp(-(-11.44748 * sensitivity + 9.04945)))).toFloat()
                        val changeSong = (abs(offset) > 50f && velocity > sensitivity * -8.25f + 8.5f) || abs(offset) > autoThreshold
                        if (changeSong && matching) {
                            if (preferences[EnableHapticFeedbackKey, true]) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            if (offset > 0 && nativeState.canPrevious) onAction(PlayerAction.PREVIOUS)
                            else if (offset < 0 && nativeState.canNext) onAction(PlayerAction.NEXT)
                        }
                        settleDrag()
                    },
                )
            } else Modifier
            // The desktop host draws its own player bar in the shell, so this bar is only ever composed by a phone
            // host's bottom chrome; a narrow window keeps its compact single row.
            if (phone || !isWideTransportBar(barWidth)) {
                Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = if (phone) { if (compact) 64.dp else 70.dp } else 56.dp)
                        .then(swipe).graphicsLayer {
                            translationX = if (direction == LayoutDirection.Rtl) -dragOffset.floatValue else dragOffset.floatValue
                        }
                        .spaceTogglesPlayback(onPlayPause)
                        .clickable(onClick = onExpand)
                        .padding(horizontal = if (compact) 6.dp else 12.dp, vertical = 4.dp)
                        .testTag("compact-mini-player"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp),
                ) {
                    PlayerTrackSwap(current, motionLevel, Modifier.size(if (phone) { if (compact) 44.dp else 52.dp } else if (compact) 36.dp else 44.dp).clip(RoundedCornerShape(if (phone) 10.dp else 8.dp))) {
                        artwork(it.item, Modifier.fillMaxSize())
                    }
                    Column(Modifier.weight(1f)) {
                        PlayerTextSwap(current.item.title, motionLevel.fades) {
                            MarqueeText(it, style = MaterialTheme.typography.bodyMedium)
                        }
                        if (current.item.subtitle.isNotBlank()) {
                            PlayerTextSwap(current.item.subtitle, motionLevel.fades) {
                                MarqueeText(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Box(contentAlignment = Alignment.Center) {
                        PlayerIcon(
                            symbol = if (playback?.isEnded == true) PlayerSymbol.REPLAY
                                else if (playing) PlayerSymbol.PAUSE else PlayerSymbol.PLAY,
                            label = stringResource(if (playback?.isEnded == true) Res.string.m5_replay
                                else if (playing) Res.string.m5_pause else Res.string.m5_play),
                            onClick = onPlayPause,
                            size = 48.dp, iconSize = 24.dp,
                            container = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            shape = CircleShape,
                            modifier = Modifier.testTag("mini-player-play-pause"),
                        )
                        PlayerBufferingFade(playback?.isBuffering == true, motionLevel.fades) {
                            val spinner = Modifier.size(34.dp)
                            if (motionLevel.moves) CircularProgressIndicator(spinner, strokeWidth = 2.dp)
                            else CircularProgressIndicator(progress = { .7f }, modifier = spinner, strokeWidth = 2.dp)
                        }
                    }
                    if (!compact) PlayerIcon(
                        symbol = PlayerSymbol.NEXT,
                        label = stringResource(Res.string.m5_next),
                        onClick = { onAction(PlayerAction.NEXT) },
                        enabled = matching && nativeState.canNext,
                        size = 48.dp,
                        iconSize = 24.dp,
                    )
                }
                MiniPlayerProgressLine(playback, active, Modifier.fillMaxWidth())
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
                                .spaceTogglesPlayback(onPlayPause)
                                .clickable(onClick = onExpand)
                                .testTag("wide-mini-player-title"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            PlayerTrackSwap(current, motionLevel, Modifier.size(48.dp).clip(RoundedCornerShape(10.dp))) {
                                artwork(it.item, Modifier.fillMaxSize())
                            }
                            Column(Modifier.weight(1f)) {
                                PlayerTextSwap(current.item.title, motionLevel.fades) {
                                    MarqueeText(it, style = MaterialTheme.typography.titleSmall)
                                }
                                if (current.item.subtitle.isNotBlank()) {
                                    PlayerTextSwap(current.item.subtitle, motionLevel.fades) {
                                        MarqueeText(
                                            it,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
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
                            PlayerIcon(if (playing) PlayerSymbol.PAUSE else PlayerSymbol.PLAY,
                                stringResource(if (playing) Res.string.integration_pause else Res.string.integration_play),
                                onPlayPause, enabled = playback != null, size = 44.dp, large = true,
                                modifier = Modifier.testTag("mini-player-play-pause"))
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
                        MiniPlayerSeek(playback, seekState, seekInteraction, seekInteractionSource, motionLevel.moves, active, onSeekTo)

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

/**
 * The position line under the compact bar. A phone bar has no room for a seek slider, so the row above it is paired
 * with a two-point line, as the reference phone bar does. It is drawn only for a seekable track with a known duration:
 * a live stream, a preview or an unread native snapshot shows no position this UI did not observe.
 */
@Composable
private fun MiniPlayerProgressLine(playback: PlaybackSnapshot?, active: Boolean, modifier: Modifier = Modifier) {
    val snapshot = playback ?: return
    val duration = snapshot.durationMs ?: return
    if (duration <= 0L || !snapshot.isSeekable) return
    val progress = rememberPlayerProgress(snapshot, active = active)
    LinearProgressIndicator(
        progress = { progress.fraction(duration) },
        modifier = modifier.height(2.dp),
        color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        strokeCap = StrokeCap.Butt,
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
}

@Composable
private fun MiniPlayerSeek(playback: PlaybackSnapshot?, state: PlayerState, draft: PlayerInteractionState,
    source: MutableInteractionSource, motion: Boolean, active: Boolean, onSeekTo: (Long) -> Unit) {
    val duration = playback?.durationMs?.takeIf { it > 0L }
    val progress = rememberPlayerProgress(playback, active, { draft.previewMs })
    Row(Modifier.fillMaxWidth().testTag("mini-player-seek"), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MiniPlayerTime(progress)
        PlayerSeekSlider(progress, duration, duration != null && state.canSeek && playback?.isSeekable == true,
            motion, source, { draft.preview(it, state) }, { draft.commit(state, onSeekTo) },
            Modifier.weight(1f).height(24.dp))
        Text(if (duration != null) playerTime(duration) else "—", style = MaterialTheme.typography.labelSmall.tabularFigures())
    }
}

@Composable
private fun MiniPlayerTime(progress: PlayerProgress) {
    val seconds by remember(progress) { derivedStateOf { progress.positionMs / 1000L } }
    Text(playerTime(seconds * 1000L), style = MaterialTheme.typography.labelSmall.tabularFigures())
}
