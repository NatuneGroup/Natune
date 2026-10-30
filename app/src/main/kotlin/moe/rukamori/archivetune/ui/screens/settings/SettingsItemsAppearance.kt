package moe.rukamori.archivetune.ui.screens.settings

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.AlbumCanvasEnabledKey
import moe.rukamori.archivetune.constants.BackdropEnabledKey
import moe.rukamori.archivetune.constants.CropThumbnailToSquareKey
import moe.rukamori.archivetune.constants.DisableAnimationsKey
import moe.rukamori.archivetune.constants.DisableBlurKey
import moe.rukamori.archivetune.constants.DynamicThemeKey
import moe.rukamori.archivetune.constants.ForceHighRefreshRateKey
import moe.rukamori.archivetune.constants.HideStatusBarKey
import moe.rukamori.archivetune.constants.HideNavigationBarLabelsKey
import moe.rukamori.archivetune.constants.HidePlayerThumbnailKey
import moe.rukamori.archivetune.constants.HideScrollbarKey
import moe.rukamori.archivetune.constants.NavigationBarHideOnScrollKey
import moe.rukamori.archivetune.constants.HideSearchChromeWhileScrollingKey
import moe.rukamori.archivetune.constants.SplashOverlayEnabledKey
import moe.rukamori.archivetune.constants.TikTokMainLyricsEnabledKey
import moe.rukamori.archivetune.constants.LiquidGlassEnabledKey
import moe.rukamori.archivetune.constants.CompactControlsStockColorKey
import moe.rukamori.archivetune.constants.LiquidGlassNavBarEnabledKey
import moe.rukamori.archivetune.constants.NavigationBarFrostedBlurKey
import moe.rukamori.archivetune.constants.PureBlackKey
import moe.rukamori.archivetune.constants.RandomThemeOnStartupKey
import moe.rukamori.archivetune.constants.ShowHomeCategoryChipsKey
import moe.rukamori.archivetune.constants.PlayerDesignStyle
import moe.rukamori.archivetune.constants.PlayerDesignStyleKey
import moe.rukamori.archivetune.utils.playerVolumeVisibility
import moe.rukamori.archivetune.constants.SwipeToSongKey

