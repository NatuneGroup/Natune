package moe.rukamori.archivetune.shared.ui.settings

import moe.rukamori.archivetune.shared.ui.resources.*

/** Android presentation order; regenerate with scripts/sync_phone_settings_reference.py. */
internal val PhoneDebugSettingsReference = PhoneSettingsReference(Res.string.m6_account_misc_group, listOf(
    PhoneSettingReference("group:experimental_features", Res.string.phone_reference_experimental_features, null, PhoneSettingKind.HEADER, setOf("experimental_features"), null, setOf(), false),
    PhoneSettingReference("show_discord_debug_ui", Res.string.m6_dev_show_discord_debug, Res.drawable.phone_reference_discord, PhoneSettingKind.TOGGLE, setOf("show_discord_debug_ui"), null, setOf(Res.string.m6_dev_show_discord_debug), false),
    PhoneSettingReference("show_nerd_stats", Res.string.m6_dev_show_nerd_stats, Res.drawable.phone_reference_stats, PhoneSettingKind.TOGGLE, setOf("show_nerd_stats"), null, setOf(Res.string.m6_dev_show_nerd_stats), false),
    PhoneSettingReference("debug_logs", Res.string.m11_debug_logs, Res.drawable.phone_reference_manage_search, PhoneSettingKind.ACTION, setOf("debug_logs"), null, setOf(Res.string.m11_debug_logs, Res.string.m6_dev_debug_logs), false),
    PhoneSettingReference("crash_logs_title", Res.string.m6_crash_logs_title, Res.drawable.phone_reference_bug_report, PhoneSettingKind.ACTION, setOf("crash_logs_title"), null, setOf(Res.string.m6_crash_logs_title), false),
    PhoneSettingReference("acoustic_quality_title", Res.string.m6aq_title, Res.drawable.phone_reference_graphic_eq, PhoneSettingKind.ACTION, setOf("acoustic_quality_title", "acoustic_quality"), null, setOf(Res.string.m6aq_title, Res.string.m5pq_acoustic_quality_title, Res.string.m6_dev_acoustic_quality), false),
))
