package moe.rukamori.archivetune.shared.ui.settings

import moe.rukamori.archivetune.shared.ui.resources.*

/** Android presentation order; regenerate with scripts/sync_phone_settings_reference.py. */
internal val PhoneJioSettingsReference = PhoneSettingsReference(Res.string.phone_reference_jiosaavn_settings, listOf(
    PhoneSettingReference("group:jiosaavn_integration", Res.string.phone_reference_jiosaavn_integration, null, PhoneSettingKind.HEADER, setOf("jiosaavn_integration"), null, setOf(), false),
    PhoneSettingReference("jiosaavn_audio_quality", Res.string.m6_audio_quality, Res.drawable.phone_reference_play, PhoneSettingKind.VALUE, setOf("jiosaavn_audio_quality"), "saavnAudioQuality", setOf(Res.string.m6_audio_quality, Res.string.m6_qobuz_audio_quality), false),
    PhoneSettingReference("group:jiosaavn_credit_title", Res.string.phone_reference_jiosaavn_credit_title, null, PhoneSettingKind.HEADER, setOf("jiosaavn_credit_title"), null, setOf(), false),
    PhoneSettingReference("jiosaavn_credit", Res.string.phone_reference_jiosaavn_credit, Res.drawable.phone_reference_info, PhoneSettingKind.ACTION, setOf("jiosaavn_credit"), null, setOf(), false),
))
