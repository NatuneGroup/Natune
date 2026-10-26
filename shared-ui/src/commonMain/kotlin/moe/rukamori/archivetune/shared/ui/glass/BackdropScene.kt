/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.shared.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.*

val LocalNavigationHaze = staticCompositionLocalOf<HazeState?> { null }

/**
 * Whether a frosted navigation surface is composed on screen. The shell publishes it, so the navigation recording runs
 * only while a frosted bar, rail or compact row samples it, as the baseline's frosted backdrop gate does. Starts open, so
 * the first frame records as it did before the shell reported.
 */
@Stable
internal class FrostedNavigationGate {
    var onScreen: Boolean by mutableStateOf(true)
}

internal val LocalFrostedNavigationGate = staticCompositionLocalOf<FrostedNavigationGate?> { null }

/** Apply the supplied modifier exactly once to page content, never to the enclosing shell/chrome. */
@Composable
fun ProvideBackdropSources(
    policy: OpticalPolicy,
    content: @Composable (pageSource: Modifier) -> Unit,
) {
    val backdrop = if (policy.recordLiquidGlass) rememberBackdrop(MaterialTheme.colorScheme.background) else null
    val gate = remember { FrostedNavigationGate() }
    val navigationRecorded = policy.recordNavigation && (gate.onScreen || policy.frostedMiniPlayer)
    // Remembered unconditionally, so the haze state survives each time the frosted surfaces come and go.
    val navigationHaze = remember { HazeState() }
    CompositionLocalProvider(
        LocalOpticalPolicy provides policy,
        LocalLiquidGlassBackdrop provides backdrop,
        LocalNavigationHaze provides if (navigationRecorded) navigationHaze else null,
        LocalFrostedNavigationGate provides gate,
    ) {
        content(Modifier
            .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
            .then(if (navigationRecorded) Modifier.hazeSource(navigationHaze) else Modifier))
    }
}

/** Record page content once; glass/chrome is a sibling and therefore cannot sample itself. */
@Composable
fun BackdropScene(
    policy: OpticalPolicy,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
    chrome: @Composable BoxScope.() -> Unit,
) {
    ProvideBackdropSources(policy) { pageSource ->
        Box(modifier) {
            Box(Modifier.fillMaxSize().then(pageSource)) { content() }
            chrome()
        }
    }
}

/** Root frosted path deliberately uses a separate recording from refractive glass and artwork. */
@Composable
fun Modifier.frostedNavigation(tinted: Boolean = false): Modifier {
    val state = LocalNavigationHaze.current
    val color = MaterialTheme.colorScheme.surfaceContainer
    return if (state != null && LocalOpticalPolicy.current.recordNavigation) {
        hazeEffect(state, HazeStyle(
            backgroundColor = MaterialTheme.colorScheme.background,
            tint = HazeTint(color.copy(alpha = if (tinted) .65f else .3f)),
            blurRadius = 24.dp,
            noiseFactor = .15f,
        ))
    } else background(color)
}

val ArtworkHazeStyle = HazeStyle(
    blurRadius = 80.dp,
    tint = HazeTint(Color.Black.copy(alpha = .30f)),
    noiseFactor = .15f,
)

/**
 * Haze artwork is its own optical path; its content slot is already-loaded artwork, never UI chrome. With artwork blur
 * off, no Haze layer is composed and the artwork is drawn as is: the baseline never draws Haze then and selects its
 * gradient background instead, so no tint is invented here.
 */
@Composable
fun ArtworkHaze(
    modifier: Modifier = Modifier,
    policy: OpticalPolicy = LocalOpticalPolicy.current,
    artwork: @Composable () -> Unit,
) {
    val state = remember { HazeState() }
    Box(modifier) {
        Box(Modifier.fillMaxSize().then(if (policy.artworkBlur) Modifier.hazeSource(state) else Modifier)) { artwork() }
        if (policy.artworkBlur) {
            Box(Modifier.matchParentSize().hazeEffect(state, ArtworkHazeStyle))
        }
    }
}
