/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.shared.ui.navigation

import moe.rukamori.archivetune.shared.domain.settings.*

enum class ShellLayout { BottomBar, CompactRow, Rail, TvRail, MiniPlayerOnly, Hidden }

/** Pass the running (startup-latched) navigation preferences, not a pending settings draft. */
fun shellLayout(
    widthDp: Float,
    television: Boolean,
    preferences: PreferenceSnapshot,
    scrolledDown: Boolean = false,
    searchActive: Boolean = false,
    topLevel: Boolean = true,
): ShellLayout {
    if (searchActive) return ShellLayout.Hidden
    // The coordinated experience cannot be broken by a saved compact/rail selection.
    if (preferences.interfaceStyle() == InterfaceStyle.APPLE_MUSIC) return ShellLayout.CompactRow
    if (television) return ShellLayout.TvRail
    if (widthDp >= 840f || preferences[TabletModeEnabledKey, false]) return ShellLayout.Rail
    val compact = when (preferences[NavigationBarCompactBehaviorKey]) {
        "ALWAYS_COMPACT" -> true
        "ADAPTIVE" -> scrolledDown
        else -> false
    }
    if (compact) return ShellLayout.CompactRow
    if (!topLevel || (scrolledDown && preferences[NavigationBarHideOnScrollKey, false])) return ShellLayout.MiniPlayerOnly
    return ShellLayout.BottomBar
}

/**
 * True when a frosted navigation surface is composed on screen for [layout]: the rail, the compact row or the M3 bottom
 * bar while a frosted preference is on. The liquid glass bar and the SukiSu bar never draw the frosted treatment, and a
 * hidden or mini-player-only shell draws no navigation surface. Mirrors the baseline frosted backdrop gate.
 */
fun frostedNavigationOnScreen(layout: ShellLayout, preferences: PreferenceSnapshot): Boolean {
    val frosted = preferences[NavigationBarFrostedBlurKey, false] || preferences[NavigationBarTintFrostedBlurKey, false]
    if (!frosted || preferences[LiquidGlassNavBarEnabledKey, false]) return false
    return when (layout) {
        ShellLayout.Rail, ShellLayout.TvRail, ShellLayout.CompactRow -> true
        ShellLayout.BottomBar -> !preferences.usesSukisuNavigationBar()
        ShellLayout.MiniPlayerOnly, ShellLayout.Hidden -> false
    }
}

fun compactLeftIsLibrary(route: String): Boolean =
    route in setOf("home", "history", "local_songs", "library_playlists", "library_spotify_playlists", "library_artists") ||
        listOf("local_playlist/", "auto_playlist/", "cache_playlist/", "spotify_playlist/", "online_playlist/", "top_playlist/")
            .any(route::startsWith)

fun compactSearchVisible(route: String): Boolean =
    !route.startsWith("artist/") && !route.startsWith("artist_items/") &&
        (route == "history" || route == "local_songs" || !compactLeftIsLibrary(route) || route == "home")
