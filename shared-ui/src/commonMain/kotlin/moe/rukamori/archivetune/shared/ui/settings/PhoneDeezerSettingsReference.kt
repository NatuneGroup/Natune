package moe.rukamori.archivetune.shared.ui.settings

import moe.rukamori.archivetune.shared.ui.resources.*

/** Android presentation order; regenerate with scripts/sync_phone_settings_reference.py. */
internal val PhoneDeezerSettingsReference = PhoneSettingsReference(Res.string.m5pq_source_deezer, listOf(
    PhoneSettingReference("group:deezer_integration", Res.string.m5pq_source_deezer, null, PhoneSettingKind.HEADER, setOf("deezer_integration"), null, setOf(Res.string.m5pq_source_deezer, Res.string.m6_dev_source_deezer, Res.string.m6_integration_deezer, Res.string.m6_settings_home_deezer, Res.string.m6_source_deezer, Res.string.m6_deezer), false),
    PhoneSettingReference("deezer_login", Res.string.m6_deezer_login, Res.drawable.phone_reference_provider_deezer, PhoneSettingKind.ACTION, setOf("deezer_login"), null, setOf(Res.string.m6_deezer_login, Res.string.m6_provider_sign_in_deezer_title), true),
    PhoneSettingReference("deezer_arl_login", Res.string.m6_deezer_arl_login, Res.drawable.phone_reference_key, PhoneSettingKind.ACTION, setOf("deezer_arl_login"), null, setOf(Res.string.m6_deezer_arl_login), true),
    PhoneSettingReference("deezer_sign_out", Res.string.m6_deezer_sign_out, Res.drawable.phone_reference_logout, PhoneSettingKind.ACTION, setOf("deezer_sign_out"), null, setOf(Res.string.m6_deezer_sign_out), false),
    PhoneSettingReference("group:deezer_api_group", Res.string.m6_deezer_api_group, null, PhoneSettingKind.HEADER, setOf("deezer_api_group"), null, setOf(Res.string.m6_deezer_api_group), false),
    PhoneSettingReference("deezer_api_instances", Res.string.m6_deezer_api_instances, Res.drawable.phone_reference_link, PhoneSettingKind.ACTION, setOf("deezer_api_instances"), null, setOf(Res.string.m6_deezer_api_instances), false),
    PhoneSettingReference("deezer_api_pool", Res.string.m6_deezer_api_pool, Res.drawable.phone_reference_cloud, PhoneSettingKind.ACTION, setOf("deezer_api_pool"), null, setOf(Res.string.m6_deezer_api_pool), false),
))
