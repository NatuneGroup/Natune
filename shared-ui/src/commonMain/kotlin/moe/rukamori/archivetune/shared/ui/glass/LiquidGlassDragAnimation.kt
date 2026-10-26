/*
 * Drag-to-slide animation for the Liquid Glass navigation pill, ported from the baseline
 * LiquidGlassDragAnimation.kt. That file adapts SukiSU-Ultra's DampedDragAnimation and inspectDragGestures,
 * which themselves adapt compose-miuix-ui (Apache 2.0).
 *
 * A tap runs press and release, so the pill gives feedback even without a drag. Holding and dragging moves the
 * pill with the finger and tracks its velocity for the squash-and-stretch. The gesture is observed on the Initial
 * pass, so the tab items keep their own click handling: a tap still selects, and a drag only moves the pill.
 */
package moe.rukamori.archivetune.shared.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.util.fastFirstOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Suspends until the next frame. A release waits on it so the snap-to-target animation starts before the press
 * relaxes; otherwise the pill would shrink before it slides.
 */
private suspend fun awaitFrame() {
    withFrameNanos { }
}

/**
 * Observe-only drag detector. It reads the Initial pass so it sees the pointer before the children's Main-pass
 * handlers, which is what lets it coexist with each tab's own clickable.
 */
internal suspend fun PointerInputScope.inspectDragGestures(
    onDragStart: (down: PointerInputChange) -> Unit = {},
    onDragEnd: (change: PointerInputChange) -> Unit = {},
    onDragCancel: () -> Unit = {},
    onDrag: (change: PointerInputChange, dragAmount: Offset) -> Unit,
) {
    awaitEachGesture {
        val initialDown = awaitFirstDown(false, PointerEventPass.Initial)

        val down = awaitFirstDown(false)

        onDragStart(down)
        onDrag(initialDown, Offset.Zero)
        val upEvent =
            drag(
                pointerId = initialDown.id,
                onDrag = { onDrag(it, it.positionChange()) },
            )
        if (upEvent == null) {
            onDragCancel()
        } else {
            onDragEnd(upEvent)
        }
    }
}

private suspend inline fun AwaitPointerEventScope.drag(
    pointerId: PointerId,
    onDrag: (PointerInputChange) -> Unit,
): PointerInputChange? {
    val isPointerUp = currentEvent.changes.fastFirstOrNull { it.id == pointerId }?.pressed != true
    if (isPointerUp) {
        return null
    }
    var pointer = pointerId
    while (true) {
        val change = awaitDragOrUp(pointer) ?: return null
        if (change.isConsumed) {
            return null
        }
        if (change.changedToUpIgnoreConsumed()) {
            return change
        }
        onDrag(change)
        pointer = change.id
    }
}

private suspend inline fun AwaitPointerEventScope.awaitDragOrUp(
    pointerId: PointerId,
): PointerInputChange? {
    var pointer = pointerId
    while (true) {
        val event = awaitPointerEvent()
        val dragEvent = event.changes.fastFirstOrNull { it.id == pointer } ?: return null
        if (dragEvent.changedToUpIgnoreConsumed()) {
            val otherDown = event.changes.fastFirstOrNull { it.pressed }
            if (otherDown == null) {
                return dragEvent
            } else {
                pointer = otherDown.id
            }
        } else {
            val hasDragged = dragEvent.previousPosition != dragEvent.position
            if (hasDragged) {
                return dragEvent
            }
        }
    }
}

/**
 * Drives the Liquid Glass navigation pill. It holds five [Animatable] channels (position, velocity, press progress,
 * scale X and scale Y) and exposes one [modifier] that feeds drag gestures into them. The caller decides whether the
 * decorative channels are read: the pill still slides when motion is off, and the press and squash are skipped.
 */
