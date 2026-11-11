package moe.rukamori.archivetune.shared.ui.settings

import moe.rukamori.archivetune.shared.ui.resources.*

// Android keeps these subpages in the search index and under their parent settings page.
internal val phoneSettingsHomeBrowseHidden = setOf("appearance_extras", "aod", "navigation_bar", "liquid_glass",
    "lyrics_animations", "sources", "jiosaavn", "amazon", "qqmusic", "spotify_streaming", "deezer", "lyrics",
    "lyrics_providers", "lyrics_romanisation", "discord_experimental", "ai_integration", "po_token")

/** The phone settings index keeps the Android group and entry order. */
internal val PhoneSettingsHomeReference = PhoneSettingsReference(Res.string.m4_settings, listOf(
    PhoneSettingReference("group_settings", Res.string.m6_settings_home_group_settings, null, PhoneSettingKind.HEADER),
    PhoneSettingReference("account", Res.string.m6_settings_home_account, Res.drawable.phone_reference_ic_account, PhoneSettingKind.ACTION),
    PhoneSettingReference("stats", Res.string.m6_settings_home_stats, Res.drawable.phone_reference_stats, PhoneSettingKind.ACTION),
    PhoneSettingReference("listen_together", Res.string.m6_settings_home_listen_together, Res.drawable.phone_reference_diversity_listen_together, PhoneSettingKind.ACTION),
    PhoneSettingReference("group_player_content", Res.string.m6_settings_home_group_player_content, null, PhoneSettingKind.HEADER),
    PhoneSettingReference("appearance", Res.string.m6_settings_home_appearance, Res.drawable.phone_reference_ic_palette, PhoneSettingKind.ACTION),
    PhoneSettingReference("appearance_extras", Res.string.m6_settings_home_appearance_extras, Res.drawable.phone_reference_discover_tune, PhoneSettingKind.ACTION),
    PhoneSettingReference("aod", Res.string.m6_settings_home_aod, Res.drawable.phone_reference_bedtime, PhoneSettingKind.ACTION),
    PhoneSettingReference("navigation_bar", Res.string.m6_settings_home_navigation_bar, Res.drawable.phone_reference_nav_bar, PhoneSettingKind.ACTION),
    PhoneSettingReference("liquid_glass", Res.string.m6_liquid_glass, Res.drawable.phone_reference_blur_on, PhoneSettingKind.ACTION),
    PhoneSettingReference("lyrics_animations", Res.string.m6_settings_home_lyrics_animations, Res.drawable.phone_reference_animation, PhoneSettingKind.ACTION),
    PhoneSettingReference("playback", Res.string.m6_settings_home_playback, Res.drawable.phone_reference_music_note, PhoneSettingKind.ACTION),
    PhoneSettingReference("canvas", Res.string.m6_settings_home_canvas, Res.drawable.phone_reference_motion_photos_on, PhoneSettingKind.ACTION),
    PhoneSettingReference("sources", Res.string.m6_settings_home_sources, Res.drawable.phone_reference_provider_tidal, PhoneSettingKind.ACTION),
    PhoneSettingReference("modules", Res.string.source_modules, Res.drawable.phone_reference_extension, PhoneSettingKind.ACTION),
    PhoneSettingReference("jiosaavn", Res.string.m6_settings_home_jiosaavn, Res.drawable.phone_reference_provider_jiosaavn, PhoneSettingKind.ACTION),
    PhoneSettingReference("amazon", Res.string.m6_settings_home_amazon, Res.drawable.phone_reference_cloud, PhoneSettingKind.ACTION),
    PhoneSettingReference("qqmusic", Res.string.m6_settings_home_qqmusic, Res.drawable.phone_reference_music_note, PhoneSettingKind.ACTION),
    PhoneSettingReference("spotify_streaming", Res.string.m6_settings_home_spotify_streaming, Res.drawable.m9_spotify_icon, PhoneSettingKind.ACTION),
    PhoneSettingReference("deezer", Res.string.m6_settings_home_deezer, Res.drawable.phone_reference_provider_deezer, PhoneSettingKind.ACTION),
    PhoneSettingReference("lyrics", Res.string.m6_settings_home_lyrics, Res.drawable.phone_reference_lyrics, PhoneSettingKind.ACTION),
    PhoneSettingReference("lyrics_providers", Res.string.m6_settings_home_lyrics_providers, Res.drawable.phone_reference_lyrics, PhoneSettingKind.ACTION),
    PhoneSettingReference("lyrics_romanisation", Res.string.m6_settings_home_lyrics_romanisation, Res.drawable.phone_reference_translate, PhoneSettingKind.ACTION),
    PhoneSettingReference("content", Res.string.m6_settings_home_content, Res.drawable.phone_reference_language, PhoneSettingKind.ACTION),
    PhoneSettingReference("behavior", Res.string.m6_settings_home_behavior, Res.drawable.phone_reference_settings, PhoneSettingKind.ACTION),
    PhoneSettingReference("group_integration", Res.string.m6_settings_home_group_integration, null, PhoneSettingKind.HEADER),
    PhoneSettingReference("android_auto", Res.string.m6_settings_home_android_auto, Res.drawable.phone_reference_directions_car, PhoneSettingKind.ACTION),
    PhoneSettingReference("integration", Res.string.m6_settings_home_integration, Res.drawable.phone_reference_integration, PhoneSettingKind.ACTION),
    PhoneSettingReference("ai_integration", Res.string.m6_settings_home_ai_integration, Res.drawable.phone_reference_ai, PhoneSettingKind.ACTION),
    PhoneSettingReference("discord_experimental", Res.string.m6_settings_home_discord_experimental, Res.drawable.phone_reference_discord, PhoneSettingKind.ACTION),
    PhoneSettingReference("internet", Res.string.m6_settings_home_internet, Res.drawable.phone_reference_wifi_proxy, PhoneSettingKind.ACTION),
    PhoneSettingReference("po_token", Res.string.m6_settings_home_po_token, Res.drawable.phone_reference_token, PhoneSettingKind.ACTION),
    PhoneSettingReference("group_storage", Res.string.m6_settings_home_group_storage, null, PhoneSettingKind.HEADER),
    PhoneSettingReference("storage", Res.string.m6_settings_home_storage, Res.drawable.phone_reference_storage, PhoneSettingKind.ACTION),
    PhoneSettingReference("downloads", Res.string.m6_settings_home_downloads, Res.drawable.phone_reference_download, PhoneSettingKind.ACTION),
    PhoneSettingReference("language_packs", Res.string.m6_settings_home_language_packs, Res.drawable.phone_reference_download, PhoneSettingKind.ACTION),
    PhoneSettingReference("backup_restore", Res.string.m6_settings_home_backup_restore, Res.drawable.phone_reference_backup, PhoneSettingKind.ACTION),
    PhoneSettingReference("group_about", Res.string.m6_settings_home_group_about, null, PhoneSettingKind.HEADER),
    PhoneSettingReference("developer_options", Res.string.m6_settings_home_developer_options, Res.drawable.phone_reference_experiment, PhoneSettingKind.ACTION),
    PhoneSettingReference("updates", Res.string.m6_settings_home_updates, Res.drawable.phone_reference_ic_refresh, PhoneSettingKind.ACTION),
    PhoneSettingReference("about", Res.string.m6_settings_home_about, Res.drawable.phone_reference_ic_about, PhoneSettingKind.ACTION),
))
