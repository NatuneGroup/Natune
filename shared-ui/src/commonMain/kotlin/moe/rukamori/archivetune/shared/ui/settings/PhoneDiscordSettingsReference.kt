package moe.rukamori.archivetune.shared.ui.settings

import moe.rukamori.archivetune.shared.ui.resources.*

/** Android presentation order; regenerate with scripts/sync_phone_settings_reference.py. */
internal val PhoneDiscordSettingsReference = PhoneSettingsReference(Res.string.m6_dev_discord_integration, listOf(
    PhoneSettingReference("group:account", Res.string.m4_account, null, PhoneSettingKind.HEADER, setOf("account"), null, setOf(Res.string.m4_account, Res.string.m4hv_account, Res.string.m6_account_title, Res.string.m6_deezer_account, Res.string.m6_discord_group_account, Res.string.m6_profile_account, Res.string.m6_qobuz_account_row_title, Res.string.m6_qobuz_field_account, Res.string.m6_settings_home_account, Res.string.m6_telegram_account_group, Res.string.m6_tidal_field_account), false),
    PhoneSettingReference("group:options", Res.string.m11_options, null, PhoneSettingKind.HEADER, setOf("options"), null, setOf(Res.string.m11_options, Res.string.m6_discord_group_options), false),
    PhoneSettingReference("refresh", Res.string.m4_refresh, Res.drawable.phone_reference_update, PhoneSettingKind.ACTION, setOf("refresh"), null, setOf(Res.string.m4_refresh, Res.string.m6_po_token_helper_refresh, Res.string.m6_refresh), false),
    PhoneSettingReference("group:discord_connection_settings", Res.string.m6_android_auto_connection, null, PhoneSettingKind.HEADER, setOf("discord_connection_settings"), null, setOf(Res.string.m6_android_auto_connection, Res.string.m6_discord_group_connection), false),
    PhoneSettingReference("activity_status", Res.string.m6_discord_activity_status, Res.drawable.phone_reference_status, PhoneSettingKind.VALUE, setOf("activity_status"), "discordPresenceStatus", setOf(Res.string.m6_discord_activity_status), false),
    PhoneSettingReference("platform_status", Res.string.m6_discord_platform, Res.drawable.phone_reference_desktop_windows, PhoneSettingKind.VALUE, setOf("platform_status"), "discordActivityPlatform", setOf(Res.string.m6_discord_platform), false),
    PhoneSettingReference("group:discord_activity_content", Res.string.m6_discord_group_activity, null, PhoneSettingKind.HEADER, setOf("discord_activity_content"), null, setOf(Res.string.m6_discord_group_activity), false),
    PhoneSettingReference("discord_activity_name", Res.string.m6_discord_activity_name, Res.drawable.phone_reference_text_fields, PhoneSettingKind.VALUE, setOf("discord_activity_name"), "discordActivityName", setOf(Res.string.m6_discord_activity_name), false),
    PhoneSettingReference("discord_activity_details", Res.string.m6_discord_activity_details, Res.drawable.phone_reference_text_fields, PhoneSettingKind.VALUE, setOf("discord_activity_details"), "discordActivityDetails", setOf(Res.string.m6_discord_activity_details), false),
    PhoneSettingReference("discord_activity_state", Res.string.m6_discord_activity_state, Res.drawable.phone_reference_text_fields, PhoneSettingKind.VALUE, setOf("discord_activity_state"), "discordActivityState", setOf(Res.string.m6_discord_activity_state), false),
    PhoneSettingReference("discord_show_when_paused", Res.string.m6_discord_show_when_paused, Res.drawable.phone_reference_ic_pause_white, PhoneSettingKind.TOGGLE, setOf("discord_show_when_paused"), null, setOf(Res.string.m6_discord_show_when_paused), false),
    PhoneSettingReference("discord_activity_type", Res.string.m6_discord_activity_type, Res.drawable.phone_reference_discord, PhoneSettingKind.VALUE, setOf("discord_activity_type"), "discordActivityType", setOf(Res.string.m6_discord_activity_type), false),
    PhoneSettingReference("group:discord_image_options", Res.string.m6_discord_group_images, null, PhoneSettingKind.HEADER, setOf("discord_image_options"), null, setOf(Res.string.m6_discord_group_images), false),
    PhoneSettingReference("large_image", Res.string.m6_discord_large_image, Res.drawable.phone_reference_image, PhoneSettingKind.VALUE, setOf("large_image"), "discordLargeImageType", setOf(Res.string.m6_discord_large_image), false),
    PhoneSettingReference("large_text", Res.string.m6_discord_large_text, Res.drawable.phone_reference_text_fields, PhoneSettingKind.VALUE, setOf("large_text"), "discordLargeTextSource", setOf(Res.string.m6_discord_large_text), false),
    PhoneSettingReference("small_image", Res.string.m6_discord_small_image, Res.drawable.phone_reference_image, PhoneSettingKind.VALUE, setOf("small_image"), "discordSmallImageType", setOf(Res.string.m6_discord_small_image), false),
    PhoneSettingReference("preview", Res.string.m6_preview, null, PhoneSettingKind.ACTION, setOf("preview"), null, setOf(Res.string.m6_preview), false),
))
