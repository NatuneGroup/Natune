package moe.rukamori.archivetune.ui.player

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.LocalAnimationScale
import moe.rukamori.archivetune.LocalAnimationsDisabled
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.shared.ui.player.PlayerMotionSpecs
import moe.rukamori.archivetune.shared.ui.player.PlayerTransportCrossfade
import moe.rukamori.archivetune.scaleAwareSpring

/** The player controls that share one motion vocabulary across every player style and the mini player. */
enum class PlayerControlKind { PlayPause, Next, Previous, Shuffle, Repeat, Like }

/** What a control does when its state changes or it is tapped. Pure so the mapping can be unit tested. */
enum class PlayerControlGesture { Spin, Pop, Dip, Swap, NudgeForward, NudgeBackward, Squash }

/**
 * Maps a state transition of [kind] to the gesture it plays, or null when nothing should move.
 * [before] and [after] are the control's state: a Boolean for play/pause, shuffle and like, a
 * media3 repeat mode Int for repeat. Next and previous have no state and answer to taps only.
 */
fun playerControlGesture(
    kind: PlayerControlKind,
    before: Any?,
    after: Any?,
): PlayerControlGesture? {
    if (before == after) return null
    return when (kind) {
        PlayerControlKind.Repeat ->
            when (after) {
                Player.REPEAT_MODE_ALL -> PlayerControlGesture.Spin
                Player.REPEAT_MODE_ONE -> PlayerControlGesture.Pop
                Player.REPEAT_MODE_OFF -> PlayerControlGesture.Dip
                else -> null
            }
        PlayerControlKind.Shuffle -> if (after == true) PlayerControlGesture.Swap else PlayerControlGesture.Dip
        PlayerControlKind.PlayPause -> PlayerControlGesture.Squash
        PlayerControlKind.Like -> if (after == true) PlayerControlGesture.Pop else PlayerControlGesture.Dip
        PlayerControlKind.Next, PlayerControlKind.Previous -> null
    }
}

/** The gesture a tap plays, for controls whose meaning is the tap itself. */
fun playerControlTapGesture(kind: PlayerControlKind): PlayerControlGesture? =
    when (kind) {
        PlayerControlKind.Next -> PlayerControlGesture.NudgeForward
        PlayerControlKind.Previous -> PlayerControlGesture.NudgeBackward
        else -> null
    }

/**
 * One control's motion. [modifier] goes on the icon, never on the button container, so a rotation
 * turns the glyph and leaves a rounded or tonal container where it is. Every gesture is a transform
 * of whatever painter the style draws, which keeps it working with any icon.
 */
