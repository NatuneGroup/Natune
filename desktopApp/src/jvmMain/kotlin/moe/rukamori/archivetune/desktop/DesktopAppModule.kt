/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.desktop

/**
 * Names the desktop host module. Its Compose Desktop window entry point lives in `Main.kt`, and
 * the runtime setup that must precede the window lives in `DesktopRuntime.kt`.
 */
object DesktopAppModule {
    const val NAME: String = "desktopApp"
}
