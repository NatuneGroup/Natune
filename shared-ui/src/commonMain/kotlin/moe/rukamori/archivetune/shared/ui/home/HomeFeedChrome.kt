package moe.rukamori.archivetune.shared.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.ui.glass.StaticBusyFraction
import moe.rukamori.archivetune.shared.ui.glass.motionAllowed

// Pull-to-refresh: the usual circular puck is suppressed, and the drag feedback is a loader line along the bottom edge
// of the top bar. The line fills left to right as the pull approaches the threshold, then sweeps while the refresh runs,
// so the two phases read as one continuous gesture.

private val HomeRefreshLineHeight = 2.5.dp

/**
 * The refresh indicator line, tracking the drag on [distanceFraction] and sweeping while [refreshing]. Position it at the
 * bottom edge of the top bar. The owner sets [refreshing]; the line never reports a refresh on its own.
 */
@Composable
fun HomePullRefreshLine(
    refreshing: Boolean,
    distanceFraction: () -> Float,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = refreshing || distanceFraction() > 0.01f,
        enter = fadeIn(tween(120)),
        exit = fadeOut(tween(220)),
        modifier = modifier,
    ) {
        val lineModifier =
            Modifier
                .fillMaxWidth()
                .height(HomeRefreshLineHeight)
        if (refreshing && motionAllowed()) {
            LinearProgressIndicator(
                modifier = lineModifier,
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.Transparent,
                strokeCap = StrokeCap.Butt,
                gapSize = 0.dp,
            )
        } else if (refreshing) {
            // Motion is off: the refresh line holds a static bar instead of an indeterminate sweep.
            LinearProgressIndicator(
                progress = { StaticBusyFraction },
                modifier = lineModifier,
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.Transparent,
                strokeCap = StrokeCap.Butt,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        } else {
            LinearProgressIndicator(
                progress = { distanceFraction().coerceIn(0f, 1f) },
                modifier = lineModifier,
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.Transparent,
                strokeCap = StrokeCap.Butt,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        }
    }
}

/** The run the fade needs below the bar to get from full strength to none without the eye finding where it stopped. */
private val HomeTopFadeRun = 88.dp

/**
 * How dark the readability scrim starts, at the very top of the strip. Modest on purpose: it gives the glyphs a floor on
 * a pale sleeve, and it does not grey out the artwork.
 */
private const val HomeTopScrimPeak = 0.42f

/** Enough stops that the ramp does not band across a near-flat colour. */
private const val HomeTopScrimStops = 12

/**
 * The top-fade scrim behind the home top bar, keyed to the colour of the page underneath rather than the theme's
 * background, so it dissolves into whatever the page actually is. It is a plain gradient, not a blur, so it does not
 * need a haze source. The scrim is drawn at the same eased shape as the blur it accompanies, so both arrive at nothing
 * together.
 */
@Composable
fun HomeTopFadeBlur(
    pageColor: Color,
    barHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val height = barHeight + HomeTopFadeRun
    val scrim =
        remember(pageColor) {
            Brush.verticalGradient(
                colorStops =
                    Array(HomeTopScrimStops) { i ->
                        val t = i / (HomeTopScrimStops - 1f)
                        t to pageColor.copy(alpha = HomeTopScrimPeak * (1f - EaseOutCubic.transform(t)))
                    },
            )
        }
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(height)
                .background(scrim),
    )
}
