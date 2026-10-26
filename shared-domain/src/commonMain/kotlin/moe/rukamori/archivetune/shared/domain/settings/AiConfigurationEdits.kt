package moe.rukamori.archivetune.shared.domain.settings

import moe.rukamori.archivetune.shared.domain.lyrics.ai.AiProvider
import moe.rukamori.archivetune.shared.domain.lyrics.ai.AiServiceSettings

fun PreferenceEditor.setAiProvider(provider: AiProvider) {
    if (this[AiProviderKey, "NONE"] == provider.name) return
    this[AiProviderKey] = provider.name
    this[AiSelectedModelKey] = ""
    this[AiApiValidationStatusKey] = "UNKNOWN"
}

/** A successful test belongs to the exact configuration, not just the provider. */
fun PreferenceEditor.setAiConfiguration(key: StringPreferenceKey, value: String) {
    require(key in setOf(AiApiKeyKey, AiCustomEndpointKey, AiCustomModelKey, AiSelectedModelKey,
        OpenRouterApiKeyKey, OpenRouterBaseUrlKey, OpenRouterModelKey, DeeplApiKeyKey, DeeplFormalityKey))
    val normalized = value.trim()
    if (this[key] == normalized) return
    this[key] = normalized
    this[AiApiValidationStatusKey] = "UNKNOWN"
}

fun AiServiceSettings.canTestConfiguration(): Boolean =
    canCallApi && (provider != AiProvider.CUSTOM || model.isNotBlank())
