package moe.rukamori.archivetune.shared.ui.settings

object SettingsRoutes {
    const val YTDLP = "settings/player/ytdlp"
}

/** Navigation policy from Android SettingsScreen; does not assert all target pages are ported. */
fun settingsSearchRoute(parentKey: String, scrollKey: String?): String? {
    val owner = crossPageOwners["$parentKey/${scrollKey.orEmpty()}"] ?: parentKey
    val route = routes[owner] ?: return null
    if (owner == "ytdlp") return SettingsRoutes.YTDLP
    if (owner == "developer_options" && scrollKey == "acoustic_quality") return "$route?scrollTo=acoustic_quality"
    val supportsScroll = owner !in setOf(
        "developer_options", "about", "po_token", "account", "logcat", "listen_together", "canvas", "android_auto",
        "crash_logs",
    )
    val target = if (owner == "lyrics" && scrollKey == "use_lyrics_v2") "lyrics_mode" else scrollKey
    return if (!supportsScroll || target.isNullOrBlank()) route else "$route?scrollTo=$target"
}

private val routes = mapOf(
    "account" to "settings/account", "appearance" to "settings/appearance",
    // Appearance is one page; its three section owners keep resolving so indexed rows still land on it.
    "appearance_theme" to "settings/appearance", "appearance_player" to "settings/appearance",
    "appearance_interface" to "settings/appearance", "appearance_extras" to "settings/appearance/extras",
    "aod" to "settings/appearance/aod_customized", "navigation_bar" to "settings/appearance/navigation_bar",
    "liquid_glass" to "settings/appearance/liquid_glass",
    "lyrics_animations" to "settings/appearance/lyrics_animations", "playback" to "settings/player",
    "ytdlp" to SettingsRoutes.YTDLP, "sources" to "settings/sources", "applemusic" to "settings/applemusic",
    "jiosaavn" to "settings/jiosaavn", "amazon" to "settings/amazon", "deezer" to "settings/deezer",
    "lyrics" to "settings/lyrics", "lyrics_providers" to "settings/lyrics/providers",
    "lyrics_romanisation" to "settings/lyrics/romanisation", "content" to "settings/content",
    "behavior" to "settings/privacy", "integration" to "settings/integration",
    "listen_together" to "settings/integrations/listen_together", "internet" to "settings/internet",
    "storage" to "settings/storage", "downloads" to "settings/downloads",
    "backup_restore" to "settings/backup_restore", "developer_options" to "settings/misc",
    "logcat" to "settings/logcat", "about" to "settings/about", "discord" to "settings/discord",
    "discord_experimental" to "settings/discord/experimental", "tidal" to "settings/tidal",
    "qobuz" to "settings/qobuz", "telegram" to "settings/telegram", "lastfm" to "settings/lastfm",
    "crash_logs" to "settings/crash_logs",
    "ai_integration" to "settings/ai_integration", "language_packs" to "settings/language_packs",
    "po_token" to "settings/po_token", "update" to "settings/update", "changelog" to "settings/changelog",
    "spotify_streaming" to "settings/spotify_streaming", "qqmusic" to "settings/qqmusic",
    "canvas" to "settings/canvas", "android_auto" to "settings/android_auto",
)

