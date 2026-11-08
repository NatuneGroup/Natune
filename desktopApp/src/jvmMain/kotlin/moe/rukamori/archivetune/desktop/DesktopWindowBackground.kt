package moe.rukamori.archivetune.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import java.awt.Window
import moe.rukamori.archivetune.shared.ui.theme.archiveTuneWindowBackground

/**
 * The AWT window's own background colour.
 *
 * Compose paints the client area, but AWT owns every pixel Compose did not: the strip a resize has not repainted
 * yet, and whatever the compositor cuts away from a rounded window corner. The toolkit's default there is black
 * (0,0,0), which reads as a hole in the window and hides the fact that the page simply has not been drawn there yet.
 * Giving the window the app's background colour makes those pixels the page's own colour, so a compositor that
 * rounds the window corners rounds the app instead of cutting black notches out of it.
 */
@Composable
internal fun DesktopWindowBackground(window: Window, darkTheme: Boolean) {
    LaunchedEffect(window, darkTheme) {
        val color = archiveTuneWindowBackground(darkTheme)
        // Built from the Colour's own components: Compose's `toAwtColor` is still an experimental API.
        window.background = java.awt.Color(color.red, color.green, color.blue, color.alpha)
    }
}
