package moe.rukamori.archivetune.shared.ui.settings

import java.nio.file.Files
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlinx.coroutines.*
import moe.rukamori.archivetune.shared.domain.lyrics.ai.AiServiceSettings
import moe.rukamori.archivetune.shared.domain.repository.AiTextService
import moe.rukamori.archivetune.shared.domain.settings.*
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class AiIntegrationSettingsTest {
    @Test fun realDialogsPersistModelsTargetsAndExclusionsOnlyOnCommit() = runBlocking {
        val directory = Files.createTempDirectory("m6-ai-dialogs-")
        val file = directory.resolve("settings.bin")
        val vault = TestSettingsVault()
        val store = VaultTestOwner.open(file, vault)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = SettingsController(store, store.snapshot(), false)
        val service = FakeService()
        val actions = AiSettingsActions(scope, controller, service)
        try {
            controller.edit {
                this[AiProviderKey] = "OPENROUTER"
                this[OpenRouterApiKeyKey] = "test-only-key"
                this[AutoTranslateLyricsKey] = true
                this[AutoTranslateExcludedLanguagesKey] = setOf("UNKNOWN_FUTURE_LANGUAGE")
            }
            runComposeUiTest {
                var anchor by mutableStateOf("openrouter_api_key")
                setContent { MaterialTheme {
                    AiIntegrationSettings(controller, actions, { error("Unexpected storage failure") }, scrollTo = anchor)
                } }
                onNodeWithTag("setting:openrouter_api_key").performClick()
                onNodeWithTag("settings-draft").performTextReplacement("test-only-cancelled-key")
                onNodeWithText("Cancel").performClick()
                assertEquals("test-only-key", store.snapshots.value?.get(OpenRouterApiKeyKey))
                onNodeWithTag("setting:openrouter_api_key").performClick()
                onNodeWithTag("settings-draft").performTextReplacement("test-only-ui-router-key")
                onNodeWithText("Save").performClick()
                waitUntil { store.snapshots.value?.get(OpenRouterApiKeyKey) == "test-only-ui-router-key" }
                assertNull(store.ordinary.snapshots.value?.get(OpenRouterApiKeyKey))
                assertFalse(Files.readAllBytes(file).toString(Charsets.UTF_8).contains("test-only-ui-router-key"))
                runOnIdle { anchor = "ai_model" }
                onNodeWithTag("setting:ai_model").performClick()
                waitUntil { actions.actions.value.models.isNotEmpty() }
                onNodeWithTag("ai-model:fake-service/model").performClick()
                waitUntil { store.snapshots.value?.get(OpenRouterModelKey) == "fake-service/model" }
                runOnIdle { anchor = "ai_test_api" }
                onNodeWithTag("setting:ai_test_api").performClick()
                waitUntil { store.snapshots.value?.get(AiApiValidationStatusKey) == "SUCCESS" }
                onNodeWithTag("ai-validation").assertTextEquals("API test succeeded")

                runOnIdle { anchor = "target_language" }
                onNodeWithTag("setting:target_language").performClick()
                onNodeWithTag("language:AFRIKAANS").performClick()
                assertNull(store.snapshots.value?.get(TranslatorTargetLangKey))
                onNodeWithText("Save").performClick()
                waitUntil { store.snapshots.value?.get(TranslatorTargetLangKey) == "AFRIKAANS" }

                runOnIdle { anchor = "auto_translate_excluded_languages" }
                onNodeWithTag("setting:auto_translate_excluded_languages").performClick()
                onNodeWithTag("language:AFRIKAANS").performClick()
                onNodeWithText("Cancel").performClick()
                assertEquals(setOf("UNKNOWN_FUTURE_LANGUAGE"), store.snapshots.value?.get(AutoTranslateExcludedLanguagesKey))
                onNodeWithTag("setting:auto_translate_excluded_languages").performClick()
                onNodeWithTag("language:AFRIKAANS").performClick()
                onNodeWithText("Save").performClick()
                waitUntil { store.snapshots.value?.get(AutoTranslateExcludedLanguagesKey)?.contains("AFRIKAANS") == true }
            }
            assertEquals(1, service.tests)
        } finally { actions.close(); scope.cancel(); store.close() }
        try {
            val reopened = VaultTestOwner.open(file, vault)
            try {
                assertEquals("fake-service/model", reopened.snapshot()[OpenRouterModelKey])
                assertEquals("AFRIKAANS", reopened.snapshot()[TranslatorTargetLangKey])
                assertEquals(setOf("AFRIKAANS", "UNKNOWN_FUTURE_LANGUAGE"), reopened.snapshot()[AutoTranslateExcludedLanguagesKey])
                assertEquals("SUCCESS", reopened.snapshot()[AiApiValidationStatusKey])
            } finally { reopened.close() }
        } finally { directory.toFile().deleteRecursively() }
    }

    private class FakeService : AiTextService {
        var tests = 0
        override suspend fun listModels(config: AiServiceSettings) = listOf("fake-service/model")
        override suspend fun test(config: AiServiceSettings) { tests++ }
        override suspend fun translateLines(config: AiServiceSettings, targetLanguage: String, lines: List<String>, formatName: String): List<String> = error("Not a lyrics test")
        override suspend fun romanizeLines(config: AiServiceSettings, lines: List<String>, formatName: String): List<String> = error("Not a lyrics test")
    }
}
