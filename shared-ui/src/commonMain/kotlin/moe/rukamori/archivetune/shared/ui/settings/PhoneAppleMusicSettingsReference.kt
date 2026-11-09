package moe.rukamori.archivetune.shared.ui.settings

import moe.rukamori.archivetune.shared.ui.resources.*

/** Android presentation order; regenerate with scripts/sync_phone_settings_reference.py. */
internal val PhoneAppleMusicSettingsReference = PhoneSettingsReference(Res.string.ios_lane_nav_style_apple_music, listOf(
    PhoneSettingReference("group:applemusic_settings", Res.string.ios_lane_nav_style_apple_music, null, PhoneSettingKind.HEADER, setOf("applemusic_settings"), null, setOf(Res.string.ios_lane_nav_style_apple_music, Res.string.m5pq_source_apple_music, Res.string.m6_canvas_apple_music, Res.string.m6_dev_source_apple, Res.string.m6_integration_applemusic, Res.string.m6_apple_music), false),
    PhoneSettingReference("applemusic_sign_in_web", Res.string.phone_reference_applemusic_sign_in_web, Res.drawable.phone_reference_language, PhoneSettingKind.ACTION, setOf("applemusic_sign_in_web"), null, setOf(), false),
    PhoneSettingReference("applemusic_tokens_title", Res.string.phone_reference_applemusic_tokens_title, Res.drawable.phone_reference_token, PhoneSettingKind.ACTION, setOf("applemusic_tokens_title"), null, setOf(), false),
    PhoneSettingReference("applemusic_disconnect", Res.string.m6_telegram_logout, Res.drawable.phone_reference_close, PhoneSettingKind.ACTION, setOf("applemusic_disconnect"), null, setOf(Res.string.m6_telegram_logout, Res.string.m6_apple_music_sign_out), true),
))
