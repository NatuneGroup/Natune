package moe.rukamori.archivetune.lyrics

import androidx.datastore.preferences.core.mutablePreferencesOf
import moe.rukamori.archivetune.ai.toAiServiceConfig
import moe.rukamori.archivetune.constants.AiApiKeyKey
import moe.rukamori.archivetune.constants.AiApiValidationStatus
import moe.rukamori.archivetune.constants.AiCustomEndpointKey
import moe.rukamori.archivetune.constants.AiProvider
import moe.rukamori.archivetune.constants.AiProviderKey
import moe.rukamori.archivetune.constants.DeeplApiKeyKey
import moe.rukamori.archivetune.constants.OpenRouterApiKeyKey
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiLyricsTranslationEligibilityTest {
    @Test
    fun openRouterCredentialAloneOffersTranslation() {
        val stored =
            mutablePreferencesOf(
                AiProviderKey to AiProvider.OPENROUTER.name,
                OpenRouterApiKeyKey to "fixture-openrouter-credential",
            )

        // The former generic-key check rejected this configuration before any provider selection ran.
        assertNull(stored[AiApiKeyKey])
        assertTrue(isAiTranslationAvailable(stored.toAiServiceConfig(), AiApiValidationStatus.UNKNOWN))
    }

    @Test
    fun deepLCredentialAloneOffersTranslationButNotRomanization() {
        val stored =
            mutablePreferencesOf(
                AiProviderKey to AiProvider.DEEPL.name,
                DeeplApiKeyKey to "fixture-deepl-credential",
            )

        assertNull(stored[AiApiKeyKey])
        val config = stored.toAiServiceConfig()
        assertTrue(isAiTranslationAvailable(config, AiApiValidationStatus.UNKNOWN))
        // DeepL is translation-only; the romanisation surfaces keep refusing it through supportsRomanization.
        assertFalse(config.supportsRomanization)
    }

    @Test
    fun missingOrDisabledProviderStaysUnavailable() {
        val credentialOnly =
            mutablePreferencesOf(
                AiApiKeyKey to "fixture-generic-credential",
                OpenRouterApiKeyKey to "fixture-openrouter-credential",
                DeeplApiKeyKey to "fixture-deepl-credential",
            )
        val explicitlyDisabled =
            mutablePreferencesOf(
                AiProviderKey to AiProvider.NONE.name,
                AiApiKeyKey to "fixture-generic-credential",
            )

        assertFalse(isAiTranslationAvailable(credentialOnly.toAiServiceConfig(), AiApiValidationStatus.UNKNOWN))
        assertFalse(isAiTranslationAvailable(explicitlyDisabled.toAiServiceConfig(), AiApiValidationStatus.UNKNOWN))
    }

    @Test
    fun credentialsFromAnotherProviderDoNotUnlockTranslation() {
        val openRouterWithGenericOnly =
            mutablePreferencesOf(
                AiProviderKey to AiProvider.OPENROUTER.name,
                AiApiKeyKey to "fixture-generic-credential",
            )
        val deepLWithOpenRouterOnly =
            mutablePreferencesOf(
                AiProviderKey to AiProvider.DEEPL.name,
                OpenRouterApiKeyKey to "fixture-openrouter-credential",
            )
        val deepLWithGenericOnly =
            mutablePreferencesOf(
                AiProviderKey to AiProvider.DEEPL.name,
                AiApiKeyKey to "fixture-generic-credential",
            )
        val customWithoutEndpoint =
            mutablePreferencesOf(
                AiProviderKey to AiProvider.CUSTOM.name,
                AiApiKeyKey to "fixture-generic-credential",
            )
        val customWithEndpoint =
            mutablePreferencesOf(
                AiProviderKey to AiProvider.CUSTOM.name,
                AiApiKeyKey to "fixture-generic-credential",
                AiCustomEndpointKey to "https://example.invalid/v1",
            )

        assertFalse(
            isAiTranslationAvailable(openRouterWithGenericOnly.toAiServiceConfig(), AiApiValidationStatus.UNKNOWN),
        )
        assertFalse(
            isAiTranslationAvailable(deepLWithOpenRouterOnly.toAiServiceConfig(), AiApiValidationStatus.UNKNOWN),
        )
        assertFalse(
            isAiTranslationAvailable(deepLWithGenericOnly.toAiServiceConfig(), AiApiValidationStatus.UNKNOWN),
        )
        assertFalse(
            isAiTranslationAvailable(customWithoutEndpoint.toAiServiceConfig(), AiApiValidationStatus.UNKNOWN),
        )
        assertTrue(
            isAiTranslationAvailable(customWithEndpoint.toAiServiceConfig(), AiApiValidationStatus.UNKNOWN),
        )
    }

    @Test
    fun failedValidationStillBlocksTranslation() {
        val config =
            mutablePreferencesOf(
                AiProviderKey to AiProvider.OPENROUTER.name,
                OpenRouterApiKeyKey to "fixture-openrouter-credential",
            ).toAiServiceConfig()

        assertFalse(isAiTranslationAvailable(config, AiApiValidationStatus.FAILED))
        assertTrue(isAiTranslationAvailable(config, AiApiValidationStatus.SUCCESS))
    }

    @Test
    fun ordinaryOpenAiCredentialKeepsWorking() {
        val configured =
            mutablePreferencesOf(
                AiProviderKey to AiProvider.CHATGPT.name,
                AiApiKeyKey to "fixture-generic-credential",
            )
        val blankCredential =
            mutablePreferencesOf(
                AiProviderKey to AiProvider.CHATGPT.name,
            )

        // No selected model is required: the provider supplies its default. Only the credential is needed.
        assertTrue(isAiTranslationAvailable(configured.toAiServiceConfig(), AiApiValidationStatus.UNKNOWN))
        assertFalse(isAiTranslationAvailable(blankCredential.toAiServiceConfig(), AiApiValidationStatus.UNKNOWN))
    }
}