internal fun appearanceSettingsItem(
    navController: NavController,
    context: Context,
    colorScheme: ColorScheme,
): SettingsItem =
    SettingsItem(
        key = "appearance",
        icon = R.drawable.ic_palette,
        title = context.getString(R.string.appearance),
        subtitle = context.getString(R.string.settings_appearance_subtitle),
        accentColor = colorScheme.secondary,
        keywords = listOf("appearance", "theme", "dark", "light", "color", "palette", "style", "design"),
        onClick = { navController.navigate("settings/appearance") },
        children = listOf(
            SettingsChild("Dynamic theme", "dynamic_theme", listOf("dynamic theme", "material you", "dynamic color")) { SearchResultSwitch(DynamicThemeKey, true) },
            SettingsChild("Random theme on startup", "random_theme_on_startup", listOf("random theme", "random color", "shuffle theme")) { SearchResultSwitch(RandomThemeOnStartupKey, false) },
            SettingsChild("Dark theme", "dark_theme", listOf("dark", "dark theme", "night", "amoled")),
            SettingsChild("Pure black", "pure_black", listOf("pure black", "amoled", "oled", "black background")) { SearchResultSwitch(PureBlackKey, false) },
            SettingsChild("Color palette", "color_palette", listOf("color palette", "accent color", "theme color", "color")),
            SettingsChild("Color source", "color_source", listOf("color source", "color", "dynamic color", "material you")),
            SettingsChild("App icon", "app_icon", listOf("icon", "app icon", "icon pack", "launcher icon")),
            SettingsChild("Disable blur", "disable_blur", listOf("blur", "disable blur", "no blur", "performance")) { SearchResultSwitch(DisableBlurKey, false) },
            SettingsChild("Blur intensity", "blur_intensity", listOf("blur intensity", "blur amount", "blur level", "blur radius")),
            SettingsChild("Backdrop blur", "backdrop_blur", listOf("backdrop", "backdrop blur", "background blur", "frosted"), blurOverridden = true) { SearchResultSwitch(BackdropEnabledKey, false, blurOverridden = true) },
            SettingsChild("Font preference", "font_preference", listOf("font", "font style", "typography")),
            SettingsChild("Use system font", "use_system_font", listOf("system font", "default font", "roboto"), restartPending = { it.font }) { SearchResultSystemFontSwitch() },
            SettingsChild("Thumbnail corner radius", "thumbnail_corner_radius", listOf("thumbnail corner", "corner radius", "rounded thumbnail", "thumbnail shape")),
            SettingsChild("Crop thumbnail to square", "crop_thumbnail_to_square", listOf("crop thumbnail", "square thumbnail", "thumbnail crop")) { SearchResultSwitch(CropThumbnailToSquareKey, false) },
            SettingsChild("Enable canvas in albums page", "album_canvas_enabled", listOf("album canvas", "canvas in album", "album motion artwork", "album animated cover", "album header video")) { SearchResultSwitch(AlbumCanvasEnabledKey, true) },
            SettingsChild("Interface style", "interface_style", listOf("interface style", "design", "material you", "material 3", "expressive", "apple music", "apple music experience", "ios style", "apple tab bar")),
            SettingsChild("Library style", "library_style", listOf("library style", "apple music library", "library layout")),
            SettingsChild("Player design style", "player_design_style", listOf("player design", "player layout", "player style")),
            SettingsChild("Show lyrics on main player (TikTok)", "tiktok_main_lyrics", listOf("tiktok lyrics", "main player lyrics", "current line lyrics", "karaoke line", "tiktok player")) { SearchResultSwitch(TikTokMainLyricsEnabledKey, false) },
            SettingsChild("Player background style", "player_background_style", listOf("player background", "player bg", "background style")),
            SettingsChild("Lyrics background style", "lyrics_background_style", listOf("lyrics background", "lyrics bg")),
            SettingsChild("Mini player background style", "mini_player_background_style", listOf("mini player", "mini player background")),
            SettingsChild("Mini player layout", "compact_mini_player", listOf("compact mini player", "small mini player", "mini player layout")),
            SettingsChild("Player buttons style", "player_buttons_style", listOf("player buttons", "button style", "controls style")),
            SettingsChild("Player slider style", "player_slider_style", listOf("player slider", "slider style", "progress bar")),
            SettingsChild("Show player volume bar", "show_player_volume_bar", listOf("volume bar", "player volume", "volume slider")) { SearchPlayerVolumeVisibilitySwitch() },
            SettingsChild("Hide player thumbnail", "hide_player_thumbnail", listOf("hide thumbnail", "player thumbnail", "hide artwork")) { SearchResultSwitch(HidePlayerThumbnailKey, false) },
            SettingsChild("Swipe to song", "swipe_to_song", listOf("swipe to song", "swipe add to queue", "queue insertion")) { SearchResultSwitch(SwipeToSongKey, true) },
            SettingsChild("Mini player swipe sensitivity", "swipe_sensitivity", listOf("mini player", "gesture", "sensitivity")),
            SettingsChild("Disable animations", "disable_animations", listOf("animation", "disable animations", "no animations", "performance")) { SearchResultSwitch(DisableAnimationsKey, false) },
            SettingsChild("Splash screen animation", "splash_overlay_enabled", listOf("splash", "splash screen", "opening animation", "launch animation", "startup animation", "particles")) { SearchResultSwitch(SplashOverlayEnabledKey, true) },
            SettingsChild("Hide status bar", "hide_status_bar", listOf("status bar", "hide status", "immersive", "fullscreen", "hide bar")) { SearchResultSwitch(HideStatusBarKey, false) },
            SettingsChild("Force high refresh rate", "force_high_refresh_rate", listOf("refresh rate", "high refresh", "120hz", "90hz", "smooth")) { SearchResultSwitch(ForceHighRefreshRateKey, false) },
            SettingsChild("Navigation bar style", "navigation_bar_style", listOf("navigation bar", "nav bar", "bottom bar", "sukisu", "liquid glass bar")),
            SettingsChild("Frosted navigation bar", "frosted_nav_bar", listOf("frosted nav", "frosted navigation", "frosted blur"), restartPending = { it.navigationBarGlass }, blurOverridden = true) { SearchResultSwitch(NavigationBarFrostedBlurKey, false, blurOverridden = true) },
            SettingsChild("Liquid Glass navigation bar", "liquid_glass_nav_bar", listOf("liquid glass", "glass nav", "glass navigation", "liquid nav"), restartPending = { it.navigationBarGlass }, blurOverridden = true) { SearchResultSwitch(LiquidGlassNavBarEnabledKey, false, blurOverridden = true) },
            SettingsChild("Liquid Glass effects", "liquid_glass_effects", listOf("liquid glass", "glass effects", "liquid glass effects", "header glass", "mini player glass"), restartPending = { it.liquidGlass }, blurOverridden = true) { SearchResultSwitch(LiquidGlassEnabledKey, false, blurOverridden = true) },
            SettingsChild("Hide labels in navigation bar", "hide_navigation_bar_labels", listOf("hide labels", "navigation labels", "nav labels", "icons only")) { SearchResultSwitch(HideNavigationBarLabelsKey, false) },
            SettingsChild("Hide navigation bar while scrolling", "navigation_bar_hide_on_scroll", listOf("hide navigation bar", "hide nav bar", "scroll to hide", "auto hide bar")) { SearchResultSwitch(NavigationBarHideOnScrollKey, false) },
            SettingsChild("Navigation bar customization", "navigation_bar_settings", listOf("navigation bar", "nav bar dimensions", "nav bar opacity", "nav bar width", "nav bar height", "nav bar corner radius", "nav bar label spacing")),
            SettingsChild("Hide scrollbar", "hide_scrollbar", listOf("scrollbar", "scroll bar", "hide scroll", "no scrollbar")) { SearchResultSwitch(HideScrollbarKey, false) },
            SettingsChild("Default open tab", "default_open_tab", listOf("default tab", "home tab", "start page", "open tab")),
            SettingsChild("Search bar position", "search_bar_position", listOf("search bar", "bottom search", "search at bottom", "search position")),
            SettingsChild("Hide search bar while scrolling", "hide_search_bar_while_scrolling", listOf("hide search bar", "search scrolling", "auto hide search")) { SearchResultSwitch(HideSearchChromeWhileScrollingKey, false) },
            SettingsChild("Back animation style", "back_animation_style", listOf("back animation", "predictive back", "back gesture", "gesture animation")),
            SettingsChild("Grid layout", "grid_layout", listOf("grid", "layout", "list view", "artist grid")),
            SettingsChild("Show home category chips", "show_home_category_chips", listOf("home chips", "category chips", "home category", "chips")) { SearchResultSwitch(ShowHomeCategoryChipsKey, false) },
            SettingsChild("Language", "app_language", listOf("language", "app language", "locale")),
            SettingsChild("UI scale", "ui_scale", listOf("ui scale", "scale", "zoom", "interface size", "display size", "bigger", "smaller")),
            SettingsChild("Custom font", "custom_font", listOf("custom font", "font file", "typeface", "own font")),
            SettingsChild("Backdrop blur amount", "backdrop_blur_amount", listOf("backdrop blur amount", "backdrop intensity", "background blur amount")),
            SettingsChild("Customized background", "customized_background", listOf("customized background", "custom background", "background image", "wallpaper")),
            SettingsChild("Tablet mode", "tablet_mode", listOf("tablet mode", "tablet", "large screen", "landscape layout")),
            SettingsChild("Minimal mode", "minimal_home_mode", listOf("minimal mode", "minimal home", "simple home", "clean home")),
            SettingsChild("Home screen style", "home_screen_style", listOf("home style", "home screen", "youtube home", "home layout")),
            SettingsChild("Change default library chip", "default_lib_chips", listOf("library chip", "default chip", "library filter", "default library tab")),
            SettingsChild("Liquid Glass effects", "liquid_glass_effects", listOf("liquid glass", "glass effects", "header glass", "mini player glass")),
            SettingsChild("Theme creator", "theme_creator", listOf("theme creator", "create theme", "custom theme", "make theme")),
            SettingsChild("Palette picker", "palette_picker", listOf("palette picker", "pick palette", "choose palette", "custom palette")),
            SettingsChild("Extras", "extras", listOf("extras", "appearance extras", "home cards", "hide cards", "more appearance")),
        ) + independentAppleMusicSearchChildren(context),
    )

