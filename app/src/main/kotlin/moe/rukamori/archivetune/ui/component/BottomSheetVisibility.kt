package moe.rukamori.archivetune.ui.component

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout

internal data class BottomSheetHitTargets(val root: Boolean, val expanded: Boolean, val collapsed: Boolean)

/** Alpha-zero retained player content must not cover the screen or the visible mini player. */
internal fun bottomSheetHitTargets(progress: Float, dismissed: Boolean, collapsedContentVisible: Boolean): BottomSheetHitTargets {
    val onScreen = !dismissed && (collapsedContentVisible || progress > 0f)
    return BottomSheetHitTargets(onScreen, onScreen && progress > 0.25f,
        onScreen && collapsedContentVisible && progress < 0.25f)
}

/** Keep composition state alive without placing hidden descendants in the hit-test tree. */
internal fun Modifier.placeOnlyWhen(visible: Boolean): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    if (visible) layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
    else layout(0, 0) { }
}
