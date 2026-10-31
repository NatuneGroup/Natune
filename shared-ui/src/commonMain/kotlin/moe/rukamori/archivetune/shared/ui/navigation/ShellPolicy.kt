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
    settingsRoute: Boolean = false,
): ShellLayout {
    if (searchActive || settingsRoute) return ShellLayout.Hidden
    // The Apple Music bar itself owns its one-row geometry, not the preset indicator.
    if (preferences.navigationPillStyle() == NavigationBarStyle.APPLE_MUSIC) return ShellLayout.CompactRow
    if (television) return ShellLayout.TvRail
    if (widthDp >= 840f || preferences[TabletModeEnabledKey, false]) return ShellLayout.Rail
    val behavior = preferences.navigationBarCompactBehavior()
    val compact = when (behavior) {
        NavigationBarCompactBehavior.ALWAYS_COMPACT -> true
        NavigationBarCompactBehavior.ADAPTIVE -> scrolledDown
        NavigationBarCompactBehavior.ALWAYS_EXPANDED -> false
    }
    if (compact) return ShellLayout.CompactRow
    // "Hide while scrolling" with the one-row layout turned off keeps the older look: the bar
    // slides away and the full-width mini player drops into its place.
    val hiddenByScroll =
        preferences[NavigationBarHideOnScrollKey, false] &&
            behavior == NavigationBarCompactBehavior.ALWAYS_EXPANDED &&
            scrolledDown
    if (!topLevel || hiddenByScroll) return ShellLayout.MiniPlayerOnly
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

/**
 * The settings page and every settings subpage. Those routes draw no tab bar, rail, compact row or mini player on any
 * platform, and playback continues underneath. The first path segment is the one [ArchiveRoutes.resolve] maps to SETTINGS.
 */
fun isSettingsRoute(route: String): Boolean = route.substringBefore('?').substringBefore('/') == "settings"

fun compactLeftIsLibrary(route: String): Boolean =
    route in setOf("home", "history", "local_songs", "library_playlists", "library_spotify_playlists", "library_artists") ||
        listOf("local_playlist/", "auto_playlist/", "cache_playlist/", "spotify_playlist/", "online_playlist/", "top_playlist/")
            .any(route::startsWith)

fun compactSearchVisible(route: String): Boolean =
    !route.startsWith("artist/") && !route.startsWith("artist_items/") &&
        (route == "history" || route == "local_songs" || !compactLeftIsLibrary(route) || route == "home")
