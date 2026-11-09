package moe.rukamori.archivetune.shared.ui.settings

import moe.rukamori.archivetune.shared.ui.resources.*

/** Android presentation order; regenerate with scripts/sync_phone_settings_reference.py. */
internal val PhoneDiscordExperimentalReference = PhoneSettingsReference(Res.string.m6_discord_experiment_settings, listOf(
    PhoneSettingReference("group:discord_button_options", Res.string.m6_discord_group_buttons, null, PhoneSettingKind.HEADER, setOf("discord_button_options"), null, setOf(Res.string.m6_discord_group_buttons), false),
    PhoneSettingReference("discord_show_button_1", Res.string.m6_discord_show_button, Res.drawable.phone_reference_buttons, PhoneSettingKind.TOGGLE, setOf("show_button"), "discordActivityButton1Enabled", setOf(Res.string.m6_discord_show_button), false),
    PhoneSettingReference("discord_activity_button_1_url", Res.string.m6_discord_button1_url_source, Res.drawable.phone_reference_link, PhoneSettingKind.VALUE, setOf("discord_activity_button_1_url"), "discordActivityButton1UrlSource", setOf(Res.string.m6_discord_button1_url_source), true),
    PhoneSettingReference("discord_show_button_2", Res.string.m6_discord_show_button, Res.drawable.phone_reference_buttons, PhoneSettingKind.TOGGLE, setOf("show_button"), "discordActivityButton2Enabled", setOf(Res.string.m6_discord_show_button), false),
    PhoneSettingReference("discord_activity_button_2_url", Res.string.m6_discord_button2_url_source, Res.drawable.phone_reference_link, PhoneSettingKind.VALUE, setOf("discord_activity_button_2_url"), "discordActivityButton2UrlSource", setOf(Res.string.m6_discord_button2_url_source), true),
))
