/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.shared.domain.settings

/**
 * The defaults the shared policies assume when a key has never been written. Only the values these policies decide on
 * live here. Every other default stays with the surface that owns it, and the restart-scoped defaults live in
 * [RestartScopedSettings.Defaults].
 */
object PreferenceDefaults {
    /** The player a fresh install opens with. */
    val playerDesignStyle: PlayerDesignStyle = PlayerDesignStyle.Default

    /** Material 3 Expressive is the interface unless the Apple Music experience is switched on. */
    val interfaceStyle: InterfaceStyle = InterfaceStyle.MATERIAL_EXPRESSIVE

    val appleMusicExperience: Boolean = false

    val libraryStyle: LibraryStyle = LibraryStyle.DEFAULT

    /** The YouTube Music region when none is chosen, meaning the device's own region. */
    val youtubeMusicRegion: String = "SYSTEM_DEFAULT"
}
