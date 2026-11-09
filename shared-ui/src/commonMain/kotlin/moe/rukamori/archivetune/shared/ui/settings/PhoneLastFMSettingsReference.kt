package moe.rukamori.archivetune.shared.ui.settings

import moe.rukamori.archivetune.shared.ui.resources.*

/** Android presentation order; regenerate with scripts/sync_phone_settings_reference.py. */
internal val PhoneLastFMSettingsReference = PhoneSettingsReference(Res.string.m6_integration_lastfm, listOf(
    PhoneSettingReference("group:account", Res.string.m4_account, null, PhoneSettingKind.HEADER, setOf("account"), null, setOf(Res.string.m4_account, Res.string.m4hv_account, Res.string.m6_account_title, Res.string.m6_deezer_account, Res.string.m6_discord_group_account, Res.string.m6_profile_account, Res.string.m6_qobuz_account_row_title, Res.string.m6_qobuz_field_account, Res.string.m6_settings_home_account, Res.string.m6_telegram_account_group, Res.string.m6_tidal_field_account), false),
    PhoneSettingReference("lastfm_connect_button", Res.string.m6_lastfm_connect_button, Res.drawable.phone_reference_login, PhoneSettingKind.ACTION, setOf("lastfm_connect_button"), null, setOf(Res.string.m6_lastfm_connect_button), false),
    PhoneSettingReference("lastfm_connect_librefm_button", Res.string.m6_lastfm_connect_librefm_button, Res.drawable.phone_reference_login, PhoneSettingKind.ACTION, setOf("lastfm_connect_librefm_button"), null, setOf(Res.string.m6_lastfm_connect_librefm_button), false),
    PhoneSettingReference("lastfm_connect_custom_button", Res.string.m6_lastfm_connect_custom_button, Res.drawable.phone_reference_token, PhoneSettingKind.ACTION, setOf("lastfm_connect_custom_button"), null, setOf(Res.string.m6_lastfm_connect_custom_button, Res.string.m6_lastfm_custom_endpoint), false),
    PhoneSettingReference("group:options", Res.string.m11_options, null, PhoneSettingKind.HEADER, setOf("options"), null, setOf(Res.string.m11_options, Res.string.m6_discord_group_options), false),
    PhoneSettingReference("enable_scrobbling", Res.string.m6_enable_scrobbling, null, PhoneSettingKind.TOGGLE, setOf("enable_scrobbling"), null, setOf(Res.string.m6_enable_scrobbling), false),
    PhoneSettingReference("lastfm_now_playing", Res.string.m4_now_playing, null, PhoneSettingKind.TOGGLE, setOf("lastfm_now_playing"), null, setOf(Res.string.m4_now_playing, Res.string.m4c_now_playing, Res.string.m5_now_playing, Res.string.m5pq_now_playing, Res.string.m6_lastfm_now_playing), false),
    PhoneSettingReference("lastfm_send_loves", Res.string.m6_lastfm_send_loves, null, PhoneSettingKind.TOGGLE, setOf("lastfm_send_loves"), null, setOf(Res.string.m6_lastfm_send_loves), false),
    PhoneSettingReference("lastfm_pending_scrobbles", Res.string.m6_lastfm_pending_scrobbles_title, null, PhoneSettingKind.ACTION, setOf("lastfm_pending_scrobbles_title"), null, setOf(Res.string.m6_lastfm_pending_scrobbles_title), true),
    PhoneSettingReference("lastfm_prefer_yt_thumbnails", Res.string.m6_lastfm_prefer_yt_thumbnails, null, PhoneSettingKind.TOGGLE, setOf("lastfm_prefer_yt_thumbnails"), null, setOf(Res.string.m6_lastfm_prefer_yt_thumbnails), false),
    PhoneSettingReference("group:scrobbling_configuration", Res.string.phone_reference_scrobbling_configuration, null, PhoneSettingKind.HEADER, setOf("scrobbling_configuration"), null, setOf(), false),
    PhoneSettingReference("scrobble_min_track_duration", Res.string.m6_scrobble_min_track_duration, null, PhoneSettingKind.ACTION, setOf("scrobble_min_track_duration"), null, setOf(Res.string.m6_scrobble_min_track_duration), false),
    PhoneSettingReference("scrobble_delay_percent", Res.string.m6_scrobble_delay_percent, null, PhoneSettingKind.ACTION, setOf("scrobble_delay_percent"), null, setOf(Res.string.m6_scrobble_delay_percent), false),
    PhoneSettingReference("scrobble_delay_minutes", Res.string.m6_scrobble_delay_minutes, null, PhoneSettingKind.ACTION, setOf("scrobble_delay_minutes"), null, setOf(Res.string.m6_scrobble_delay_minutes), false),
))
