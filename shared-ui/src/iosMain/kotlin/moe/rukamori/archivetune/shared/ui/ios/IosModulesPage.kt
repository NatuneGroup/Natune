package moe.rukamori.archivetune.shared.ui.ios

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import moe.rukamori.archivetune.shared.data.modules.IosModuleIssue
import moe.rukamori.archivetune.shared.data.modules.IosModuleFailure
import kotlinx.serialization.json.*
import moe.rukamori.archivetune.shared.data.modules.IosModuleInfo
import moe.rukamori.archivetune.shared.domain.settings.ModulesEnabledKey
import moe.rukamori.archivetune.shared.ui.IosHost
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun IosModulesPage(host: IosHost, graph: IosSessionGraph) {
    val scope = rememberCoroutineScope()
    val prefs by graph.settings.snapshots.collectAsState()
    var modules by remember(graph) { mutableStateOf(emptyList<IosModuleInfo>()) }
    val status by graph.modules.status.collectAsState()
    var issue by remember { mutableStateOf<IosModuleIssue?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(graph) { modules = withContext(Dispatchers.Default) { graph.modules.installed() } }
    fun action(block: suspend () -> Unit) { scope.launch {
        busy = true; issue = null
        try { block() } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            issue = (error as? IosModuleFailure)?.issue ?: IosModuleIssue.INVALID_PACKAGE
        } finally { busy = false }
    } }
    var editing by remember { mutableStateOf<IosModuleInfo?>(null) }
    var config by remember { mutableStateOf<JsonObject>(JsonObject(emptyMap())) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Text(stringResource(Res.string.ios_modules_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(Res.string.ios_modules_contracts))
        issue?.let { Text(moduleIssueText(it), color = MaterialTheme.colorScheme.error) }
        Row { Text(stringResource(Res.string.ios_modules_enable)); Switch(prefs?.get(ModulesEnabledKey) == true,
            onCheckedChange = { scope.launch { graph.settings.edit { this[ModulesEnabledKey] = it } } }) }
        Button(enabled = !busy, onClick = { action {
                val file = host.files.pickDocument(host.viewController) ?: return@action
                graph.modules.install(host.files.read(file, 4L * 1024 * 1024))
                modules = withContext(Dispatchers.Default) { graph.modules.installed() }
        } }) { Text(stringResource(Res.string.ios_modules_import)) }
        modules.forEach { module ->
            ListItem(headlineContent = { Text(module.name) }, supportingContent = { Column { Text(module.id); status[module.id]?.let { Text(moduleIssueText(it), color = MaterialTheme.colorScheme.error) } } },
                trailingContent = { Row {
                    TextButton(enabled = !busy, onClick = { action {
                        config = Json.parseToJsonElement(graph.modules.configuration(module.id)).jsonObject; editing = module
                    } }) { Text(stringResource(Res.string.ios_modules_configure)) }
                    TextButton(enabled = !busy, onClick = { action {
                        graph.modules.remove(module.id); modules = withContext(Dispatchers.Default) { graph.modules.installed() }
                    } }) { Text(stringResource(Res.string.ios_modules_remove)) }
                } })
        }
    }
    editing?.let { module ->
        AlertDialog(onDismissRequest = { editing = null }, title = { Text(module.name) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                issue?.let { Text(moduleIssueText(it), color = MaterialTheme.colorScheme.error) }
                (module.metadata["settings"] as? JsonObject).orEmpty().forEach { (name, raw) ->
                    val field = raw as? JsonObject ?: return@forEach
                    val label = field["label"]?.jsonPrimitive?.content ?: name
                    val type = field["type"]?.jsonPrimitive?.content ?: "text"
                    val value = config[name] ?: field["defaultValue"] ?: field["value"] ?: JsonNull
                    if (type in setOf("boolean", "switch", "toggle", "checkbox")) Row {
                        Text(label); Switch((value as? JsonPrimitive)?.booleanOrNull == true,
                            { config = JsonObject(config + (name to JsonPrimitive(it))) })
                    } else if (type in setOf("select", "selector", "dropdown")) {
                        Text(label)
                        (field["options"] as? JsonArray).orEmpty().forEach { option ->
                            val choice = (option as? JsonObject)?.get("value") ?: option
                            val text = (option as? JsonObject)?.get("label")?.jsonPrimitive?.content ?: choice.toString()
                            Row { RadioButton(selected = value == choice, onClick = { config = JsonObject(config + (name to choice)) }); Text(text) }
                        }
                    } else OutlinedTextField((value as? JsonPrimitive)?.contentOrNull.orEmpty(), { text ->
                        val stored = if (type in setOf("number", "integer")) text.toDoubleOrNull()?.let(::JsonPrimitive) ?: JsonNull else JsonPrimitive(text)
                        config = JsonObject(config + (name to stored))
                    }, label = { Text(label) }, singleLine = true,
                        visualTransformation = if (type == "password" || field["secret"]?.jsonPrimitive?.booleanOrNull == true || Regex("(?i)token|password|secret|credential|api.?key|cookie|authorization").containsMatchIn(name))
                            PasswordVisualTransformation() else VisualTransformation.None)
                }
            } }, confirmButton = { TextButton(enabled = !busy, onClick = { action {
                graph.modules.configure(module.id, config.toString()); editing = null
            } }) { Text(stringResource(Res.string.ios_lyrics_save)) } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(Res.string.m6_cancel)) } })
    }
}

@Composable
private fun moduleIssueText(issue: IosModuleIssue): String = stringResource(when (issue) {
    IosModuleIssue.INVALID_PACKAGE -> Res.string.ios_modules_invalid
    IosModuleIssue.UNSUPPORTED_CONTRACT -> Res.string.ios_modules_unsupported
    IosModuleIssue.INVALID_CONFIGURATION -> Res.string.ios_modules_bad_configuration
    IosModuleIssue.EXECUTION_FAILED -> Res.string.ios_modules_execution_failed
    IosModuleIssue.UNSUPPORTED_STREAM -> Res.string.ios_modules_unsupported_stream
})
