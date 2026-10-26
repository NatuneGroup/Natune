/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.lyrics.ai.AiServiceFailureKind
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private enum class AiSettingsDialog { MODELS, TARGET, TRANSLATION_EXCLUSIONS, ROMANIZATION_EXCLUSIONS }

/** The host shares this controller/store/service owner with the player and LyricsSessions. */
@Composable
fun AiIntegrationSettings(
    controller: SettingsController,
    actions: AiSettingsActions,
    onFailure: () -> Unit,
    scrollTo: String? = null,
    modifier: Modifier = Modifier,
    /** Opens the selected provider's API-key page through the host browser. Null hides the Get API key row. */
    onOpenApiKeyPortal: ((String) -> Unit)? = null,
) {
    val snapshot by controller.store.snapshots.collectAsState()
    val preferences = snapshot
    if (preferences == null) { CircularProgressIndicator(); return }
    val state by actions.actions.collectAsState()
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<AiSettingsDialog?>(null) }
    fun attempt(block: suspend () -> Unit) {
        scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { onFailure() }
        }
    }
    /** Closing the model list stops an in-flight fetch, so no list is filled in behind a dismissed dialog. */
    fun closeModels() {
        dialog = null
        if (state.running == AiSettingsOperation.MODELS) attempt { actions.cancelRunning() }
    }
    Column(modifier) {
        if (state.running != null) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.running == AiSettingsOperation.TEST) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(Res.string.m6_ai_api_testing),
                    Modifier.weight(1f).testTag("ai-test-running"),
                )
                TextButton(onClick = { attempt { actions.cancelRunning() } }, modifier = Modifier.testTag("ai-test-cancel")) {
                    Text(stringResource(Res.string.m6_cancel))
                }
            }
        }
        if (state.failed) {
            Text(
                stringResource(state.failure?.let(::aiFailureHint) ?: Res.string.m6_ai_request_failed),
                Modifier.testTag("ai-action-failed"),
            )
        }
        Text(stringResource(when (preferences[AiApiValidationStatusKey, "UNKNOWN"]) {
            "SUCCESS" -> Res.string.m6_ai_test_success
            "FAILED" -> Res.string.m6_ai_test_failed
            else -> Res.string.m6_ai_test_unknown
        }), Modifier.testTag("ai-validation"))
        SettingsPage(
            rows = aiSettingsRows(preferences,
                testApi = { attempt { actions.testApi() } },
                showModels = { dialog = AiSettingsDialog.MODELS; attempt { actions.fetchModels() } },
                showTranslationExclusions = { dialog = AiSettingsDialog.TRANSLATION_EXCLUSIONS },
                showRomanizationExclusions = { dialog = AiSettingsDialog.ROMANIZATION_EXCLUSIONS },
                showTargetLanguages = { dialog = AiSettingsDialog.TARGET },
                busy = state.running != null,
                openApiKeyPortal = onOpenApiKeyPortal),
            preferences = preferences, controller = controller, scrollTo = scrollTo, onFailure = onFailure,
        )
    }
    when (dialog) {
        AiSettingsDialog.MODELS -> AlertDialog(
            onDismissRequest = { closeModels() },
            title = { Text(stringResource(Res.string.m6_models)) },
            text = { LazyColumn(Modifier.heightIn(max = 400.dp)) {
                items(state.models, key = { it }) { id ->
                    TextButton(onClick = { attempt { if (actions.selectModel(id)) dialog = null } },
                        modifier = Modifier.testTag("ai-model:$id")) { Text(id) }
                }
            } },
            confirmButton = { TextButton(enabled = state.running == null, onClick = { attempt { actions.fetchModels() } }) {
                Text(stringResource(Res.string.m6_refresh_models))
            } },
            dismissButton = { TextButton(onClick = { closeModels() }) { Text(stringResource(Res.string.m6_cancel)) } },
        )
        AiSettingsDialog.TARGET -> LanguageSelectionDialog(
            Res.string.m6_target_language, setOf(preferences[TranslatorTargetLangKey, "ENGLISH"]), multiple = false,
            dismiss = { dialog = null }, save = { selected ->
                attempt { controller.set(TranslatorTargetLangKey, selected.single()); dialog = null }
            },
        )
        AiSettingsDialog.TRANSLATION_EXCLUSIONS, AiSettingsDialog.ROMANIZATION_EXCLUSIONS -> {
            val key = if (dialog == AiSettingsDialog.TRANSLATION_EXCLUSIONS) AutoTranslateExcludedLanguagesKey else AiRomanizeExcludedLanguagesKey
            LanguageSelectionDialog(
                if (key == AutoTranslateExcludedLanguagesKey) Res.string.m6_translation_exclusions else Res.string.m6_romanization_exclusions,
                preferences[key, emptySet()], multiple = true, dismiss = { dialog = null },
                save = { selected -> attempt { controller.set(key, selected); dialog = null } },
            )
        }
        null -> Unit
    }
}

/** A fixed hint per safe failure kind. Provider text is never shown, so a key echoed in a response cannot reach the page. */
private fun aiFailureHint(kind: AiServiceFailureKind): StringResource = when (kind) {
    AiServiceFailureKind.AUTHENTICATION -> Res.string.m6_ai_hint_authentication
    AiServiceFailureKind.RATE_LIMITED -> Res.string.m6_ai_hint_rate_limited
    AiServiceFailureKind.UNREACHABLE -> Res.string.m6_ai_hint_unreachable
    AiServiceFailureKind.MODEL_UNAVAILABLE -> Res.string.m6_ai_hint_model_unavailable
    AiServiceFailureKind.UNEXPECTED_RESPONSE -> Res.string.m6_ai_hint_unexpected_response
    AiServiceFailureKind.OTHER -> Res.string.m6_ai_request_failed
}

@Composable
private fun LanguageSelectionDialog(
    title: StringResource, initial: Set<String>, multiple: Boolean,
    dismiss: () -> Unit, save: (Set<String>) -> Unit,
) {
    val options = remember { translatorLanguageOptions() }
    var selected by remember(title, initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(title)) },
        text = { LazyColumn(Modifier.heightIn(max = 400.dp)) {
            items(options, key = { it.value }) { option ->
                val checked = option.value in selected
                fun toggle() { selected = if (!multiple) setOf(option.value) else if (checked) selected - option.value else selected + option.value }
                Row(Modifier.fillMaxWidth().testTag("language:${option.value}").clickable { toggle() }) {
                    if (multiple) Checkbox(checked, onCheckedChange = { toggle() })
                    else RadioButton(checked, onClick = { toggle() })
                    Text(stringResource(option.label), Modifier.padding(top = 12.dp))
                }
            }
        } },
        confirmButton = { TextButton(enabled = multiple || selected.singleOrNull() in options.map { it.value },
            onClick = { save(selected) }) { Text(stringResource(Res.string.m6_save)) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(Res.string.m6_cancel)) } },
    )
}