internal class LiquidGlassDragAnimation(
    private val animationScope: CoroutineScope,
    val initialValue: Float,
    val valueRange: ClosedRange<Float>,
    val visibilityThreshold: Float,
    val initialScale: Float,
    val pressedScale: Float,
    val canDrag: (Offset) -> Boolean = { true },
    val onDragStarted: LiquidGlassDragAnimation.(position: Offset) -> Unit,
    val onDragStopped: LiquidGlassDragAnimation.() -> Unit,
    val onDrag: LiquidGlassDragAnimation.(size: IntSize, dragAmount: Offset) -> Unit,
) {

    private val valueAnimationSpec =
        spring(1f, 1000f, visibilityThreshold)
    private val velocityAnimationSpec =
        spring(0.5f, 300f, visibilityThreshold * 10f)
    private val pressProgressAnimationSpec =
        spring(1f, 1000f, 0.001f)
    private val scaleXAnimationSpec =
        spring(0.6f, 250f, 0.001f)
    private val scaleYAnimationSpec =
        spring(0.7f, 250f, 0.001f)

    private val valueAnimation =
        Animatable(initialValue, visibilityThreshold)
    private val velocityAnimation =
        Animatable(0f, 5f)
    private val pressProgressAnimation =
        Animatable(0f, 0.001f)
    private val scaleXAnimation =
        Animatable(initialScale, 0.001f)
    private val scaleYAnimation =
        Animatable(initialScale, 0.001f)

    // Held so a stale relax (suspended until the slide settles) is cancelled by the next press. Without this a tap
    // burst stacks several relax waits and keeps the glass inflated.
    private var releaseJob: Job? = null

    val value: Float get() = valueAnimation.value
    val targetValue: Float get() = valueAnimation.targetValue
    val pressProgress: Float get() = pressProgressAnimation.value
    val scaleX: Float get() = scaleXAnimation.value
    val scaleY: Float get() = scaleYAnimation.value
    val velocity: Float get() = velocityAnimation.value

    val modifier: Modifier = Modifier.pointerInput(Unit) {
        var dragActive = false
        inspectDragGestures(
            onDragStart = { down ->
                if (canDrag(down.position)) {
                    dragActive = true
                    onDragStarted(down.position)
                    press()
                } else {
                    dragActive = false
                }
            },
            onDragEnd = {
                if (dragActive) {
                    onDragStopped()
                    release()
                }
                dragActive = false
            },
            onDragCancel = {
                if (dragActive) {
                    onDragStopped()
                    release()
                }
                dragActive = false
            },
        ) { _, dragAmount ->
            // Once a drag has started the pill follows the finger wherever it moves, so the drag is not bounded
            // by the bar's own hit area.
            if (dragActive) {
                onDrag(size, dragAmount)
            }
        }
    }

    fun press() {
        // A new press supersedes a pending relax. Repeated presses continue the same expansion from its current
        // value, never from zero.
        releaseJob?.cancel()
        releaseJob = null
        animationScope.launch {
            launch { pressProgressAnimation.animateTo(1f, pressProgressAnimationSpec) }
            launch { scaleXAnimation.animateTo(pressedScale, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(pressedScale, scaleYAnimationSpec) }
        }
    }

    fun release() {
        releaseJob?.cancel()
        releaseJob =
            animationScope.launch {
                awaitFrame()
                if (value != targetValue) {
                    val threshold = (valueRange.endInclusive - valueRange.start) * 0.025f
                    snapshotFlow { valueAnimation.value }.first { abs(it - valueAnimation.targetValue) < threshold }
                }
                launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
                launch { scaleXAnimation.animateTo(initialScale, scaleXAnimationSpec) }
                launch { scaleYAnimation.animateTo(initialScale, scaleYAnimationSpec) }
            }
    }

    fun updateValue(value: Float) {
        val targetValue = value.coerceIn(valueRange)
        animationScope.launch {
            launch { valueAnimation.animateTo(targetValue, valueAnimationSpec) { updateVelocity() } }
        }
    }

    fun animateToValue(value: Float) {
        val targetValue = value.coerceIn(valueRange)
        val hadVelocity = velocity != 0f
        press()
        animationScope.launch {
            valueAnimation.animateTo(targetValue, valueAnimationSpec)
            if (hadVelocity) {
                velocityAnimation.animateTo(0f, velocityAnimationSpec)
            }
        }
        release()
    }

    private fun updateVelocity() {
        // The stretch reads the value animation's own velocity, in play time. A wall-clock tracker would exaggerate
        // the stretch below the system animator scale and flatten it above.
        val targetVelocity = valueAnimation.velocity / (valueRange.endInclusive - valueRange.start)
        animationScope.launch { velocityAnimation.animateTo(targetVelocity, velocityAnimationSpec) }
    }
}
