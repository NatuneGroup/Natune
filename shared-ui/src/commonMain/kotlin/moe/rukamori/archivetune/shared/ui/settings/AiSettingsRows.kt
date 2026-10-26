package moe.rukamori.archivetune.shared.ui.settings

import moe.rukamori.archivetune.shared.domain.lyrics.ai.AiProvider
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.StringResource

/**
 * The public developer-console page where the user creates an API key for [provider], with the label that names
 * that page. CUSTOM and NONE have no public portal, so they get no link.
 */
private fun aiApiKeyPortal(provider: AiProvider): Pair<String, StringResource>? =
    when (provider) {
        AiProvider.CHATGPT -> "https://platform.openai.com/api-keys" to Res.string.m6_ai_portal_chatgpt
        AiProvider.GEMINI -> "https://aistudio.google.com/app/apikey" to Res.string.m6_ai_portal_gemini
        AiProvider.DEEPL -> "https://www.deepl.com/pro-api" to Res.string.m6_ai_portal_deepl
        AiProvider.OPENROUTER -> "https://openrouter.ai/keys" to Res.string.m6_ai_portal_openrouter
        AiProvider.MISTRAL -> "https://console.mistral.ai/api-keys" to Res.string.m6_ai_portal_mistral
        AiProvider.CUSTOM, AiProvider.NONE -> null
    }

