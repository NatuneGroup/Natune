package moe.rukamori.archivetune.shared.domain.settings

import moe.rukamori.archivetune.shared.domain.lyrics.ai.AiLyricsSessionSettings
import moe.rukamori.archivetune.shared.domain.lyrics.ai.AiProvider
import moe.rukamori.archivetune.shared.domain.lyrics.ai.AiRomanizationSettings
import moe.rukamori.archivetune.shared.domain.lyrics.ai.AiServiceSettings
import moe.rukamori.archivetune.shared.domain.lyrics.ai.AiTranslationSettingsSnapshot

/** Same key selection as Android toAiServiceConfig; catalog/streaming credentials never enter here. */
fun PreferenceSnapshot.aiServiceSettings(): AiServiceSettings {
    val provider = AiProvider.entries.firstOrNull { it.name == this[AiProviderKey] } ?: AiProvider.NONE
    return AiServiceSettings(
        provider = provider,
        apiKey = when (provider) {
            AiProvider.OPENROUTER -> this[OpenRouterApiKeyKey].orEmpty()
            AiProvider.DEEPL -> this[DeeplApiKeyKey].orEmpty()
            else -> this[AiApiKeyKey].orEmpty()
        },
        customEndpoint = if (provider == AiProvider.OPENROUTER) this[OpenRouterBaseUrlKey].orEmpty()
            else this[AiCustomEndpointKey].orEmpty(),
        model = when (provider) {
            AiProvider.CUSTOM -> this[AiCustomModelKey].orEmpty()
            AiProvider.OPENROUTER -> this[OpenRouterModelKey].orEmpty().ifBlank { "openai/gpt-4o-mini" }
            else -> this[AiSelectedModelKey].orEmpty()
        },
        deeplFormality = this[DeeplFormalityKey].orEmpty().ifBlank { "default" },
    )
}

fun PreferenceSnapshot.aiLyricsSessionSettings(): AiLyricsSessionSettings {
    val config = aiServiceSettings()
    return AiLyricsSessionSettings(
        translation = AiTranslationSettingsSnapshot(
            config, this[AutoTranslateLyricsKey, false], this[TranslatorTargetLangKey],
            this[AutoTranslateExcludedLanguagesKey, emptySet()],
        ),
        romanization = AiRomanizationSettings(
            this[AiRomanizeLyricsKey, false], this[AutoAiRomanizeLyricsKey, false],
            this[AiRomanizeExcludedLanguagesKey, emptySet()], config,
        ),
    )
}

/** One source for inline switches and ordinary/consumer reads (BUG-005). */
object PrivacyPreferenceDefaults {
    const val SYNC_YOUTUBE_HISTORY = true
    const val LOW_DATA_MODE = true
}

fun PreferenceSnapshot.syncYouTubeHistory(): Boolean =
    this[SyncPlaybackToYouTubeHistoryKey, PrivacyPreferenceDefaults.SYNC_YOUTUBE_HISTORY]

fun PreferenceSnapshot.lowDataMode(): Boolean =
    this[NetworkMeteredKey, PrivacyPreferenceDefaults.LOW_DATA_MODE]
