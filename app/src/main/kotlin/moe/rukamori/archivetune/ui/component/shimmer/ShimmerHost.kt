/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.ui.component.shimmer

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import com.valentinilk.shimmer.defaultShimmerTheme
import com.valentinilk.shimmer.shimmer
import moe.rukamori.archivetune.ui.utils.rememberLifecycleStarted

// Hoisted to file level — this brush + color list never changes, so we
// avoid allocating a new Brush.verticalGradient and a new List<Color> on
// every frame of the infinite shimmer animation. Shimmer hosts are visible
// on every screen that shows loading placeholders (search, library, album,
// playlist, etc.), so this allocation was running continuously during any
// loading state.
private val ShimmerFadeBrush =
    Brush.verticalGradient(listOf(Color.Black, Color.Transparent))

@Composable
fun ShimmerHost(
    modifier: Modifier = Modifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        horizontalAlignment = horizontalAlignment,
        verticalArrangement = verticalArrangement,
        modifier =
            modifier
                .shimmerWhileStarted()
                .graphicsLayer(alpha = 0.99f)
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = ShimmerFadeBrush,
                        blendMode = BlendMode.DstIn,
                    )
                },
        content = content,
    )
}

/**
 * [shimmer] while the app is STARTED. The sweep is a frame loop, so a placeholder left on screen while the app is in
 * the background holds still instead of animating an unseen window.
 */
@Composable
fun Modifier.shimmerWhileStarted(): Modifier {
    val started by rememberLifecycleStarted()
    return if (started) shimmer() else this
}

val ShimmerTheme =
    defaultShimmerTheme.copy(
        animationSpec =
            infiniteRepeatable(
                animation =
                    tween(
                        durationMillis = 800,
                        easing = LinearEasing,
                        delayMillis = 250,
                    ),
                repeatMode = RepeatMode.Restart,
            ),
        shaderColors =
            listOf(
                Color.Unspecified.copy(alpha = 0.25f),
                Color.Unspecified.copy(alpha = 0.50f),
                Color.Unspecified.copy(alpha = 0.25f),
            ),
    )
