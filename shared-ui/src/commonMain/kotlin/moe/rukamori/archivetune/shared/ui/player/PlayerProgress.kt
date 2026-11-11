package moe.rukamori.archivetune.shared.ui.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.domain.playback.PlaybackClock
import moe.rukamori.archivetune.shared.domain.playback.PlaybackSnapshot
import moe.rukamori.archivetune.shared.domain.playback.SystemMonotonicClock

/** The frame pulse is read by seek drawing and its own Slider, never by the player or its artwork. */
@Stable
internal class PlayerProgress internal constructor(
    private val position: () -> Long,
) {
    val positionMs: Long get() = position()
    fun fraction(duration: Long?): Float = if (duration != null && duration > 0L)
        (positionMs.toDouble() / duration).toFloat().coerceIn(0f, 1f) else 0f
}

@Composable
internal fun rememberPlayerProgress(
    playback: PlaybackSnapshot?,
    active: Boolean,
    preview: () -> Long? = { null },
    fallback: Long = 0L,
): PlayerProgress {
    val sample by rememberUpdatedState(playback)
    val draft by rememberUpdatedState(preview)
    val still by rememberUpdatedState(fallback)
    val now = remember { mutableLongStateOf(SystemMonotonicClock.nowNanos()) }
    LaunchedEffect(playback, active) {
        now.longValue = SystemMonotonicClock.nowNanos()
        if (playback == null || !active || !PlaybackClock.isInterpolating(playback)) return@LaunchedEffect
        do {
            withFrameNanos { now.longValue = SystemMonotonicClock.nowNanos() }
        } while (playback.durationMs == null || PlaybackClock.positionMs(playback, now.longValue) < playback.durationMs!!)
    }
    return remember {
        PlayerProgress { draft() ?: sample?.let { PlaybackClock.positionMs(it, now.longValue) } ?: still }
    }
}

@Composable
internal fun rememberPlayerProgress(c: PlayerRenderContext): PlayerProgress =
    rememberPlayerProgress(c.state.playback, c.state.active, { c.interaction.previewMs }, c.state.positionMs)

/** Only this small child reads progress each frame; time labels use derived whole seconds. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerSeekSlider(
    progress: PlayerProgress,
    duration: Long?,
    enabled: Boolean,
    motion: Boolean,
    source: MutableInteractionSource,
    onPreview: (Float) -> Unit,
    onCommit: () -> Unit,
    modifier: Modifier = Modifier,
    track: (@Composable () -> Unit)? = null,
) {
    val dragging by source.collectIsDraggedAsState()
    val scale = animateFloatAsState(if (dragging && enabled) PlayerMotionSpecs.DragThumbScale else 1f,
        if (motion) PlayerMotionSpecs.response() else snap(), label = "playerSeekThumb")
    Slider(
        value = progress.fraction(duration), onValueChange = onPreview, onValueChangeFinished = onCommit,
        enabled = enabled, interactionSource = source, modifier = modifier,
        thumb = {
            Box(Modifier.size(20.dp).graphicsLayer { scaleX = scale.value; scaleY = scale.value }
                .background(if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f), CircleShape))
        },
        track = { state -> if (track != null) track() else SliderDefaults.Track(state) },
    )
}
