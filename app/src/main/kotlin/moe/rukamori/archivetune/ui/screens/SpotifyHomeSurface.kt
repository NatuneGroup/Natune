/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import moe.rukamori.archivetune.LocalBlurPolicy

/**
 * The Spotify page's background: the app's blurred atmosphere, or the flat surface when blur is off.
 *
 * The page draws no glass of its own. The reference's shelves sit directly on the background, and its
 * quick-pick cells are a raised material surface with a stroke rather than a refractive card, so
 * nothing here needs a recorded backdrop.
 */
@Composable
internal fun SpotifyHomeLayout(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val disableBlur = !LocalBlurPolicy.current.any
    Box(
        modifier = modifier,
    ) {
        if (disableBlur) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
        } else {
            HomeAtmosphereBackground(Modifier)
        }
        content()
    }
}
