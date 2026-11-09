package moe.rukamori.archivetune.shared.ui.settings

import moe.rukamori.archivetune.shared.ui.resources.*

/** Android presentation order; regenerate with scripts/sync_phone_settings_reference.py. */
internal val PhonePrivacySettingsReference = PhoneSettingsReference(Res.string.m6_settings_home_behavior, listOf(
    PhoneSettingReference("group:listen_history", Res.string.phone_reference_listen_history, null, PhoneSettingKind.HEADER, setOf("listen_history"), null, setOf(), false),
    PhoneSettingReference("pause_listen_history", Res.string.phone_reference_pause_listen_history, Res.drawable.phone_reference_history, PhoneSettingKind.TOGGLE, setOf("pause_listen_history"), "pauseListenHistory", setOf(), false),
    PhoneSettingReference("clear_listen_history", Res.string.phone_reference_clear_listen_history, Res.drawable.phone_reference_delete_history, PhoneSettingKind.ACTION, setOf("clear_listen_history"), null, setOf(), false),
    PhoneSettingReference("reset_listening_stats", Res.string.phone_reference_reset_listening_stats, Res.drawable.phone_reference_clear_all, PhoneSettingKind.ACTION, setOf("reset_listening_stats"), null, setOf(), false),
    PhoneSettingReference("group:search_history", Res.string.phone_reference_search_history, null, PhoneSettingKind.HEADER, setOf("search_history"), null, setOf(), false),
    PhoneSettingReference("pause_search_history", Res.string.m6_settings_desktop_pause_search, Res.drawable.phone_reference_search_off, PhoneSettingKind.TOGGLE, setOf("pause_search_history"), "pauseSearchHistory", setOf(Res.string.m6_settings_desktop_pause_search), false),
    PhoneSettingReference("clear_search_history", Res.string.phone_reference_clear_search_history, Res.drawable.phone_reference_clear_all, PhoneSettingKind.ACTION, setOf("clear_search_history"), null, setOf(), false),
    PhoneSettingReference("group:misc", Res.string.m6_account_misc_group, null, PhoneSettingKind.HEADER, setOf("misc"), null, setOf(Res.string.m6_account_misc_group), false),
    PhoneSettingReference("low_data_mode", Res.string.m6_low_data, Res.drawable.phone_reference_android_cell, PhoneSettingKind.TOGGLE, setOf("low_data_mode_title", "network_metered"), null, setOf(Res.string.m6_low_data), false),
    PhoneSettingReference("force_high_refresh_rate", Res.string.phone_reference_force_high_refresh_rate, Res.drawable.phone_reference_speed, PhoneSettingKind.TOGGLE, setOf("force_high_refresh_rate"), "forceHighRefreshRate", setOf(), false),
    PhoneSettingReference("haptics", Res.string.phone_reference_haptics, Res.drawable.phone_reference_vibration, PhoneSettingKind.TOGGLE, setOf("haptics"), "enableHapticFeedback", setOf(), false),
    PhoneSettingReference("music_haptics", Res.string.phone_reference_music_haptics, Res.drawable.phone_reference_vibration, PhoneSettingKind.TOGGLE, setOf("music_haptics"), null, setOf(), false),
    PhoneSettingReference("music_haptics_strength", Res.string.phone_reference_music_haptics_strength, Res.drawable.phone_reference_vibration, PhoneSettingKind.ACTION, setOf("music_haptics_strength"), null, setOf(), false),
    PhoneSettingReference("disable_screenshot", Res.string.phone_reference_disable_screenshot, Res.drawable.phone_reference_screenshot, PhoneSettingKind.TOGGLE, setOf("disable_screenshot"), "disableScreenshot", setOf(), false),
    PhoneSettingReference("open_supported_links", Res.string.phone_reference_open_supported_links, Res.drawable.phone_reference_link, PhoneSettingKind.ACTION, setOf("open_supported_links"), null, setOf(), true),
))