// Appearance → Extras sub-page. Hidden from the main list (it is reached through
// Appearance) but every toggle on it stays searchable.
internal fun appearanceExtrasSettingsItem(
    navController: NavController,
    colorScheme: ColorScheme,
): SettingsItem =
    SettingsItem(
        key = "appearance_extras",
        icon = R.drawable.palette,
        title = "Appearance extras",
        subtitle = "Home and library card visibility",
        accentColor = colorScheme.secondary,
        keywords = listOf("extras", "appearance extras", "home cards", "hide cards", "library cards", "quick picks cards"),
        onClick = { navController.navigate("settings/appearance/extras") },
        hidden = true,
        children = listOf(
            SettingsChild("Show home category chips", "show_home_category_chips", listOf("home chips", "category chips", "home category", "chips")) { SearchResultSwitch(ShowHomeCategoryChipsKey, false) },
            SettingsChild("Show tags in library", "show_tags_in_library", listOf("tags", "library tags", "show tags")),
            SettingsChild("Hide Liked songs card", "hide_liked_songs_card", listOf("hide liked songs", "liked songs card", "favourites card", "hide card")),
            SettingsChild("Hide Offline card", "hide_offline_card", listOf("hide offline", "offline card", "downloaded card", "hide card")),
            SettingsChild("Hide Cached card", "hide_cached_card", listOf("hide cached", "cached card", "cache card", "hide card")),
            SettingsChild("Hide Local Files card", "hide_local_files_card", listOf("hide local files", "local files card", "local card", "hide card")),
            SettingsChild("Hide My top 50 card", "hide_top50_card", listOf("hide top 50", "top 50 card", "my top 50", "hide card")),
        ),
    )

