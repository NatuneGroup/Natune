package moe.rukamori.archivetune.shared.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset

/** How much the player may move: everything, crossfades only, or nothing at all. */
enum class PlayerMotionLevel { Full, Fade, None }

/**
 * Folds the reduced-motion sources into one level. [animationsAllowed] is false for the in-app "disable animations"
 * switch or a hidden surface and stops every animation; the platform's reduce-motion setting keeps crossfades and drops
 * travel, scale and overshoot.
 */
fun playerMotionLevel(animationsAllowed: Boolean, systemReducedMotion: Boolean): PlayerMotionLevel = when {
    !animationsAllowed -> PlayerMotionLevel.None
    systemReducedMotion -> PlayerMotionLevel.Fade
    else -> PlayerMotionLevel.Full
}

val PlayerMotionLevel.moves: Boolean get() = this == PlayerMotionLevel.Full
val PlayerMotionLevel.fades: Boolean get() = this != PlayerMotionLevel.None

/** The platform's reduce-motion accessibility setting. Hosts that have none leave it false. */
internal val LocalSystemReducedMotion = staticCompositionLocalOf { false }

/** Same duration as the play/pause glyph crossfade, so a spinner and the glyph under it change together. */
object PlayerMotionSpecs {
    const val FadeMillis = 160
    const val ResponseDamping = 1f
    const val ResponseStiffness = 900f
    const val SettleDamping = .78f
    const val SettleStiffness = 500f
    const val TravelDamping = 1f
    const val TravelStiffness = Spring.StiffnessMediumLow
    const val BounceDamping = Spring.DampingRatioLowBouncy
    const val BounceStiffness = Spring.StiffnessLow
    const val PressScale = .92f
    const val DragThumbScale = 1.4f
    fun <T> response(): FiniteAnimationSpec<T> = spring(dampingRatio = ResponseDamping, stiffness = ResponseStiffness)
    fun <T> settle(): FiniteAnimationSpec<T> = spring(dampingRatio = SettleDamping, stiffness = SettleStiffness)
    fun <T> travel(): FiniteAnimationSpec<T> = spring(dampingRatio = TravelDamping, stiffness = TravelStiffness)
    fun <T> bounce(): FiniteAnimationSpec<T> = spring(dampingRatio = BounceDamping, stiffness = BounceStiffness)
}

@Composable
internal fun rememberPlayerMotionLevel(allowed: Boolean): PlayerMotionLevel {
    val scale = rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
    return playerMotionLevel(allowed && scale > 0f, LocalSystemReducedMotion.current)
}

internal data class PlayerTrackMotion(val level: PlayerMotionLevel, val direction: Int)
internal val LocalPlayerTrackMotion = staticCompositionLocalOf { PlayerTrackMotion(PlayerMotionLevel.None, 1) }

internal class PlayerTrackDirection {
    private var previous: String? = null
    private var previousOrder: List<String> = emptyList()
    var direction: Int = 1
        private set

    fun update(current: String?, order: List<String>): Int {
        if (current != previous && previous != null && current != null) {
            val before = order.indexOf(previous).takeIf { it >= 0 } ?: previousOrder.indexOf(previous)
            val after = order.indexOf(current)
            direction = if (before >= 0 && after >= 0 && after < before) -1 else 1
        }
        previous = current
        previousOrder = order
        return direction
    }
}

@Composable
internal fun rememberPlayerTrackMotion(current: String?, order: List<String>, level: PlayerMotionLevel): PlayerTrackMotion {
    val memory = remember { PlayerTrackDirection() }
    return PlayerTrackMotion(level, memory.update(current, order))
}

/** Two halves of a triangle become two pause bars; geometry changes only in the draw phase. */
@Composable
fun PlayerPlayPauseGlyph(playing: Boolean, color: Color, modifier: Modifier = Modifier, motion: Boolean = true,
    contentDescription: String? = null) {
    val morph = animateFloatAsState(if (playing) 1f else 0f,
        if (motion) PlayerMotionSpecs.response() else snap(), label = "playerPlayPauseMorph")
    Canvas(modifier.semantics { if (contentDescription != null) this.contentDescription = contentDescription }) {
        drawPlayerPlayPauseMorph(morph.value, color)
    }
}

