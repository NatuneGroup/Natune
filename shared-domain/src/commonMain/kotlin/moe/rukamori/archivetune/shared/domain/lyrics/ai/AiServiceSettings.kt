package moe.rukamori.archivetune.shared.domain.lyrics.ai

/** Provider kinds, named exactly as the app's `AiProvider` so stored preference values keep resolving. */
enum class AiProvider {
    CHATGPT,
    GEMINI,
    OPENROUTER,
    CUSTOM,
    DEEPL,
    MISTRAL,
    NONE,
}

/**
 * The provider configuration that the translation and romanisation paths read. The credential lives only
 * in memory here. [toString] never prints it, and [cacheIdentity] never returns it or the endpoint.
 */
data class AiServiceSettings(
    val provider: AiProvider,
    val apiKey: String,
    val customEndpoint: String,
    val model: String,
    val deeplFormality: String = "default",
) {
    val canCallApi: Boolean
        get() =
            provider != AiProvider.NONE &&
                apiKey.isNotBlank() &&
                (provider != AiProvider.CUSTOM || customEndpoint.isNotBlank())

    /** DeepL is translation-only, so it never romanises. */
    val supportsRomanization: Boolean
        get() = canCallApi && provider != AiProvider.DEEPL

    /** Salted digest over every configuration field. Two configurations differ whenever any field differs. */
    fun cacheIdentity(salt: String): String =
        Sha256.hexDigestOfParts(listOf(salt, provider.name, apiKey, customEndpoint, model, deeplFormality))

    override fun toString(): String = "AiServiceSettings(provider=$provider, credentials=<redacted>)"
}
