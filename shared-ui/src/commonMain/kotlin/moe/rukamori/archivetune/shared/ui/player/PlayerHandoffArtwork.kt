package moe.rukamori.archivetune.shared.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay
import moe.rukamori.archivetune.shared.domain.playback.PlaybackClock
import moe.rukamori.archivetune.shared.domain.playback.SystemMonotonicClock

/**
 * The blend the player artwork shows: [handoff], or the one that just committed, and how far it has run. A committed
 * blend stays at full progress briefly after the song changes, so a look that animates its own song change starts
 * from the incoming artwork instead of flashing the outgoing one back.
 */
@Stable
internal class PlayerHandoffDisplay(val handoff: PlayerHandoff?, val progress: () -> Float)

private class HandoffMemory {
    var last: PlayerHandoff? = null
}

/**
 * Follows [PlayerState.handoff] against the playback clock, the way Metrolist's artwork crossfade follows its audio
 * crossfade. Nothing runs before the blend starts, while the surface is hidden, or with reduced motion; a reduced-motion
 * player keeps the outgoing artwork and switches when the song does.
 */
@Composable
internal fun rememberPlayerHandoff(state: PlayerState, motion: Boolean): PlayerHandoffDisplay {
    val memory = remember { HandoffMemory() }
    val expired = remember { mutableStateOf<PlayerHandoff?>(null) }
    val occurrenceId = state.playback?.occurrenceId
    val armed = state.handoff?.takeIf { it.overlapMs > 0L && it.fromOccurrenceId == occurrenceId }
    val committed = memory.last?.takeIf { armed == null && it.toOccurrenceId == occurrenceId && it != expired.value }
    memory.last = armed ?: committed
    val progress = remember { mutableFloatStateOf(0f) }
    val playback = state.playback
    val animate = motion && state.active
    LaunchedEffect(armed, committed, playback, animate) {
        if (committed != null) {
            progress.floatValue = 1f
            // A pager keeps the previous song's page; it should get its own artwork back once the look has settled.
            delay(COMMITTED_HOLD_MS)
            expired.value = committed
            return@LaunchedEffect
        }
        if (armed == null || playback == null || !animate) {
            progress.floatValue = 0f
            return@LaunchedEffect
        }
        fun positionMs() = PlaybackClock.positionMs(playback, SystemMonotonicClock.nowNanos())
        progress.floatValue = armed.progressAt(positionMs())
        if (!PlaybackClock.isInterpolating(playback)) return@LaunchedEffect
        val leadMs = armed.fadeStartPositionMs - positionMs()
        if (leadMs > 0L) delay((leadMs / playback.playbackSpeed.coerceAtLeast(MIN_SPEED)).toLong())
        while (progress.floatValue < 1f) {
            withFrameNanos { progress.floatValue = armed.progressAt(positionMs()) }
        }
    }
    val read = remember(progress) { { progress.floatValue } }
    return PlayerHandoffDisplay(armed ?: committed, read)
}

/**
 * Every artwork and canvas slot of the outgoing occurrence shows the incoming artwork over it while a blend runs, so
 * each look, the sleeve, the pager and the blurred background follow one progress without a per-look change. The
 * wrapper is always present, so arming a blend never restarts the outgoing canvas or reloads its artwork.
 */
internal fun PlayerSlots.withHandoff(display: PlayerHandoffDisplay): PlayerSlots {
    val base = this
    fun incomingFor(id: String?): String? = display.handoff?.takeIf { id != null && it.fromOccurrenceId == id }?.toOccurrenceId
    return PlayerSlots(
        artwork = { id, modifier ->
            HandoffBlend(modifier, incomingFor(id), display.progress, { base.artwork(id, it) }, { to, m -> base.artwork(to, m) })
        },
        canvas = { id, active, modifier ->
            HandoffBlend(modifier, incomingFor(id), display.progress, { base.canvas(id, active, it) }, { to, m -> base.artwork(to, m) })
        },
        customBackground = base.customBackground,
        lyrics = base.lyrics,
        compactLyrics = base.compactLyrics,
        queue = base.queue,
    )
}

/** Alpha follows the progress in the layer, so a running blend redraws without recomposing either slot. */
@Composable
private fun HandoffBlend(
    modifier: Modifier,
    incomingId: String?,
    progress: () -> Float,
    outgoing: @Composable (Modifier) -> Unit,
    incoming: @Composable (String, Modifier) -> Unit,
) {
    Box(modifier, propagateMinConstraints = true) {
        outgoing(Modifier.graphicsLayer { alpha = if (incomingId == null) 1f else 1f - progress() })
        if (incomingId != null) incoming(incomingId, Modifier.matchParentSize().graphicsLayer { alpha = progress() })
    }
}

private const val COMMITTED_HOLD_MS = 1_200L
private const val MIN_SPEED = 0.1f
