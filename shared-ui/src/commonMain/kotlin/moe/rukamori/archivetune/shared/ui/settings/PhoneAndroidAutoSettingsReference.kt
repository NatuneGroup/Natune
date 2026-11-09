package moe.rukamori.archivetune.shared.ui.settings

import moe.rukamori.archivetune.shared.ui.resources.*

/** Android presentation order; regenerate with scripts/sync_phone_settings_reference.py. */
internal val PhoneAndroidAutoSettingsReference = PhoneSettingsReference(Res.string.m6_android_auto_title, listOf(
    PhoneSettingReference("group:android_auto_connection", Res.string.m6_android_auto_connection, null, PhoneSettingKind.HEADER, setOf("android_auto_connection"), null, setOf(Res.string.m6_android_auto_connection, Res.string.m6_discord_group_connection), false),
    PhoneSettingReference("android_auto", Res.string.m6_android_auto_title, Res.drawable.phone_reference_directions_car, PhoneSettingKind.ACTION, setOf("android_auto"), null, setOf(Res.string.m6_android_auto_title, Res.string.m6_settings_home_android_auto), false),
    PhoneSettingReference("android_auto_app_permissions", Res.string.m6_android_auto_app_permissions, Res.drawable.phone_reference_security, PhoneSettingKind.ACTION, setOf("android_auto_app_permissions"), null, setOf(Res.string.m6_android_auto_app_permissions), false),
    PhoneSettingReference("group:android_auto_content", Res.string.m6_android_auto_content, null, PhoneSettingKind.HEADER, setOf("android_auto_content"), null, setOf(Res.string.m6_android_auto_content, Res.string.m6_settings_home_content), false),
    PhoneSettingReference("android_auto_online_recommendations", Res.string.m6_android_auto_online_recommendations, Res.drawable.phone_reference_discover_tune, PhoneSettingKind.TOGGLE, setOf("android_auto_online_recommendations"), null, setOf(Res.string.m6_android_auto_online_recommendations), false),
    PhoneSettingReference("android_auto_online_voice_search", Res.string.m6_android_auto_online_voice_search, Res.drawable.phone_reference_mic, PhoneSettingKind.TOGGLE, setOf("android_auto_online_voice_search"), null, setOf(Res.string.m6_android_auto_online_voice_search), false),
    PhoneSettingReference("android_auto_local_songs", Res.string.m6_android_auto_local_songs, Res.drawable.phone_reference_library_music, PhoneSettingKind.TOGGLE, setOf("android_auto_local_songs"), null, setOf(Res.string.m6_android_auto_local_songs), false),
    PhoneSettingReference("group:android_auto_data", Res.string.m6_android_auto_data, null, PhoneSettingKind.HEADER, setOf("android_auto_data"), null, setOf(Res.string.m6_android_auto_data), false),
    PhoneSettingReference("android_auto_metered_playback", Res.string.m6_android_auto_metered_playback, Res.drawable.phone_reference_android_cell, PhoneSettingKind.TOGGLE, setOf("android_auto_metered_playback"), null, setOf(Res.string.m6_android_auto_metered_playback), false),
    PhoneSettingReference("android_auto_metered_artwork", Res.string.m6_android_auto_metered_artwork, Res.drawable.phone_reference_image, PhoneSettingKind.TOGGLE, setOf("android_auto_metered_artwork"), null, setOf(Res.string.m6_android_auto_metered_artwork), false),
    PhoneSettingReference("group:android_auto_driving_playlists", Res.string.phone_reference_android_auto_driving_playlists, null, PhoneSettingKind.HEADER, setOf("android_auto_driving_playlists"), null, setOf(), false),
    PhoneSettingReference("android_auto_choose_pinned_playlists", Res.string.m4_choose_playlists, Res.drawable.phone_reference_push_pin, PhoneSettingKind.ACTION, setOf("android_auto_choose_pinned_playlists"), null, setOf(Res.string.m4_choose_playlists), false),
    PhoneSettingReference("android_auto_pinned_play_in_order", Res.string.phone_reference_android_auto_pinned_play_in_order, Res.drawable.phone_reference_playlist_play, PhoneSettingKind.TOGGLE, setOf("android_auto_pinned_play_in_order"), null, setOf(), false),
    PhoneSettingReference("group:android_auto_controls", Res.string.m6_android_auto_controls, null, PhoneSettingKind.HEADER, setOf("android_auto_controls"), null, setOf(Res.string.m6_android_auto_controls), false),
    PhoneSettingReference("android_auto_primary_action", Res.string.m6_android_auto_primary_action, null, PhoneSettingKind.VALUE, setOf("android_auto_primary_action"), null, setOf(Res.string.m6_android_auto_primary_action), false),
    PhoneSettingReference("android_auto_secondary_action", Res.string.m6_android_auto_secondary_action, null, PhoneSettingKind.VALUE, setOf("android_auto_secondary_action"), null, setOf(Res.string.m6_android_auto_secondary_action), false),
))
