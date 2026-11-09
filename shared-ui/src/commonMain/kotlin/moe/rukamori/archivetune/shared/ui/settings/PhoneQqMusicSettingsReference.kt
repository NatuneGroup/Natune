package moe.rukamori.archivetune.shared.ui.settings

import moe.rukamori.archivetune.shared.ui.resources.*

/** Android presentation order; regenerate with scripts/sync_phone_settings_reference.py. */
internal val PhoneQqMusicSettingsReference = PhoneSettingsReference(Res.string.m4_qq, listOf(
    PhoneSettingReference("group:source_qq_music", Res.string.m4_qq, null, PhoneSettingKind.HEADER, setOf("source_qq_music"), null, setOf(Res.string.m4_qq, Res.string.m5pq_source_qq_music, Res.string.m6_dev_source_qq, Res.string.m6_integration_qqmusic, Res.string.m6_settings_home_qqmusic, Res.string.m6_source_qq), false),
    PhoneSettingReference("qq_music_quality", Res.string.m6_amazon_audio_quality, Res.drawable.phone_reference_equalizer, PhoneSettingKind.VALUE, setOf("qq_music_quality"), "qqMusicAudioQuality", setOf(Res.string.m6_amazon_audio_quality, Res.string.m6_qq_quality), false),
    PhoneSettingReference("qq_music_sign_out", Res.string.m6_qq_sign_out, Res.drawable.phone_reference_logout, PhoneSettingKind.ACTION, setOf("qq_music_sign_out"), null, setOf(Res.string.m6_qq_sign_out), false),
    PhoneSettingReference("qq_music_login_retry", Res.string.m6_qq_login_retry, Res.drawable.phone_reference_arrow_back, PhoneSettingKind.ACTION, setOf("qq_music_login_retry"), null, setOf(Res.string.m6_qq_login_retry), false),
))
