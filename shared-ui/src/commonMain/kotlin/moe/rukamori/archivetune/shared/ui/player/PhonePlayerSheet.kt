package moe.rukamori.archivetune.shared.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.ui.resources.Res
import moe.rukamori.archivetune.shared.ui.resources.m5_collapse
import org.jetbrains.compose.resources.stringResource

@Stable
internal class PhonePlayerSheetState(private val scope: CoroutineScope) {
    private val position = Animatable(0f)
    private var animation: Job? = null
    private var dragging by mutableStateOf(false)
    private var fling = false
    val progress: Float get() = position.value
    val visible: Boolean get() = progress > 0f || dragging

    init { position.updateBounds(0f, 1f) }

    suspend fun reset() {
        animation?.cancel()
        dragging = false
        fling = false
        position.snapTo(0f)
    }

    suspend fun animate(expanded: Boolean, motion: Boolean) {
        val stiffness = if (fling) Spring.StiffnessMediumLow else Spring.StiffnessLow
        fling = false
        if (!motion) position.snapTo(if (expanded) 1f else 0f)
        else position.animateTo(if (expanded) 1f else 0f,
            spring(Spring.DampingRatioNoBouncy, stiffness, visibilityThreshold = .001f))
    }

    fun drag(delta: Float) {
        animation?.cancel()
        dragging = true
        animation = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            position.snapTo((progress + delta).coerceIn(0f, 1f))
        }
    }

    fun settle(velocityDp: Float, expanded: Boolean, motion: Boolean, onExpanded: (Boolean) -> Unit) {
        dragging = false
        val target = when {
            velocityDp < -250f -> true
            velocityDp > 250f -> false
            else -> progress >= .5f
        }
        fling = true
        if (target != expanded) onExpanded(target)
        else animation = scope.launch { animate(target, motion) }
    }
}

@Composable
internal fun rememberPhonePlayerSheetState(): PhonePlayerSheetState {
    val scope = rememberCoroutineScope()
    return remember(scope) { PhonePlayerSheetState(scope) }
}

@Composable
internal fun PhonePlayerSheet(
    expanded: Boolean,
    onExpanded: (Boolean) -> Unit,
    motion: Boolean,
    state: PhonePlayerSheetState = rememberPhonePlayerSheetState(),
    modifier: Modifier = Modifier,
    dragEnabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    LaunchedEffect(expanded, motion) { state.animate(expanded, motion) }
    val collapseLabel = stringResource(Res.string.m5_collapse)
    if (expanded || state.visible) {
        BoxWithConstraints(modifier.fillMaxSize()) {
            val heightPx = with(LocalDensity.current) { maxHeight.toPx() }.coerceAtLeast(1f)
            val density = LocalDensity.current.density
            Box(Modifier.fillMaxSize().testTag("phone-player-sheet")
                .graphicsLayer { translationY = (1f - state.progress) * size.height }
                .semantics {
                    progressBarRangeInfo = ProgressBarRangeInfo(state.progress, 0f..1f)
                    customActions = listOf(CustomAccessibilityAction(collapseLabel) { onExpanded(false); true })
                }
                .draggable(rememberDraggableState { state.drag(-it / heightPx) }, Orientation.Vertical,
                    enabled = dragEnabled,
                    onDragStopped = { state.settle(it / density, expanded, motion, onExpanded) })) {
                content()
            }
        }
    }
}

internal fun Modifier.phonePlayerExpandDrag(onExpand: () -> Unit): Modifier = composed {
    val threshold = with(LocalDensity.current) { 24.dp.toPx() }
    val flingThreshold = with(LocalDensity.current) { 250.dp.toPx() }
    var distance by remember { mutableFloatStateOf(0f) }
    draggable(rememberDraggableState { distance += it }, Orientation.Vertical,
        onDragStarted = { distance = 0f },
        onDragStopped = { velocity -> if (distance < -threshold || velocity < -flingThreshold) onExpand() })
}