private val crossPageOwners = buildMap {
    fun own(owner: String, parent: String, vararg keys: String) = keys.forEach { put("$parent/$it", owner) }
    own("discord", "integration",
        "discord_options", "discord_connection", "discord_activity", "discord_images", "activity_status", "platform_status",
        "discord_activity_name", "discord_activity_details", "discord_activity_state", "discord_activity_type",
        "discord_show_when_paused", "large_image", "large_text", "small_image")
    own("discord_experimental", "integration", "discord_experimental")
    own("lastfm", "integration",
        "lastfm_options", "lastfm_scrobbling_config", "enable_scrobbling", "lastfm_now_playing", "lastfm_prefer_yt_thumbnails",
        "scrobble_min_track_duration", "scrobble_delay_percent", "scrobble_delay_minutes", "lastfm_connect_button",
        "lastfm_connect_librefm_button", "lastfm_connect_custom_button")
    own("tidal", "integration", "tidal_account", "tidal_instances")
    own("qobuz", "integration", "qobuz_account", "qobuz_tokens", "qobuz_instances")
    own("telegram", "integration", "telegram_login", "telegram_browse_channels", "telegram_lossless_only", "telegram_logout", "telegram_bots_title")
    own("lyrics_providers", "lyrics",
        "first_lyrics_provider", "set_first_lyrics_provider", "prioritize_word_synced_lyrics", "enable_tidal_lyrics",
        "enable_deezer_lyrics", "enable_musixmatch_experimental", "paxsenix_api_key", "paxsenix_endpoint", "paxsenix_stats",
        "paxsenix_lyrics", "betterlyrics", "betterlyrics_portato", "youlyplus_lyrics", "lrclib", "kugou",
        "unison_lyrics", "simpmusic_lyrics", "megalobiz_lyrics")
    own("lyrics_romanisation", "lyrics", "lyrics_romanize_japanese", "lyrics_romanize_korean", "lyrics_romanize_chinese", "lyrics_romanize_hindi", "lyrics_romanize_other")
    own("lyrics_animations", "lyrics", "lyrics_animations")
    own("appearance_player", "lyrics", "lyrics_background_style")
    own("listen_together", "integration", "listen_together", "listen_together_screen")
    own("discord_experimental", "lyrics", "translate_lyrics", "enable_translator")
    own("sources", "playback",
        "preferred_sources", "auto_choose_playback_client", "player_stream_client", "check_source", "spotify_catalog_source",
        "tidal_enable", "tidal_account_first", "tidal_audio_quality", "tidal_animated_covers", "tidal_manage_instances",
        "qobuz_enable", "qobuz_audio_quality", "qobuz_backup_enable", "qobuz_manage_instances", "deezer_enable",
        "deezer_audio_quality", "jiosaavn_enable", "jiosaavn_audio_quality", "applemusic_enable", "amazon_source_enable",
        "qq_music_enable", "qq_music_account", "youtube_source_enable")
    own("ytdlp", "playback", "ytdlp")
    own("crash_logs", "developer_options", "crash_logs")
    own("liquid_glass", "appearance",
        "glass_intensity", "glass_refraction_height", "glass_refraction_amount", "glass_blur_radius",
        "glass_tint_opacity", "glass_shadow_depth", "glass_depth_3d", "glass_chromatic_aberration",
        "glass_backdrop_vibrancy", "glass_adaptive_luminance", "glass_reset",
        "liquid_glass_general", "liquid_glass_optics", "liquid_glass_quality", "liquid_glass_reset_group")
    own("sources", "deezer", "deezer_enable", "deezer_audio_quality")
    own("qobuz", "sources", "qobuz")
    own("tidal", "sources", "tidal")
    own("appearance_theme", "appearance",
        "interface_style", "dynamic_theme", "random_theme_on_startup", "dark_theme", "pure_black", "color_palette", "color_source",
        "palette_picker", "theme_palette_style", "theme_creator", "app_icon", "disable_animations", "splash_overlay_enabled", "hide_status_bar",
        "ui_scale", "blur_intensity", "disable_blur", "backdrop_blur", "backdrop_blur_amount", "font_preference",
        "use_system_font", "custom_font", "liquid_glass_effects", "native_ios_interface", "wallpaper_permission",
        "apple_music_menus", "apple_music_search_bar", "apple_music_palette")
    own("appearance_player", "appearance",
        "player_design_style", "player_menu_style", "player_background_style", "lyrics_background_style",
        "mini_player_background_style", "player_buttons_style", "player_slider_style", "show_player_volume_bar", "hide_player_thumbnail", "crop_thumbnail_to_square",
        "thumbnail_corner_radius", "customized_background", "album_canvas_enabled", "apple_music_animated_artwork",
        "disable_animated_artwork_on_mobile_data", "simpmusic_lyrics")
    own("appearance_interface", "appearance",
        "home_screen_style", "minimal_home_mode", "default_open_tab", "search_bar_position", "hide_search_bar_while_scrolling",
        "back_animation_style", "tablet_mode", "navigation_bar_style", "navigation_bar_settings", "hide_scrollbar",
        "grid_layout", "default_lib_chips", "extras", "app_language")
    own("navigation_bar", "appearance", "frosted_nav_bar", "liquid_glass_nav_bar", "hide_navigation_bar_labels", "navigation_bar_hide_on_scroll")
    own("appearance_extras", "appearance", "show_home_category_chips")
    own("playback", "appearance", "swipe_sensitivity")
    own("behavior", "appearance", "force_high_refresh_rate")
    own("appearance_extras", "behavior", "show_tags_in_library")
    own("downloads", "storage", "downloaded_songs", "download_location")
}
