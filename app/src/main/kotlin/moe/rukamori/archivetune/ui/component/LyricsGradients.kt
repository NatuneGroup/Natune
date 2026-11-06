package moe.rukamori.archivetune.ui.component

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** [Brush.horizontalGradient] whose stop positions are mirrored for right-to-left text. */
internal fun rtlAwareHorizontalGradient(
    isRtl: Boolean,
    vararg colorStops: Pair<Float, Color>,
): Brush {
    val stops =
        if (isRtl) {
            colorStops
                .map { (f, c) -> (1f - f).coerceIn(0f, 1f) to c }
                .sortedBy { it.first }
        } else {
            colorStops.toList()
        }
    return Brush.horizontalGradient(*stops.toTypedArray())
}
