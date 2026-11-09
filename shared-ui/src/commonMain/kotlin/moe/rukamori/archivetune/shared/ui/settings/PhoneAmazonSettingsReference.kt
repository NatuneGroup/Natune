package moe.rukamori.archivetune.shared.ui.settings

import moe.rukamori.archivetune.shared.ui.resources.*

/** Android presentation order; regenerate with scripts/sync_phone_settings_reference.py. */
internal val PhoneAmazonSettingsReference = PhoneSettingsReference(Res.string.m5pq_source_amazon, listOf(
    PhoneSettingReference("group:source_amazon", Res.string.m5pq_source_amazon, null, PhoneSettingKind.HEADER, setOf("source_amazon"), null, setOf(Res.string.m5pq_source_amazon, Res.string.m6_integration_amazon, Res.string.m6_settings_home_amazon, Res.string.m6_source_amazon), false),
    PhoneSettingReference("amazon_audio_quality", Res.string.m6_amazon_audio_quality, Res.drawable.phone_reference_graphic_eq, PhoneSettingKind.VALUE, setOf("amazon_audio_quality"), "amazonAudioQuality", setOf(Res.string.m6_amazon_audio_quality, Res.string.m6_qq_quality), false),
    PhoneSettingReference("group:amazon_instances", Res.string.m6_amazon_instances, null, PhoneSettingKind.HEADER, setOf("amazon_instances"), null, setOf(Res.string.m6_amazon_instances), false),
    PhoneSettingReference("amazon_instances", Res.string.m6_amazon_instances, Res.drawable.phone_reference_link, PhoneSettingKind.ACTION, setOf("amazon_instances"), null, setOf(Res.string.m6_amazon_instances), false),
    PhoneSettingReference("amazon_pool_instances", Res.string.m6_amazon_pool_instances, Res.drawable.phone_reference_cloud, PhoneSettingKind.ACTION, setOf("amazon_pool_instances"), null, setOf(Res.string.m6_amazon_pool_instances), false),
    PhoneSettingReference("amazon_authorize", Res.string.m6_amazon_authorize, Res.drawable.phone_reference_login, PhoneSettingKind.ACTION, setOf("amazon_authorize"), null, setOf(Res.string.m6_amazon_authorize), false),
    PhoneSettingReference("amazon_jwt_status", Res.string.m6_amazon_jwt_status, Res.drawable.phone_reference_lock, PhoneSettingKind.ACTION, setOf("amazon_jwt_status"), null, setOf(Res.string.m6_amazon_jwt_status), false),
    PhoneSettingReference("amazon_bypass_token", Res.string.m6_amazon_bypass_token, Res.drawable.phone_reference_token, PhoneSettingKind.ACTION, setOf("amazon_bypass_token"), null, setOf(Res.string.m6_amazon_bypass_token), false),
))
