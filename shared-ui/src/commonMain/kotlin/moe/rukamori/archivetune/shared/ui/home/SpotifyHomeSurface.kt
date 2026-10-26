package moe.rukamori.archivetune.shared.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import moe.rukamori.archivetune.shared.ui.glass.LiquidGlassRole
import moe.rukamori.archivetune.shared.ui.glass.LocalOpticalPolicy
import moe.rukamori.archivetune.shared.ui.glass.PlatformBackdrop
import moe.rukamori.archivetune.shared.ui.glass.layerBackdrop
import moe.rukamori.archivetune.shared.ui.glass.liquidGlassMaterial
import moe.rukamori.archivetune.shared.ui.glass.rememberBackdrop

private val LocalSpotifyHomeBackdrop = staticCompositionLocalOf<PlatformBackdrop?> { null }

/**
 * The Spotify home page's own backdrop. When the host's blur preference and optics policy allow refraction, the
 * atmosphere is recorded into a private backdrop that the cards sample; otherwise the flat surface is painted. The
 * recording is private to this page, so the shell's own glass never samples it.
 */
@Composable
fun SpotifyHomeLayout(
    blurEffects: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val glass = blurEffects && LocalOpticalPolicy.current.recordLiquidGlass
    val backdrop = if (glass) rememberBackdrop(MaterialTheme.colorScheme.surface) else null
    Box(modifier) {
        if (!blurEffects) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
        } else {
            HomeClassicAtmosphereBackground(Modifier.then(backdrop?.let { Modifier.layerBackdrop(it) } ?: Modifier))
        }
        CompositionLocalProvider(LocalSpotifyHomeBackdrop provides backdrop) {
            content()
        }
    }
}

/** A Spotify home card: refractive glass over the recorded atmosphere, or the flat card tint without it. */
@Composable
fun Modifier.spotifyHomeSurface(shape: Shape): Modifier {
    val backdrop = LocalSpotifyHomeBackdrop.current
    val clipped = clip(shape)
    if (backdrop == null) return clipped.background(MaterialTheme.colorScheme.surfaceContainerHigh)
    return clipped.liquidGlassMaterial(
        backdrop = backdrop,
        shape = shape,
        role = LiquidGlassRole.Card,
        interactive = true,
    )
}
