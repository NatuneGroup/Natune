package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Settings anchors whose only consumers live in the Android host. Shared pages are mounted on iOS as well, where a
 * control for such a key would write a preference nothing reads; a host declares this set and [SettingsPage] omits
 * those rows instead of showing a no-op. The stored values stay readable for the Android host and for backups.
 *
 * Hosts that own the reader (Android, and desktop where the desktop worker still wires these) keep the default empty
 * set, so only a host choosing to opt in changes its page.
 */
object AndroidOnlySettingsAnchors {
    val appearance: Set<String> = setOf(
        // Theme rows with no shared/iOS reader.
        "apple_music_palette", "random_theme_on_startup", "pure_black", "splash_overlay_enabled",
        "hide_status_bar", "ui_scale", "blur_intensity", "backdrop_blur", "backdrop_blur_amount",
        // Home & library rows with no shared/iOS reader.
        "home_screen_style", "minimal_home_mode", "back_animation_style", "hide_scrollbar",
        // Player-appearance rows with no shared/iOS reader.
        "library_style", "compact_mini_player", "crop_thumbnail_to_square", "album_canvas_enabled",
        "metrolist_presentation", "tiktok_main_lyrics", "apple_music_animated_artwork",
        "apple_music_backdrop_wander", "apple_music_pause_scale", "apple_music_title_marquee",
        "apple_music_seek_grow",
        // Entries for pages that only exist on the Android host.
        "aod_customize", "aod", "discord_experimental",
        // Extras row read only by the Android library.
        "show_tags_in_library",
    )

    val playback: Set<String> = setOf(
        "enable_pip_mode", "skip_silence", "automix_performance", "reject_suspected_upscaled_audio",
        "audio_offload", "bluetooth_auto_start", "tidal_artwork_fallback", "persistent_queue",
        "permanent_shuffle", "swipe_up_queue", "swipe_to_song", "stop_task_clear", "wakelock",
    )

    val lyrics: Set<String> = emptySet()

    val integration: Set<String> = setOf(
        "listenbrainz", "listenbrainz_token",
        "platform_status", "large_text", "custom_large_text",
    )

    val developer: Set<String> = setOf("dev_show_discord_debug", "dev_show_nerd_stats", "show_codec_on_player")

    val downloads: Set<String> = setOf(
        "auto_download_like", "external_downloader", "external_downloader_package",
    )

    val account: Set<String> = setOf("account_force_sync")

    val updates: Set<String> = setOf("enable_update_notification", "persistent_update_popup")

    val all: Set<String> =
        appearance + playback + lyrics + integration + developer + downloads + account + updates
}

/** The rows this host can apply; an empty policy keeps every row for hosts that own all the readers. */
fun List<SettingsRow>.withoutUnsupportedRows(hiddenAnchors: Set<String>): List<SettingsRow> =
    if (hiddenAnchors.isEmpty()) this else filterNot { it.anchor in hiddenAnchors }

/**
 * The anchors the iOS host cannot apply. The shared pages stay mounted, but the rows whose readers are Android-only
 * are left out until the shared layer implements a consumer. The value is empty during tests that render the page
 * without a host policy.
 */
val LocalUnsupportedSettingsAnchors = staticCompositionLocalOf<Set<String>> { emptySet() }