internal fun DrawScope.drawPlayerPlayPauseMorph(progress: Float, color: Color) {
    val p = progress.coerceIn(0f, 1f)
    val path = Path()
    for (half in 0..1) {
        for (point in 0..3) {
            val i = (half * 4 + point) * 2
            val x = (PlayPoints[i] + (PausePoints[i] - PlayPoints[i]) * p) * size.width / 24f
            val y = (PlayPoints[i + 1] + (PausePoints[i + 1] - PlayPoints[i + 1]) * p) * size.height / 24f
            if (point == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
    }
    drawPath(path, color)
}

private val PlayPoints = floatArrayOf(7f, 5f, 12f, 8f, 12f, 16f, 7f, 19f, 12f, 8f, 19f, 12f, 19f, 12f, 12f, 16f)
private val PausePoints = floatArrayOf(6f, 5f, 10f, 5f, 10f, 19f, 6f, 19f, 14f, 5f, 18f, 5f, 18f, 19f, 14f, 19f)

private class PlayerSurfaceMemory<T> { var last: T? = null }

/** Android's host uses the same content transition while keeping its own queue and artwork pager. */
@Composable
fun <T> PlayerMetadataTransition(target: T, motion: Boolean, direction: Int, modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit) {
    PlayerTrackSwap(target, if (motion) PlayerMotionLevel.Full else PlayerMotionLevel.None,
        modifier, Alignment.TopStart, direction, content)
}

@Composable
internal fun <T : Any> PlayerSurfaceTransition(
    current: T?,
    level: PlayerMotionLevel,
    modifier: Modifier = Modifier,
    mini: Boolean = false,
    transitionState: MutableTransitionState<Boolean>? = null,
    content: @Composable (T) -> Unit,
) {
    val transition = transitionState ?: remember { MutableTransitionState(false) }
    val held = remember { PlayerSurfaceMemory<T>() }
    if (current != null) held.last = current
    transition.targetState = current != null
    if (!level.fades) {
        if (current != null) Box(modifier, propagateMinConstraints = true) { content(current) }
        return
    }
    val shown = current ?: held.last ?: return
    val enter = fadeIn(tween(PlayerMotionSpecs.FadeMillis)) + if (level.moves)
        slideInVertically(PlayerMotionSpecs.travel()) { it / 8 } +
            if (mini) expandVertically(PlayerMotionSpecs.travel()) else EnterTransition.None else EnterTransition.None
    val exit = fadeOut(tween(PlayerMotionSpecs.FadeMillis)) + if (level.moves)
        slideOutVertically(PlayerMotionSpecs.travel()) { it / 8 } +
            if (mini) shrinkVertically(PlayerMotionSpecs.travel()) else ExitTransition.None else ExitTransition.None
    AnimatedVisibility(visibleState = transition, modifier = modifier, enter = enter, exit = exit,
        label = "playerSurface") { content(shown) }
}

internal fun PlayerSlots.withTrackMotion(level: PlayerMotionLevel): PlayerSlots {
    val base = this
    return PlayerSlots(
        artwork = { id, modifier -> PlayerTrackSwap(id, level, modifier) { base.artwork(it, Modifier.fillMaxSize()) } },
        canvas = { id, active, modifier -> PlayerTrackSwap(id, level, modifier) { shown ->
            base.canvas(shown, active && shown == id, Modifier.fillMaxSize())
        } },
        customBackground = base.customBackground,
        lyrics = base.lyrics,
        compactLyrics = base.compactLyrics,
        queue = base.queue,
        outputButton = base.outputButton,
    )
}

/** Digits of equal width, so a running clock does not shift the text around it. */
internal fun TextStyle.tabularFigures(): TextStyle = copy(fontFeatureSettings = "tnum")

/**
 * A title or artist line that crossfades to the next track's text, as the Android player's title and artist do.
 * [modifier] and [alignment] place the line the same way with and without the fade, so a caller's weight or
 * fillMaxWidth applies to this wrapper either way.
 */
@Composable
internal fun <T> PlayerTextSwap(
    target: T,
    fade: Boolean,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.TopStart,
    content: @Composable (T) -> Unit,
) {
    val track = LocalPlayerTrackMotion.current
    PlayerTrackSwap(target, if (fade) track.level.takeIf { it.fades } ?: PlayerMotionLevel.Fade else PlayerMotionLevel.None,
        modifier, alignment, track.direction, content)

}

/** Fixed-size slots retain their outgoing content until the spring settles; rapid changes reverse immediately. */
@Composable
internal fun <T> PlayerTrackSwap(
    target: T,
    level: PlayerMotionLevel,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.Center,
    direction: Int = LocalPlayerTrackMotion.current.direction,
    content: @Composable (T) -> Unit,
) {
    if (!level.fades) {
        Box(modifier, contentAlignment = alignment, propagateMinConstraints = true) { content(target) }
        return
    }
    AnimatedContent(targetState = target, modifier = modifier, contentAlignment = alignment,
        transitionSpec = {
            val fadeIn = fadeIn(tween(PlayerMotionSpecs.FadeMillis))
            val fadeOut = fadeOut(tween(PlayerMotionSpecs.FadeMillis))
            if (level.moves) {
                ((fadeIn + slideInHorizontally(PlayerMotionSpecs.travel()) { direction * it / 8 }) togetherWith
                    (fadeOut + slideOutHorizontally(PlayerMotionSpecs.travel()) { -direction * it / 8 })).using(null)
            } else (fadeIn togetherWith fadeOut).using(null)
        }, label = "playerTrack") { content(it) }
}

/** Fades a buffering indicator in and out instead of popping it over the play button. */
@Composable
internal fun PlayerBufferingFade(visible: Boolean, fade: Boolean, content: @Composable () -> Unit) {
    if (!fade) {
        if (visible) content()
        return
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(PlayerMotionSpecs.FadeMillis)),
        exit = fadeOut(tween(PlayerMotionSpecs.FadeMillis)),
        label = "playerBuffering",
    ) { content() }
}

/** Enter and exit of a panel laid over the phone player, taken from the Android queue sheet and lyrics sheet. */
internal fun playerPanelEnter(panel: PlayerPanel, level: PlayerMotionLevel): EnterTransition = when {
    level == PlayerMotionLevel.None -> EnterTransition.None
    level == PlayerMotionLevel.Fade -> fadeIn(tween(PlayerMotionSpecs.FadeMillis))
    panel == PlayerPanel.LYRICS -> slideInVertically(PlayerMotionSpecs.travel<IntOffset>()) { it / 8 } + fadeIn(tween(PlayerMotionSpecs.FadeMillis))
    else -> slideInVertically(PlayerMotionSpecs.travel<IntOffset>()) { it / 8 } + fadeIn(tween(PlayerMotionSpecs.FadeMillis))
}

internal fun playerPanelExit(panel: PlayerPanel, level: PlayerMotionLevel): ExitTransition = when {
    level == PlayerMotionLevel.None -> ExitTransition.None
    level == PlayerMotionLevel.Fade -> fadeOut(tween(PlayerMotionSpecs.FadeMillis))
    panel == PlayerPanel.LYRICS -> slideOutVertically(PlayerMotionSpecs.travel<IntOffset>()) { it / 8 } + fadeOut(tween(PlayerMotionSpecs.FadeMillis))
    else -> slideOutVertically(PlayerMotionSpecs.travel<IntOffset>()) { it / 8 } + fadeOut(tween(PlayerMotionSpecs.FadeMillis))
}

/**
 * Shows [content] for the open panel, or nothing while [panel] is null. The last open panel stays composed while it
 * leaves, so closing the queue never flashes the lyrics on its way out. [vertical] slides a panel laid over the player
 * up from the bottom; otherwise a side panel opens out from the player's edge.
 */
@Composable
internal fun PlayerPanelTransition(
    panel: PlayerPanel?,
    level: PlayerMotionLevel,
    modifier: Modifier = Modifier,
    vertical: Boolean = true,
    content: @Composable (PlayerPanel) -> Unit,
) {
    val transition = remember { MutableTransitionState(false) }
    transition.targetState = panel != null
    val remembered = remember { arrayOfNulls<PlayerPanel>(1) }
    if (panel != null) remembered[0] = panel
    if (level == PlayerMotionLevel.None) {
        if (panel != null) Box(modifier, propagateMinConstraints = true) { content(panel) }
        return
    }
    val last = panel ?: remembered[0] ?: return
    val enter = when {
        vertical -> playerPanelEnter(last, level)
        level.moves -> fadeIn(tween(PlayerMotionSpecs.FadeMillis)) + expandHorizontally(PlayerMotionSpecs.travel(), expandFrom = Alignment.Start)
        else -> fadeIn(tween(PlayerMotionSpecs.FadeMillis))
    }
    val exit = when {
        vertical -> playerPanelExit(last, level)
        level.moves -> fadeOut(tween(PlayerMotionSpecs.FadeMillis)) + shrinkHorizontally(PlayerMotionSpecs.travel(), shrinkTowards = Alignment.Start)
        else -> fadeOut(tween(PlayerMotionSpecs.FadeMillis))
    }
    AnimatedVisibility(
        visibleState = transition,
        modifier = modifier,
        enter = enter,
        exit = exit,
        label = "playerPanel",
    ) { content(last) }
}

/** Corner interpolation reads animation state during drawing; callers keep their existing circle or rounded rectangle. */
@Composable
fun rememberPlayerButtonShape(playing: Boolean, pressed: Boolean, base: Shape, motion: Boolean): Shape {
    val roundness = animateFloatAsState(
        if (pressed && motion) .52f else if (playing) .64f else 1f,
        if (motion) PlayerMotionSpecs.settle() else snap(), label = "playerControlShape")
    val animatedShape = remember(base) {
        object : Shape {
            override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
                val outline = base.createOutline(size, layoutDirection, density)
                if (outline !is Outline.Rounded) return outline
                val r = outline.roundRect
                val factor = roundness.value
                return Outline.Rounded(RoundRect(r.left, r.top, r.right, r.bottom,
                    r.topLeftCornerRadius * factor, r.topRightCornerRadius * factor,
                    r.bottomRightCornerRadius * factor, r.bottomLeftCornerRadius * factor))
            }
        }
    }
    return animatedShape
}
