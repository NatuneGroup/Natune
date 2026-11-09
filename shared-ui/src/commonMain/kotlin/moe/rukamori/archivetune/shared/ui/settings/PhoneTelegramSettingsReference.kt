package moe.rukamori.archivetune.shared.ui.settings

import moe.rukamori.archivetune.shared.ui.resources.*

/** Android presentation order; regenerate with scripts/sync_phone_settings_reference.py. */
internal val PhoneTelegramSettingsReference = PhoneSettingsReference(Res.string.m4_telegram, listOf(
    PhoneSettingReference("group:telegram_account", Res.string.m4_account, null, PhoneSettingKind.HEADER, setOf("telegram_account"), null, setOf(Res.string.m4_account, Res.string.m4hv_account, Res.string.m6_account_title, Res.string.m6_deezer_account, Res.string.m6_discord_group_account, Res.string.m6_profile_account, Res.string.m6_qobuz_account_row_title, Res.string.m6_qobuz_field_account, Res.string.m6_settings_home_account, Res.string.m6_telegram_account_group, Res.string.m6_tidal_field_account), false),
    PhoneSettingReference("telegram_logged_in_as", Res.string.ios_services_lastfm_status_signed_in, Res.drawable.phone_reference_provider_telegram, PhoneSettingKind.ACTION, setOf("telegram_logged_in_as"), null, setOf(Res.string.ios_services_lastfm_status_signed_in, Res.string.m6_provider_sign_in_signed_in, Res.string.m6_qq_signed_in, Res.string.m6_settings_notice_deezer_signed_in), true),
    PhoneSettingReference("telegram_logout", Res.string.m6_telegram_logout, Res.drawable.phone_reference_logout, PhoneSettingKind.ACTION, setOf("telegram_logout"), null, setOf(Res.string.m6_telegram_logout, Res.string.m6_apple_music_sign_out), true),
    PhoneSettingReference("telegram_login", Res.string.m6_telegram_login, Res.drawable.phone_reference_provider_telegram, PhoneSettingKind.ACTION, setOf("telegram_login"), null, setOf(Res.string.m6_telegram_login), false),
    PhoneSettingReference("group:telegram_browse_channels", Res.string.m4_telegram_browse_title, null, PhoneSettingKind.HEADER, setOf("telegram_browse_channels"), null, setOf(Res.string.m4_telegram_browse_title, Res.string.m6_telegram_browse_group, Res.string.m6_telegram_browse_channels), false),
    PhoneSettingReference("telegram_browse_channels", Res.string.m4_telegram_browse_title, Res.drawable.phone_reference_search, PhoneSettingKind.ACTION, setOf("telegram_browse_channels"), null, setOf(Res.string.m4_telegram_browse_title, Res.string.m6_telegram_browse_group, Res.string.m6_telegram_browse_channels), false),
    PhoneSettingReference("telegram_lossless_only", Res.string.m6_telegram_lossless_only, Res.drawable.phone_reference_graphic_eq, PhoneSettingKind.TOGGLE, setOf("telegram_lossless_only"), "telegramLosslessOnly", setOf(Res.string.m6_telegram_lossless_only), false),
    PhoneSettingReference("group:telegram_bots_title", Res.string.m4_telegram_bots_title, null, PhoneSettingKind.HEADER, setOf("telegram_bots_title"), null, setOf(Res.string.m4_telegram_bots_title, Res.string.m6_telegram_bots_group, Res.string.m6_telegram_bots_title), false),
    PhoneSettingReference("telegram_bots_title", Res.string.m4_telegram_bots_title, Res.drawable.phone_reference_provider_telegram, PhoneSettingKind.ACTION, setOf("telegram_bots_title"), null, setOf(Res.string.m4_telegram_bots_title, Res.string.m6_telegram_bots_group, Res.string.m6_telegram_bots_title), false),
))
