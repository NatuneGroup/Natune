package moe.rukamori.archivetune.shared.ui.settings

import java.nio.file.Files
import kotlinx.coroutines.*
import moe.rukamori.archivetune.shared.domain.lyrics.ai.*
import moe.rukamori.archivetune.shared.domain.lyrics.ai.AiRequestBudget
import moe.rukamori.archivetune.shared.domain.repository.AiTextService
import moe.rukamori.archivetune.shared.domain.settings.*
import kotlin.test.*

class AiSettingsActionsTest {
    @Test fun publishedServiceModelSelectionAndValidationPersistAcrossReopen() = runBlocking {
        val directory = Files.createTempDirectory("m6-ai-settings-")
        val file = directory.resolve("settings.bin")
        val vault = TestSettingsVault()
        try {
            val store = VaultTestOwner.open(file, vault)
            val controller = SettingsController(store, store.snapshot(), false)
            val service = Service()
            val actions = AiSettingsActions(this, controller, service)
            try {
                controller.edit {
                    setAiProvider(AiProvider.OPENROUTER)
                    setAiConfiguration(OpenRouterApiKeyKey, "test-only-router-key")
                    this[AiApiKeyKey] = "test-only-generic-key"
                }
                actions.fetchModels()!!.join()
                assertEquals(listOf("router/model-a", "router/model-b"), actions.actions.value.models)
                assertEquals("test-only-router-key", service.last!!.apiKey)
                assertFalse(actions.selectModel("not-returned-by-provider"))
                assertTrue(actions.selectModel("router/model-b"))
                assertEquals("router/model-b", store.snapshot()[OpenRouterModelKey])
                assertNull(store.snapshot()[AiSelectedModelKey].takeUnless { it.isNullOrBlank() })
                actions.testApi()!!.join()
                assertEquals("SUCCESS", store.snapshot()[AiApiValidationStatusKey])
            } finally { actions.close(); store.close() }
            val reopened = VaultTestOwner.open(file, vault)
            try {
                assertEquals("router/model-b", reopened.snapshot().aiServiceSettings().model)
                assertEquals("SUCCESS", reopened.snapshot()[AiApiValidationStatusKey])
                assertEquals("test-only-generic-key", reopened.snapshot()[AiApiKeyKey])
            } finally { reopened.close() }
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun staleProviderTestCannotValidateNewConfigurationAndRequestsDeduplicate() = runBlocking {
        val directory = Files.createTempDirectory("m6-ai-stale-")
        val store = VaultTestOwner.open(directory.resolve("settings.bin"), TestSettingsVault())
        val controller = SettingsController(store, store.snapshot(), false)
        val service = Service()
        val began = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        service.testing = { withContext(NonCancellable) { began.complete(Unit); finish.await() } }
        val actions = AiSettingsActions(this, controller, service)
        try {
            controller.edit {
                setAiProvider(AiProvider.CUSTOM)
                setAiConfiguration(AiApiKeyKey, "test-only-key")
                setAiConfiguration(AiCustomEndpointKey, "https://example.invalid/v1")
                setAiConfiguration(AiCustomModelKey, "first")
            }
            val old = actions.testApi()!!
            began.await()
            assertNull(actions.testApi())
            controller.edit { setAiConfiguration(AiCustomModelKey, "second") }
            finish.complete(Unit)
            old.join()
            assertEquals("UNKNOWN", store.snapshot()[AiApiValidationStatusKey])
            service.testing = { throw IllegalStateException("must not display raw response or credential") }
            actions.testApi()!!.join()
            assertEquals("FAILED", store.snapshot()[AiApiValidationStatusKey])
            assertTrue(actions.actions.value.failed)
            assertFalse(actions.actions.value.toString().contains("credential"))
        } finally { actions.close(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun noneAndIncompleteCustomAreIneligibleAndDeepLNeverListsModels() = runBlocking {
        val directory = Files.createTempDirectory("m6-ai-gates-")
        val store = VaultTestOwner.open(directory.resolve("settings.bin"), TestSettingsVault())
        val controller = SettingsController(store, store.snapshot(), false)
        val actions = AiSettingsActions(this, controller, Service())
        try {
            assertNull(actions.testApi())
            assertNull(actions.fetchModels())
            controller.edit { setAiProvider(AiProvider.CUSTOM); setAiConfiguration(AiApiKeyKey, "test-only-key") }
            assertNull(actions.testApi())
            controller.edit { setAiProvider(AiProvider.DEEPL); setAiConfiguration(DeeplApiKeyKey, "test-only-deepl-key") }
            assertNull(actions.fetchModels())
            actions.testApi()!!.join()
            assertEquals("SUCCESS", store.snapshot()[AiApiValidationStatusKey])
        } finally { actions.close(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun actualRowGatesAndProviderOrderMatchWireValuesWithoutClearingHiddenChoices() {
        val snapshot = PreferenceSnapshot.of(AiProviderKey to "DEEPL", DeeplApiKeyKey to "test-only",
            AiRomanizeLyricsKey to true, AutoTranslateLyricsKey to false)
        val rows = aiSettingsRows(snapshot, {}, {}, {}, {}, {})
        assertEquals(listOf("GEMINI", "CHATGPT", "DEEPL", "OPENROUTER", "MISTRAL", "CUSTOM", "NONE"),
            (rows.first() as SettingsRow.Choice).options.map { it.value })
        assertFalse(rows.single { it.anchor == "ai_romanize_lyrics" }.enabled)
        assertFalse(rows.single { it.anchor == "auto_ai_romanize_lyrics" }.enabled)
        assertFalse(rows.any { it.anchor == "auto_translate_excluded_languages" })
        assertEquals(true, snapshot[AiRomanizeLyricsKey])
        assertEquals(110, translatorLanguageOptions().size)
        assertEquals("JAPANESE", translatorLanguageOptions().single { it.value == "JAPANESE" }.value)
        assertTrue(AiServiceSettings(AiProvider.GEMINI, "test-only", "", "").canTestConfiguration())
        assertTrue(AiServiceSettings(AiProvider.MISTRAL, "test-only", "", "").canTestConfiguration())
    }

    private class Service : AiTextService {
        var last: AiServiceSettings? = null
        var testing: suspend () -> Unit = {}
        override suspend fun complete(
            config: AiServiceSettings, budget: AiRequestBudget, systemPrompt: String,
            userPrompt: String, temperature: Double, maxTokens: Int,
        ): String = error("Not a completion test")
        override suspend fun fetchModels(config: AiServiceSettings): List<AiModelOption> = listModels(config).map { AiModelOption(it, it) }
        override suspend fun listModels(config: AiServiceSettings): List<String> {
            last = config
            return listOf("router/model-a", "", "router/model-b", "router/model-a")
        }
        override suspend fun test(config: AiServiceSettings) { last = config; testing() }
        override suspend fun translateLines(config: AiServiceSettings, targetLanguage: String, lines: List<String>, formatName: String): List<String> = error("Not a lyrics request")
        override suspend fun romanizeLines(config: AiServiceSettings, lines: List<String>, formatName: String): List<String> = error("Not a lyrics request")
    }
}
