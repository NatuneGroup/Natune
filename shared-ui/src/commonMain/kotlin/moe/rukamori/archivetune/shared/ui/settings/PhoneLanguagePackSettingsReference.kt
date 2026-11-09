package moe.rukamori.archivetune.shared.ui.settings

import moe.rukamori.archivetune.shared.ui.resources.*

/** Android presentation order; regenerate with scripts/sync_phone_settings_reference.py. */
internal val PhoneLanguagePackSettingsReference = PhoneSettingsReference(Res.string.phone_reference_language_packs, listOf(
    PhoneSettingReference("group:language_pack_base_language", Res.string.m6_language_pack_base_group, null, PhoneSettingKind.HEADER, setOf("language_pack_base_language"), null, setOf(Res.string.m6_language_pack_base_group), false),
    PhoneSettingReference("language_pack_english", Res.string.m6_language_english, Res.drawable.phone_reference_language, PhoneSettingKind.ACTION, setOf("language_pack_english"), null, setOf(Res.string.m6_language_english), false),
    PhoneSettingReference("group:language_pack_optional_data", Res.string.m6_language_pack_optional_group, null, PhoneSettingKind.HEADER, setOf("language_pack_optional_data"), null, setOf(Res.string.m6_language_pack_optional_group), false),
    PhoneSettingReference("language_pack_japanese", Res.string.m6_language_pack_japanese, Res.drawable.phone_reference_translate, PhoneSettingKind.ACTION, setOf("language_pack_japanese"), null, setOf(Res.string.m6_language_pack_japanese), false),
    PhoneSettingReference("language_pack_other_romanization", Res.string.m6_language_pack_other_romanization, Res.drawable.phone_reference_translate, PhoneSettingKind.ACTION, setOf("language_pack_other_romanization"), null, setOf(Res.string.m6_language_pack_other_romanization), false),
    PhoneSettingReference("group:additional_downloads_player_fonts", Res.string.m6_additional_downloads_player_fonts, null, PhoneSettingKind.HEADER, setOf("additional_downloads_player_fonts"), null, setOf(Res.string.m6_additional_downloads_player_fonts), false),
))
