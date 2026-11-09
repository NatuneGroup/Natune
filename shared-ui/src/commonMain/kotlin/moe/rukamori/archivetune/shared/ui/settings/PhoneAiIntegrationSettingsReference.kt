package moe.rukamori.archivetune.shared.ui.settings

import moe.rukamori.archivetune.shared.ui.resources.*

/** Android presentation order; regenerate with scripts/sync_phone_settings_reference.py. */
internal val PhoneAiIntegrationSettingsReference = PhoneSettingsReference(Res.string.m6_integration_ai, listOf(
    PhoneSettingReference("group:ai_provider_settings", Res.string.phone_reference_ai_provider_settings, null, PhoneSettingKind.HEADER, setOf("ai_provider_settings"), null, setOf(), false),
    PhoneSettingReference("ai_provider", Res.string.m6_ai_provider, Res.drawable.phone_reference_auto_awesome, PhoneSettingKind.VALUE, setOf("ai_provider"), "ai_provider", setOf(Res.string.m6_ai_provider), false),
    PhoneSettingReference("ai_api_key", Res.string.m6_lastfm_api_key_override, Res.drawable.phone_reference_token, PhoneSettingKind.ACTION, setOf("ai_api_key"), null, setOf(Res.string.m6_lastfm_api_key_override, Res.string.m6_api_key), false),
    PhoneSettingReference("ai_test_api", Res.string.m6_test_api, Res.drawable.phone_reference_done, PhoneSettingKind.ACTION, setOf("ai_test_api"), null, setOf(Res.string.m6_test_api), false),
    PhoneSettingReference("auto_translate_lyrics", Res.string.phone_reference_auto_translate_lyrics, Res.drawable.phone_reference_translate, PhoneSettingKind.TOGGLE, setOf("auto_translate_lyrics"), "autoTranslateLyrics", setOf(), false),
    PhoneSettingReference("auto_translate_excluded_languages", Res.string.phone_reference_auto_translate_excluded_languages, Res.drawable.phone_reference_block, PhoneSettingKind.ACTION, setOf("auto_translate_excluded_languages"), null, setOf(), true),
    PhoneSettingReference("ai_romanize_lyrics", Res.string.phone_reference_ai_romanize_lyrics, Res.drawable.phone_reference_language, PhoneSettingKind.TOGGLE, setOf("ai_romanize_lyrics"), "aiRomanizeLyrics", setOf(), false),
    PhoneSettingReference("auto_ai_romanize_lyrics", Res.string.phone_reference_auto_ai_romanize_lyrics, Res.drawable.phone_reference_auto_awesome, PhoneSettingKind.TOGGLE, setOf("auto_ai_romanize_lyrics"), "autoAiRomanizeLyrics", setOf(), true),
    PhoneSettingReference("ai_romanize_excluded_languages", Res.string.phone_reference_ai_romanize_excluded_languages, Res.drawable.phone_reference_block, PhoneSettingKind.ACTION, setOf("ai_romanize_excluded_languages"), null, setOf(), true),
    PhoneSettingReference("group:ai_translation_settings", Res.string.phone_reference_ai_translation_settings, null, PhoneSettingKind.HEADER, setOf("ai_translation_settings"), null, setOf(), true),
    PhoneSettingReference("deepl_api_key", Res.string.phone_reference_deepl_api_key, Res.drawable.phone_reference_token, PhoneSettingKind.ACTION, setOf("deepl_api_key"), null, setOf(), true),
    PhoneSettingReference("deepl_formality", Res.string.m6_deepl_formality, Res.drawable.phone_reference_text_fields, PhoneSettingKind.ACTION, setOf("deepl_formality"), null, setOf(Res.string.m6_deepl_formality), true),
    PhoneSettingReference("openrouter_api_key", Res.string.phone_reference_openrouter_api_key, Res.drawable.phone_reference_token, PhoneSettingKind.ACTION, setOf("openrouter_api_key"), null, setOf(), true),
    PhoneSettingReference("mistral_api_key", Res.string.phone_reference_mistral_api_key, Res.drawable.phone_reference_token, PhoneSettingKind.ACTION, setOf("mistral_api_key"), null, setOf(), true),
    PhoneSettingReference("translate_mode", Res.string.phone_reference_translate_mode, Res.drawable.phone_reference_text_fields, PhoneSettingKind.ACTION, setOf("translate_mode"), null, setOf(), true),
    PhoneSettingReference("group:translator_options", Res.string.m6_discord_group_translator, null, PhoneSettingKind.HEADER, setOf("translator_options"), null, setOf(Res.string.m6_discord_group_translator), false),
    PhoneSettingReference("ai_model", Res.string.m6_ai_model, Res.drawable.phone_reference_auto_awesome, PhoneSettingKind.ACTION, setOf("ai_model"), null, setOf(Res.string.m6_ai_model), false),
))
