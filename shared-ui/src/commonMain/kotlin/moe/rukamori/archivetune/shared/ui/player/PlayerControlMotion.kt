package moe.rukamori.archivetune.shared.ui.player

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** The player controls that share one motion vocabulary across every player style and the mini player. */
enum class PlayerControlKind { PlayPause, Next, Previous, Shuffle, Repeat, Like, Queue, Lyrics }

/** What a control does when its state changes or it is tapped. Pure so the mapping can be unit tested. */
enum class PlayerControlGesture { Spin, Pop, Dip, Swap, NudgeForward, NudgeBackward, Squash }

/**
 * Maps a state transition of [kind] to the gesture it plays, or null when nothing should move.
 * [before] and [after] are the control's state: a [PlayerRepeat] for repeat, a Boolean for shuffle
 * and like, and any value that changes with the glyph for play/pause. Next and previous answer to
 * taps only.
 */
fun playerControlGesture(kind: PlayerControlKind, before: Any?, after: Any?): PlayerControlGesture? {
    if (before == after) return null
    return when (kind) {
        PlayerControlKind.Repeat -> when (after) {
            PlayerRepeat.ALL -> PlayerControlGesture.Spin
            PlayerRepeat.ONE -> PlayerControlGesture.Pop
            PlayerRepeat.OFF -> PlayerControlGesture.Dip
            else -> null
        }
        PlayerControlKind.Shuffle -> if (after == true) PlayerControlGesture.Swap else PlayerControlGesture.Dip
        PlayerControlKind.PlayPause -> PlayerControlGesture.Squash
        PlayerControlKind.Like, PlayerControlKind.Queue, PlayerControlKind.Lyrics -> if (after == true) PlayerControlGesture.Pop else PlayerControlGesture.Dip
        PlayerControlKind.Next, PlayerControlKind.Previous -> null
    }
}

/** The gesture a tap plays, for controls whose meaning is the tap itself. */
fun playerControlTapGesture(kind: PlayerControlKind): PlayerControlGesture? = when (kind) {
    PlayerControlKind.Next -> PlayerControlGesture.NudgeForward
    PlayerControlKind.Previous -> PlayerControlGesture.NudgeBackward
    else -> null
}

/**
 * Whether player controls may move, and the repeat mode the repeat glyph stands for. Styles pass
 * only "selected" to the repeat button, so repeat-one versus repeat-all comes from here.
 */
internal data class PlayerControlMotionEnvironment(
    val active: Boolean,
    val repeat: PlayerRepeat,
    /** Glyph crossfades still run when the platform asks for reduced motion and [active] is false. */
    val fades: Boolean = active,
)

/** Defaults to still, so a control drawn outside a player never animates. */
internal val LocalPlayerControlMotion = compositionLocalOf { PlayerControlMotionEnvironment(false, PlayerRepeat.OFF) }

/**
 * One control's motion. [modifier] goes on the glyph, never on the button container, so a rotation
 * turns the icon and leaves a rounded or tonal container where it is.
 */
@Stable
class PlayerControlMotion internal constructor(
    private val kind: PlayerControlKind,
    private val scope: CoroutineScope,
    private val enabled: () -> Boolean,
) {
    internal val rotation = Animatable(0f)
    internal val scale = Animatable(1f)
    internal val shift = Animatable(0f)
    private var job: Job? = null

    val modifier: Modifier = Modifier.graphicsLayer {
        rotationZ = if (enabled()) rotation.value else 0f
        scaleX = if (enabled()) scale.value else 1f
        scaleY = if (enabled()) scale.value else 1f
        translationX = if (enabled()) shift.value * size.width else 0f
    }

    fun tap() {
        playerControlTapGesture(kind)?.let(::play)
    }

    internal fun play(gesture: PlayerControlGesture) {
        if (!enabled()) return
        job?.cancel()
        job = scope.launch { animate(gesture) }
    }

    internal suspend fun reset() {
        job?.cancel()
        rotation.snapTo(0f)
        scale.snapTo(1f)
        shift.snapTo(0f)
    }

    private suspend fun animate(gesture: PlayerControlGesture): Unit = coroutineScope {
        val settle = PlayerMotionSpecs.settle<Float>()
        val quick = PlayerMotionSpecs.response<Float>()
        // A gesture cancelled half way leaves its transform behind; settle whatever this one does not drive.
        if (gesture != PlayerControlGesture.Spin && rotation.value != 0f) launch {
            rotation.animateTo(if (rotation.value > 180f) 360f else 0f, settle)
            rotation.snapTo(0f)
        }
        if (gesture !in ShiftGestures && shift.value != 0f) launch { shift.animateTo(0f, settle) }
        if (gesture in ShiftGestures && gesture != PlayerControlGesture.Swap && scale.value != 1f) {
            launch { scale.animateTo(1f, settle) }
        }
        when (gesture) {
            PlayerControlGesture.Spin -> {
                launch {
                    rotation.snapTo(0f)
                    rotation.animateTo(360f, PlayerMotionSpecs.settle())
                    rotation.snapTo(0f)
                }
                launch { scale.animateTo(1.12f, quick); scale.animateTo(1f, settle) }
            }
            PlayerControlGesture.Pop -> { scale.animateTo(1.28f, quick); scale.animateTo(1f, settle) }
            PlayerControlGesture.Dip -> { scale.animateTo(.82f, quick); scale.animateTo(1f, settle) }
            PlayerControlGesture.Swap -> {
                launch { scale.animateTo(1.16f, quick); scale.animateTo(1f, settle) }
                launch { shift.animateTo(.14f, quick); shift.animateTo(-.14f, quick); shift.animateTo(0f, settle) }
            }
            PlayerControlGesture.NudgeForward, PlayerControlGesture.NudgeBackward -> {
                shift.animateTo(if (gesture == PlayerControlGesture.NudgeForward) .2f else -.2f, quick)
                shift.animateTo(0f, settle)
            }
            PlayerControlGesture.Squash -> { scale.snapTo(.78f); scale.animateTo(1f, settle) }
        }
    }
}

private val ShiftGestures =
    setOf(PlayerControlGesture.Swap, PlayerControlGesture.NudgeForward, PlayerControlGesture.NudgeBackward)

/**
 * Remembers the motion for one control and plays the matching gesture whenever [state] changes,
 * whether the change came from this button, another surface or the media session. The first
 * composition never animates, and nothing moves while [active] is false.
 */
@Composable
fun rememberPlayerControlMotion(kind: PlayerControlKind, state: Any?, active: Boolean): PlayerControlMotion {
    val enabled by rememberUpdatedState(active)
    val scope = rememberCoroutineScope()
    val motion = remember(kind, scope) { PlayerControlMotion(kind, scope) { enabled } }
    val last = remember(motion) { arrayOf(state) }
    LaunchedEffect(motion, state, active) {
        val before = last[0]
        last[0] = state
        if (!active) motion.reset() else playerControlGesture(kind, before, state)?.let(motion::play)
    }
    return motion
}

/** Crossfades between two glyphs of one control when [active], and swaps them instantly otherwise. */
@Composable
fun <T> PlayerControlCrossfade(
    targetState: T,
    active: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    if (!active) Box(modifier, contentAlignment = Alignment.Center) { content(targetState) }
    else Crossfade(targetState, modifier, animationSpec = tween(PlayerMotionSpecs.FadeMillis), label = "playerControlGlyph") {
        Box(contentAlignment = Alignment.Center) { content(it) }
    }
}
