package moe.rukamori.archivetune.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.constants.SliderStyle
import moe.rukamori.archivetune.LocalAnimationScale
import moe.rukamori.archivetune.LocalAnimationsDisabled
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.shared.ui.player.PlayerMotionSpecs
import moe.rukamori.archivetune.shared.ui.player.PlayerPlayPauseGlyph

internal fun playerGlyphPlaying(iconRes: Int): Boolean? = when (iconRes) {
    R.drawable.player_play, R.drawable.play, R.drawable.play_applemusic, R.drawable.spatialflow_ic_play -> false
    R.drawable.player_pause, R.drawable.pause, R.drawable.pause_applemusic, R.drawable.spatialflow_ic_pause -> true
    else -> null
}

@Composable
fun PlayerTransportIcon(iconRes: Int, contentDescription: String?, modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current) {
    val playing = playerGlyphPlaying(iconRes)
    if (playing != null) PlayerPlayPauseGlyph(playing, tint, modifier,
        !LocalAnimationsDisabled.current && LocalAnimationScale.current > 0f, contentDescription)
    else Icon(painterResource(iconRes), contentDescription, modifier, tint)
}

/** Keeps the control mounted and reachable while its buffering indicator fades over it. */
@Composable
fun PlayerLoadingCrossfade(loading: Boolean, spinner: @Composable () -> Unit, content: @Composable () -> Unit) {
    val motion = !LocalAnimationsDisabled.current && LocalAnimationScale.current > 0f
    val opacity = animateFloatAsState(if (loading) 0f else 1f,
        if (motion) tween(PlayerMotionSpecs.FadeMillis) else snap(), label = "playerBufferingGlyph")
    Box(contentAlignment = Alignment.Center) {
        Box(Modifier.graphicsLayer { alpha = opacity.value }, contentAlignment = Alignment.Center) { content() }
        if (!motion) {
            if (loading) spinner()
        } else AnimatedVisibility(loading, enter = fadeIn(tween(PlayerMotionSpecs.FadeMillis)),
            exit = fadeOut(tween(PlayerMotionSpecs.FadeMillis)), label = "playerBuffering") { spinner() }
    }
}

/** Observes the press without consuming it; playback is still dispatched by the original clickable. */
fun Modifier.playerPressFeedback(): Modifier = composed {
    val motion = !LocalAnimationsDisabled.current && LocalAnimationScale.current > 0f
    var pressed by remember { mutableStateOf(false) }
    val scale = animateFloatAsState(if (pressed && motion) .92f else 1f,
        if (motion) PlayerMotionSpecs.response() else snap(), label = "playerButtonPress")
    this.graphicsLayer { scaleX = scale.value; scaleY = scale.value }
        .pointerInput(motion) {
            if (!motion) return@pointerInput
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                pressed = true
                try { waitForUpOrCancellation(pass = PointerEventPass.Initial) } finally { pressed = false }
            }
        }
}

@Composable
fun StyledPlaybackSlider(sliderStyle: SliderStyle, value: Float, valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit, onValueChangeFinished: () -> Unit, activeColor: Color, isPlaying: Boolean,
    modifier: Modifier = Modifier) {
    val source = remember { MutableInteractionSource() }
    val dragging by source.collectIsDraggedAsState()
    var scrubbing by remember { mutableStateOf(false) }
    val motion = !LocalAnimationsDisabled.current && LocalAnimationScale.current > 0f
    val previous = remember { floatArrayOf(value) }
    val discontinuity = kotlin.math.abs(previous[0] - value) > 2_000f
    previous[0] = value
    val progress = animateFloatAsState(value,
        if (scrubbing || dragging || !motion || !isPlaying || discontinuity) snap()
        else tween(100, easing = LinearEasing), label = "playerSeekProgress")
    LaunchedEffect(source) {
        source.interactions.collect { if (it is androidx.compose.foundation.interaction.DragInteraction.Cancel) scrubbing = false }
    }
    StyledPlaybackSliderContent(sliderStyle, progress.value, valueRange,
        { scrubbing = true; onValueChange(it) }, { scrubbing = false; onValueChangeFinished() },
        activeColor, isPlaying, modifier.pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                try { waitForUpOrCancellation(pass = PointerEventPass.Initial) } finally { scrubbing = false }
            }
        }, source)
}

@Composable
internal fun PlayerSeekThumb(source: MutableInteractionSource, color: Color) {
    val dragging by source.collectIsDraggedAsState()
    val motion = !LocalAnimationsDisabled.current && LocalAnimationScale.current > 0f
    val scale = animateFloatAsState(if (dragging) 1.4f else 0f,
        if (motion) PlayerMotionSpecs.response() else snap(), label = "playerSeekThumb")
    // A zero-size layout preserves the thumb-free styles' full-width track during dragging too.
    Box(Modifier.size(0.dp).drawBehind { drawCircle(color, radius = 8.dp.toPx() * scale.value.coerceAtLeast(0f)) })
}

@Composable
internal fun rememberPlayerPlayShape(playing: Boolean, base: androidx.compose.ui.graphics.Shape): androidx.compose.ui.graphics.Shape =
    moe.rukamori.archivetune.shared.ui.player.rememberPlayerButtonShape(playing, false, base,
        !LocalAnimationsDisabled.current && LocalAnimationScale.current > 0f)

@Composable
fun <T> PlayerTrackTextTransition(targetState: T, modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit) {
    val index = LocalPlayerConnection.current?.player?.currentMediaItemIndex ?: -1
    val previous = remember { intArrayOf(index, 1) }
    if (index != previous[0]) {
        previous[1] = if (index < previous[0]) -1 else 1
        previous[0] = index
    }
    moe.rukamori.archivetune.shared.ui.player.PlayerMetadataTransition(targetState,
        !LocalAnimationsDisabled.current && LocalAnimationScale.current > 0f, previous[1], modifier, content)
}
