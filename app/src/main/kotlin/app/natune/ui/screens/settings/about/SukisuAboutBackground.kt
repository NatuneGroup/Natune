package app.natune.ui.screens.settings.about

import androidx.compose.foundation.background
import androidx.compose.runtime.getValue

internal class SukisuBgConfig(
    val points: FloatArray,
    val colors1: FloatArray,
    val colors2: FloatArray,
    val colors3: FloatArray,
    val colorInterpPeriod: Float,
    val lightOffset: Float,
    val saturateOffset: Float,
    val pointOffset: Float,
)

/**
 * How far the header has scrolled out of view, 0 at rest and 1 once the first item has gone. The
 * background fades over the first three quarters of that distance and stops drawing after it.
 */
internal fun sukisuHeaderScrollProgress(
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffset: Int,
    firstItemHeight: Int,
): Float =
    when {
        firstVisibleItemIndex > 0 -> 1f
        firstItemHeight <= 0 -> 0f
        else -> (firstVisibleItemScrollOffset.toFloat() / firstItemHeight).coerceIn(0f, 1f)
    }

internal fun sukisuBackgroundAlpha(scrollProgress: Float): Float = ((1f - scrollProgress) / 0.75f).coerceIn(0f, 1f)

/** The frame loop runs only while it can be seen and motion is allowed; otherwise one still frame. */
internal fun sukisuBackgroundAnimates(
    resumed: Boolean,
    animationsDisabled: Boolean,
    animationScale: Float,
    backgroundAlpha: Float,
): Boolean = resumed && !animationsDisabled && animationScale > 0f && backgroundAlpha > SUKISU_VISIBLE_ALPHA

private const val SUKISU_VISIBLE_ALPHA = 0.01f