// Appearance → AOD customization. Entirely absent from the search index before,
// so none of these 17 settings could be found by name.
internal fun aodCustomizationSettingsItem(
    navController: NavController,
    colorScheme: ColorScheme,
): SettingsItem =
    SettingsItem(
        key = "aod",
        icon = R.drawable.palette,
        title = "AOD customization",
        subtitle = "Always-on display layout and style",
        accentColor = colorScheme.secondary,
        keywords = listOf("aod", "always on display", "always-on display", "lockscreen", "screensaver", "idle screen", "ambient display"),
        onClick = { navController.navigate("settings/appearance/aod_customized") },
        hidden = true,
        children = listOf(
            SettingsChild("Show thumbnail", "aod_customize_show_thumbnail", listOf("aod thumbnail", "always on display artwork", "aod cover", "aod show thumbnail")),
            SettingsChild("Show artist", "aod_customize_show_artist", listOf("aod artist", "always on display artist", "aod show artist")),
            SettingsChild("Show album", "aod_customize_show_album", listOf("aod album", "always on display album", "aod show album")),
            SettingsChild("Show progress", "aod_customize_show_progress", listOf("aod progress", "aod progress bar", "always on display progress")),
            SettingsChild("Show time labels", "aod_customize_show_time_labels", listOf("aod time", "aod timestamps", "aod time labels", "always on display time")),
            SettingsChild("Show controls", "aod_customize_show_controls", listOf("aod controls", "aod buttons", "always on display controls")),
            SettingsChild("Show exit button", "aod_customize_show_exit_button", listOf("aod exit", "aod close button", "leave aod")),
            SettingsChild("Show lyrics", "aod_customize_show_lyrics", listOf("aod lyrics", "always on display lyrics", "aod show lyrics")),
            SettingsChild("Background style", "aod_customize_background_style", listOf("aod background", "aod background style", "always on display background")),
            SettingsChild("Accent style", "aod_customize_accent_style", listOf("aod accent", "aod accent style", "aod color")),
            SettingsChild("Content position", "aod_customize_content_position", listOf("aod position", "aod content position", "aod layout")),
            SettingsChild("Text alignment", "aod_customize_text_alignment", listOf("aod text alignment", "aod align", "aod centre", "aod center")),
            SettingsChild("Slider style", "aod_customize_slider_style", listOf("aod slider", "aod slider style", "aod progress style")),
            SettingsChild("Artwork glow", "aod_customize_artwork_glow", listOf("aod glow", "artwork glow", "aod artwork glow", "ambient glow")),
            SettingsChild("Control style", "aod_customize_control_style", listOf("aod control style", "aod button style")),
            SettingsChild("Enter AOD when screen dims", "aod_customize_auto_on_screen_dim", listOf("auto aod", "aod on dim", "automatic aod", "screen dim aod")),
        ),
    )

