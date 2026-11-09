package moe.rukamori.archivetune.shared.ui.settings

import moe.rukamori.archivetune.shared.ui.resources.*

/** Android presentation order; regenerate with scripts/sync_phone_settings_reference.py. */
internal val PhoneInternetSettingsReference = PhoneSettingsReference(Res.string.m6_settings_home_internet, listOf(
    PhoneSettingReference("group:youtube_music_region", Res.string.m6_yt_region, null, PhoneSettingKind.HEADER, setOf("youtube_music_region"), null, setOf(Res.string.m6_yt_region), false),
    PhoneSettingReference("youtube_music_region", Res.string.m6_yt_region, Res.drawable.phone_reference_location_on, PhoneSettingKind.VALUE, setOf("youtube_music_region"), "youtubeMusicRegion", setOf(Res.string.m6_yt_region), false),
    PhoneSettingReference("group:dns_over_https", Res.string.m6_doh, null, PhoneSettingKind.HEADER, setOf("dns_over_https"), null, setOf(Res.string.m6_doh), false),
    PhoneSettingReference("dns_over_https", Res.string.m6_doh, Res.drawable.phone_reference_security, PhoneSettingKind.TOGGLE, setOf("dns_over_https"), "enableDnsOverHttps", setOf(Res.string.m6_doh), false),
    PhoneSettingReference("dns_provider", Res.string.m6_dns_provider, Res.drawable.phone_reference_website, PhoneSettingKind.VALUE, setOf("dns_provider"), "dnsOverHttpsProvider", setOf(Res.string.m6_dns_provider), true),
    PhoneSettingReference("group:proxy", Res.string.phone_reference_proxy, null, PhoneSettingKind.HEADER, setOf("proxy"), null, setOf(), false),
    PhoneSettingReference("enable_proxy", Res.string.m6_proxy_enable, Res.drawable.phone_reference_wifi_proxy, PhoneSettingKind.TOGGLE, setOf("enable_proxy"), "proxyEnabled", setOf(Res.string.m6_proxy_enable), false),
    PhoneSettingReference("proxy_type", Res.string.m6_proxy_type, null, PhoneSettingKind.VALUE, setOf("proxy_type"), "proxyType", setOf(Res.string.m6_proxy_type), true),
    PhoneSettingReference("group:proxy_auth", Res.string.phone_reference_proxy_auth, null, PhoneSettingKind.HEADER, setOf("proxy_auth"), null, setOf(), true),
    PhoneSettingReference("stream_bypass_proxy", Res.string.m6_proxy_bypass, Res.drawable.phone_reference_wifi_proxy, PhoneSettingKind.TOGGLE, setOf("stream_bypass_proxy"), "streamBypassProxy", setOf(Res.string.m6_proxy_bypass), true),
    PhoneSettingReference("test_proxy", Res.string.m6_test_proxy, Res.drawable.phone_reference_check, PhoneSettingKind.ACTION, setOf("test_proxy_connection"), null, setOf(Res.string.m6_test_proxy), true),
    PhoneSettingReference("group:ip_rotation", Res.string.m6_ip_rotation, null, PhoneSettingKind.HEADER, setOf("ip_rotation"), null, setOf(Res.string.m6_ip_rotation), false),
))
