package moe.rukamori.archivetune.shared.ui.glass

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The platform's animator duration scale from developer options: 1 is the default, 0.5 halves every duration and 0 turns
 * animation off. Each host provides the live value. Without a provider the scale stays at 1, so the plain springs apply.
 */
val LocalAnimatorDurationScale = compositionLocalOf { 1f }

/**
 * A spring that stays smooth below 1x: it drops the overshoot that would need more frames than the shortened duration
 * allows, and it snaps at 0, where the system has turned animation off. At 1x and above it is the plain spring, because
 * the animation clock already shortens it. Ported from the baseline scaleAwareSpring.
 */
fun <T> scaleAwareSpring(
    scale: Float,
    dampingRatio: Float = Spring.DampingRatioNoBouncy,
    stiffness: Float = Spring.StiffnessMedium,
    visibilityThreshold: T? = null,
): FiniteAnimationSpec<T> =
    when {
        scale <= 0f -> snap()
        scale >= 1f -> spring(dampingRatio, stiffness, visibilityThreshold)
        else ->
            spring(
                dampingRatio = 1f + (dampingRatio - 1f) * scale,
                stiffness = stiffness * (0.5f + 0.5f * scale),
                visibilityThreshold = visibilityThreshold,
            )
    }

/**
 * Whether decorative and busy-indicator animation may run here. [LocalOpticalPolicy] already folds in the screen's
 * resumed and visible state, the in-app animation switch and the platform reduced-motion setting, so a hidden,
 * minimized or occluded surface stops its frame clocks. A zero animator scale turns animation off as well.
 */
@Composable
@ReadOnlyComposable
internal fun motionAllowed(): Boolean = LocalOpticalPolicy.current.motion && LocalAnimatorDurationScale.current > 0f

/**
 * The fixed fraction a busy indicator holds while motion is off. It marks work in progress with a static arc or bar; it
 * is not a progress value and never reports how far the work has got.
 */
internal const val StaticBusyFraction = 0.25f

/** An indeterminate spinner that holds [StaticBusyFraction] while motion is off, so no frame clock runs for it. */
@Composable
internal fun BusyCircularProgress(modifier: Modifier = Modifier, strokeWidth: Dp = 4.dp) {
    if (motionAllowed()) {
        CircularProgressIndicator(modifier = modifier, strokeWidth = strokeWidth)
    } else {
        CircularProgressIndicator(progress = { StaticBusyFraction }, modifier = modifier, strokeWidth = strokeWidth)
    }
}

/** An indeterminate bar that holds [StaticBusyFraction] while motion is off, so no frame clock runs for it. */
@Composable
internal fun BusyLinearProgress(modifier: Modifier = Modifier) {
    if (motionAllowed()) {
        LinearProgressIndicator(modifier = modifier)
    } else {
        LinearProgressIndicator(progress = { StaticBusyFraction }, modifier = modifier)
    }
}