fun aiSettingsRows(
    preferences: PreferenceSnapshot,
    testApi: () -> Unit,
    showModels: () -> Unit,
    showTranslationExclusions: () -> Unit,
    showRomanizationExclusions: () -> Unit,
    showTargetLanguages: () -> Unit,
    busy: Boolean = false,
    /** Opens the provider's API-key page through the host browser. Null leaves the Get API key row out. */
    openApiKeyPortal: ((String) -> Unit)? = null,
): List<SettingsRow> = buildList {
    val config = preferences.aiServiceSettings()
    val provider = config.provider
    add(SettingsRow.Choice("ai_provider", Res.string.m6_ai_provider, AiProviderKey, "NONE", listOf(
        SettingsRow.Option("GEMINI", Res.string.m6_gemini),
        SettingsRow.Option("CHATGPT", Res.string.m6_chatgpt),
        SettingsRow.Option("DEEPL", Res.string.m6_deepl),
        SettingsRow.Option("OPENROUTER", Res.string.m6_openrouter),
        SettingsRow.Option("MISTRAL", Res.string.m6_mistral),
        SettingsRow.Option("CUSTOM", Res.string.m6_custom),
        SettingsRow.Option("NONE", Res.string.m6_none),
    ), write = { setAiProvider(AiProvider.valueOf(it)) }))
    // Offered only when the host can open a browser, so the row never shows a link that does nothing.
    if (openApiKeyPortal != null) {
        aiApiKeyPortal(provider)?.let { (url, label) ->
            add(SettingsRow.Action("ai_api_key_portal", Res.string.m6_ai_get_api_key, { openApiKeyPortal(url) }, description = label))
        }
    }
    val credentialKey = when (provider) {
        AiProvider.OPENROUTER -> OpenRouterApiKeyKey
        AiProvider.DEEPL -> DeeplApiKeyKey
        else -> AiApiKeyKey
    }
    add(SettingsRow.Text(
        when (provider) {
            AiProvider.DEEPL -> "deepl_api_key"; AiProvider.OPENROUTER -> "openrouter_api_key"
            AiProvider.MISTRAL -> "mistral_api_key"; else -> "ai_api_key"
        },
        Res.string.m6_api_key, credentialKey, masked = true, enabled = provider != AiProvider.NONE,
        write = { setAiConfiguration(credentialKey, it) },
    ))
    if (provider == AiProvider.CUSTOM || provider == AiProvider.OPENROUTER) {
        val endpointKey = if (provider == AiProvider.OPENROUTER) OpenRouterBaseUrlKey else AiCustomEndpointKey
        add(SettingsRow.Text(
            if (provider == AiProvider.OPENROUTER) "openrouter_base_url" else "ai_custom_endpoint",
            Res.string.m6_endpoint, endpointKey, valid = ::validLyricsEndpoint,
            write = { setAiConfiguration(endpointKey, it) },
        ))
    }
    if (provider == AiProvider.CUSTOM) add(SettingsRow.Text("ai_custom_model", Res.string.m6_ai_model, AiCustomModelKey,
        write = { setAiConfiguration(AiCustomModelKey, it) }))
    else if (provider != AiProvider.DEEPL && provider != AiProvider.NONE) {
        add(SettingsRow.Action("ai_model", Res.string.m6_models, showModels, enabled = config.canCallApi && !busy))
    }
    if (provider == AiProvider.OPENROUTER) add(SettingsRow.Text("openrouter_model", Res.string.m6_ai_model, OpenRouterModelKey,
        valid = { it.isNotBlank() }, default = "openai/gpt-4o-mini",
        write = { setAiConfiguration(OpenRouterModelKey, it) }))
    if (provider == AiProvider.MISTRAL) add(SettingsRow.Text("mistral_model", Res.string.m6_ai_model, AiSelectedModelKey,
        valid = { it.isNotBlank() }, write = { setAiConfiguration(AiSelectedModelKey, it) }))
    if (provider == AiProvider.DEEPL) add(SettingsRow.Choice("deepl_formality", Res.string.m6_deepl_formality, DeeplFormalityKey, "default", listOf(
        SettingsRow.Option("default", Res.string.m6_default),
        SettingsRow.Option("more", Res.string.m6_formal),
        SettingsRow.Option("less", Res.string.m6_informal),
    ), write = { setAiConfiguration(DeeplFormalityKey, it) }))
    if (provider in setOf(AiProvider.DEEPL, AiProvider.OPENROUTER, AiProvider.MISTRAL)) {
        add(SettingsRow.Text("translate_language", Res.string.m6_provider_language, TranslateLanguageKey,
            valid = { it.isNotBlank() && it.length <= 64 }, default = "en"))
        add(SettingsRow.Choice("translate_mode", Res.string.m6_translate_mode, TranslateModeKey, "translate", listOf(
            SettingsRow.Option("translate", Res.string.m6_translate_only),
            SettingsRow.Option("romanize", Res.string.m6_romanize_only),
        )))
    }
    add(SettingsRow.Action("ai_test_api", Res.string.m6_test_api, testApi, enabled = config.canTestConfiguration() && !busy))
    add(SettingsRow.Toggle("hide_ai_mix", Res.string.m6_hide_ai_mix, HideAiMixKey, false))
    add(SettingsRow.Action("target_language", Res.string.m6_target_language, showTargetLanguages))
    add(SettingsRow.Toggle("auto_translate_lyrics", Res.string.m6_auto_translate, AutoTranslateLyricsKey, false, enabled = config.canCallApi))
    if (preferences[AutoTranslateLyricsKey, false])
        add(SettingsRow.Action("auto_translate_excluded_languages", Res.string.m6_translation_exclusions, showTranslationExclusions))
    add(SettingsRow.Toggle("ai_romanize_lyrics", Res.string.m6_ai_romanize, AiRomanizeLyricsKey, false, enabled = config.supportsRomanization))
    if (preferences[AiRomanizeLyricsKey, false]) {
        add(SettingsRow.Toggle("auto_ai_romanize_lyrics", Res.string.m6_auto_romanize, AutoAiRomanizeLyricsKey, false, enabled = config.supportsRomanization))
        add(SettingsRow.Action("ai_romanize_excluded_languages", Res.string.m6_romanization_exclusions, showRomanizationExclusions))
    }
}

fun privacyInlineSettingsRows(): List<SettingsRow> = listOf(
    SettingsRow.Toggle("sync_yt_history", Res.string.m6_sync_history, SyncPlaybackToYouTubeHistoryKey, PrivacyPreferenceDefaults.SYNC_YOUTUBE_HISTORY),
    SettingsRow.Toggle("network_metered", Res.string.m6_low_data, NetworkMeteredKey, PrivacyPreferenceDefaults.LOW_DATA_MODE),
)