// Appearance → Navigation bar customization.
internal fun navigationBarSettingsItem(
    navController: NavController,
    colorScheme: ColorScheme,
): SettingsItem =
    SettingsItem(
        key = "navigation_bar",
        icon = R.drawable.palette,
        title = "Navigation bar",
        subtitle = "Navigation bar style and dimensions",
        accentColor = colorScheme.secondary,
        keywords = listOf("navigation bar", "nav bar", "bottom bar", "tab bar", "navbar"),
        onClick = { navController.navigate("settings/appearance/navigation_bar") },
        hidden = true,
        children = listOf(
            SettingsChild("Navigation bar style", "navigation_bar_style", listOf("navigation bar style", "nav bar style", "bottom bar style", "sukisu", "liquid glass bar")),
            SettingsChild("Bottom pills layout", "compact_bottom_pills", listOf("compact pills", "compact navigation", "compact bottom bar", "bottom pills layout")),
            SettingsChild("Mini player and bar behavior", "navigation_bar_compact_behavior", listOf("compact", "mini player", "collapse", "adaptive", "scroll", "always expanded", "always compact")),
            SettingsChild("Frosted navigation bar", "navigation_bar_frosted_blur", listOf("frosted nav", "frosted navigation", "frosted blur"), restartPending = { it.navigationBarGlass }, blurOverridden = true) { SearchResultSwitch(NavigationBarFrostedBlurKey, false, blurOverridden = true) },
            SettingsChild("Tint frosted navigation bar", "navigation_bar_tint_frosted_blur", listOf("tint frosted", "tint nav bar", "frosted tint", "coloured nav bar")),
            SettingsChild("Liquid Glass navigation bar", "liquid_glass_nav_bar", listOf("liquid glass nav", "glass navigation", "liquid nav"), restartPending = { it.navigationBarGlass }, blurOverridden = true) { SearchResultSwitch(LiquidGlassNavBarEnabledKey, false, blurOverridden = true) },
            SettingsChild("Glass glow", "navigation_bar_glass_glow", listOf("glow", "rim light", "sheen", "glass glow", "glow intensity")),
            SettingsChild("Stock colours instead of Liquid Glass", "compact_controls_stock_color", listOf("stock color", "stock colour", "solid pills", "glass pills", "compact buttons", "bottom buttons", "solid", "no glass", "replace liquid glass", "liquid glass alternative")) { SearchResultSwitch(CompactControlsStockColorKey, false) },
            SettingsChild("Hide labels in navigation bar", "hide_navigation_bar_labels", listOf("hide labels", "navigation labels", "nav labels", "icons only")) { SearchResultSwitch(HideNavigationBarLabelsKey, false) },
            SettingsChild("Hide navigation bar while scrolling", "navigation_bar_hide_on_scroll", listOf("hide navigation bar", "hide nav bar", "scroll to hide", "auto hide bar")) { SearchResultSwitch(NavigationBarHideOnScrollKey, false) },
            SettingsChild("Navigation bar dimensions", "navigation_bar_dimensions", listOf("nav bar height", "nav bar width", "nav bar opacity", "nav bar corner radius", "nav bar label spacing", "nav bar size")),
        ),
    )

// Appearance → Lyrics animations.
internal fun lyricsAnimationsSettingsItem(
    navController: NavController,
    colorScheme: ColorScheme,
): SettingsItem =
    SettingsItem(
        key = "lyrics_animations",
        icon = R.drawable.lyrics,
        title = "Lyrics animations",
        subtitle = "Lyrics motion and transitions",
        accentColor = colorScheme.secondary,
        keywords = listOf("lyrics animation", "lyrics animations", "lyrics motion", "lyrics transition", "karaoke animation"),
        onClick = { navController.navigate("settings/appearance/lyrics_animations") },
        hidden = true,
        children = listOf(
            SettingsChild("Lyrics animation style", "lyrics_animation_style", listOf("lyrics animation style", "lyrics motion", "lyrics transition")),
            SettingsChild("Lyrics scale animation", "lyrics_scale_animation", listOf("lyrics scale", "lyrics zoom", "lyrics grow")),
            SettingsChild("Lyrics glow animation", "lyrics_glow_animation", listOf("lyrics glow", "lyrics shine", "lyrics highlight")),
            SettingsChild("Lyrics fade animation", "lyrics_fade_animation", listOf("lyrics fade", "lyrics opacity animation")),
        ),
    )

@Composable
private fun SearchPlayerVolumeVisibilitySwitch() {
    val prefs = LocalSettingsPreferenceSnapshot.current
    val (style) = prefs.enumState(PlayerDesignStyleKey, PlayerDesignStyle.Default)
    val (visible, setVisible) = prefs.playerVolumeVisibility(style)
    Switch(checked = visible, onCheckedChange = setVisible, enabled = style.hasVolumeRow)
}