@Stable
class PlayerControlMotion internal constructor(
    private val kind: PlayerControlKind,
    private val scope: CoroutineScope,
    private val enabled: () -> Boolean,
    private val springScale: () -> Float,
) {
    internal val rotation = Animatable(0f)
    internal val scale = Animatable(1f)
    internal val shift = Animatable(0f)
    private var job: Job? = null

    val modifier: Modifier =
        Modifier.graphicsLayer {
            rotationZ = if (enabled()) rotation.value else 0f
            scaleX = if (enabled()) scale.value else 1f
            scaleY = if (enabled()) scale.value else 1f
            translationX = if (enabled()) shift.value * size.width else 0f
        }

    /** Plays the tap gesture; call it from the button's onClick before acting. */
    fun tap() {
        playerControlTapGesture(kind)?.let(::play)
    }

    /** Wraps [onClick] so the tap gesture plays with it. */
    fun onClick(onClick: () -> Unit): () -> Unit =
        {
            tap()
            onClick()
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

    private suspend fun animate(gesture: PlayerControlGesture) {
        coroutineScope {
            val s = springScale()
            val settle = scaleAwareSpring<Float>(s, PlayerMotionSpecs.SettleDamping, PlayerMotionSpecs.SettleStiffness)
            val quick = scaleAwareSpring<Float>(s, PlayerMotionSpecs.ResponseDamping, PlayerMotionSpecs.ResponseStiffness)
            // A gesture cancelled half way leaves its transform behind; settle whatever this one does not drive.
            if (gesture != PlayerControlGesture.Spin && rotation.value != 0f) {
                launch {
                    rotation.animateTo(if (rotation.value > 180f) 360f else 0f, settle)
                    rotation.snapTo(0f)
                }
            }
            if (gesture !in ShiftGestures && shift.value != 0f) launch { shift.animateTo(0f, settle) }
            if (gesture in ShiftGestures && gesture != PlayerControlGesture.Swap && scale.value != 1f) {
                launch { scale.animateTo(1f, settle) }
            }
            when (gesture) {
                PlayerControlGesture.Spin -> {
                    launch {
                        rotation.snapTo(0f)
                        rotation.animateTo(
                            360f,
                            scaleAwareSpring(s, PlayerMotionSpecs.BounceDamping, PlayerMotionSpecs.BounceStiffness),
                        )
                        rotation.snapTo(0f)
                    }
                    launch {
                        scale.animateTo(1.12f, quick)
                        scale.animateTo(1f, settle)
                    }
                }
                PlayerControlGesture.Pop -> {
                    scale.animateTo(1.28f, quick)
                    scale.animateTo(1f, settle)
                }
                PlayerControlGesture.Dip -> {
                    scale.animateTo(0.82f, quick)
                    scale.animateTo(1f, settle)
                }
                PlayerControlGesture.Swap -> {
                    launch {
                        scale.animateTo(1.16f, quick)
                        scale.animateTo(1f, settle)
                    }
                    launch {
                        shift.animateTo(0.14f, quick)
                        shift.animateTo(-0.14f, quick)
                        shift.animateTo(0f, settle)
                    }
                }
                PlayerControlGesture.NudgeForward, PlayerControlGesture.NudgeBackward -> {
                    val target = if (gesture == PlayerControlGesture.NudgeForward) 0.2f else -0.2f
                    launch { scale.animateTo(.9f, quick); scale.animateTo(1f, settle) }
                    shift.animateTo(target, quick)
                    shift.animateTo(0f, settle)
                }
                PlayerControlGesture.Squash -> {
                    scale.snapTo(0.78f)
                    scale.animateTo(1f, settle)
                }
            }
        }
    }
}

private val ShiftGestures =
    setOf(PlayerControlGesture.Swap, PlayerControlGesture.NudgeForward, PlayerControlGesture.NudgeBackward)

/**
 * Remembers the motion for one control and plays the matching gesture whenever [state] changes,
 * whether the change came from this button, another surface or a media session. The first
 * composition never animates. Honours the in-app "disable animations" switch and the system
 * animator duration scale (0 means off, below 1 settles without overshoot).
 */
@Composable
fun rememberPlayerControlMotion(
    kind: PlayerControlKind,
    state: Any? = null,
): PlayerControlMotion {
    val disabled by rememberUpdatedState(LocalAnimationsDisabled.current)
    val animationScale by rememberUpdatedState(LocalAnimationScale.current)
    val scope = rememberCoroutineScope()
    val motion =
        remember(kind, scope) {
            PlayerControlMotion(
                kind = kind,
                scope = scope,
                enabled = { !disabled && animationScale > 0f },
                springScale = { animationScale },
            )
        }
    val last = remember(motion) { arrayOf(state) }
    LaunchedEffect(motion, state, disabled, animationScale) {
        val before = last[0]
        last[0] = state
        if (disabled || animationScale <= 0f) motion.reset() else playerControlGesture(kind, before, state)?.let(motion::play)
    }
    return motion
}

/** Convenience for state-only controls (play/pause, shuffle, repeat, like). */
@Composable
fun Modifier.playerControlMotion(
    kind: PlayerControlKind,
    state: Any?,
): Modifier = this.then(rememberPlayerControlMotion(kind, state).modifier)

/**
 * Crossfades between two glyphs of one control (play and pause, repeat and repeat-one) when motion
 * is on, and swaps them instantly when it is off.
 */
@Composable
fun <T> PlayerControlCrossfade(
    targetState: T,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    // The play/pause node stays mounted across intent changes; only replay gets a separate fade layer.
    val transport = targetState as? Int
    val replay = transport == R.drawable.player_replay
    val morph = transport?.let(::playerGlyphPlaying) != null
    if ((morph || replay) && !LocalAnimationsDisabled.current && LocalAnimationScale.current > 0f) {
        PlayerTransportCrossfade(targetState, replay, modifier, content)
        return
    }
    if (morph || LocalAnimationsDisabled.current || LocalAnimationScale.current <= 0f) {
        Box(modifier, contentAlignment = Alignment.Center) { content(targetState) }
    } else {
        Crossfade(
            targetState = targetState,
            modifier = modifier,
            animationSpec = tween(durationMillis = PlayerMotionSpecs.FadeMillis),
            label = "playerControlGlyph",
        ) {
            Box(contentAlignment = Alignment.Center) { content(it) }
        }
    }
}

/**
 * The play, pause or replay glyph of a transport row: crossfades between the three and plays the
 * squash of [motion]. [modifier] places the glyph; [iconSize] sizes the icon inside it.
 */
@Composable
fun PlayerPlayPauseIcon(
    ended: Boolean,
    playing: Boolean,
    motion: PlayerControlMotion,
    iconSize: Dp,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    contentDescription: String? = null,
) {
    val glyph =
        when {
            ended -> R.drawable.player_replay
            playing -> R.drawable.player_pause
            else -> R.drawable.player_play
        }
    PlayerControlCrossfade(glyph, modifier) {
        PlayerTransportIcon(
            iconRes = it,
            contentDescription = contentDescription,
            modifier = Modifier.size(iconSize).then(motion.modifier),
            tint = tint,
        )
    }
}
