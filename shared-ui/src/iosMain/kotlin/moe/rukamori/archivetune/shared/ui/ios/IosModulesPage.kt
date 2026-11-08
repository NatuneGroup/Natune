package moe.rukamori.archivetune.shared.ui.ios

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
import moe.rukamori.archivetune.shared.ui.settings.ModuleDetailsSettings
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
    var enabled by remember(graph) { mutableStateOf(emptyMap<String, Boolean>()) }
    suspend fun refresh() {
        val installed = withContext(Dispatchers.Default) { graph.modules.installed() }
        val states = installed.associate { it.id to graph.modules.isEnabled(it.id) }
        modules = installed; enabled = states
    }
    LaunchedEffect(graph) { refresh() }
    fun action(block: suspend () -> Unit) { if (busy) return; scope.launch {
        busy = true; issue = null
        try { block() } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            issue = (error as? IosModuleFailure)?.issue ?: IosModuleIssue.INVALID_PACKAGE
        } finally { busy = false }
    } }
    var editing by remember { mutableStateOf<IosModuleInfo?>(null) }
    var config by remember { mutableStateOf<JsonObject>(JsonObject(emptyMap())) }
    var savedConfig by remember { mutableStateOf(config) }
    val module = editing
    if (module != null) {
        ModuleDetailsSettings(module.id, module.name, module.metadata, config,
            enabled[module.id] == true, modules.indexOfFirst { it.id == module.id } + 1, modules.size,
            busy, config != savedConfig, issue?.let { moduleIssueText(it) } ?: status[module.id]?.let { moduleIssueText(it) },
            prefs?.get(ModulesEnabledKey) == true,
            onEnabled = { action { graph.modules.setEnabled(module.id, it); refresh() } },
            onMove = { action { graph.modules.move(module.id, it); refresh() } },
            onField = { key, value -> config = JsonObject(config + (key to value)) },
            onSave = { action { graph.modules.configure(module.id, config.toString()); savedConfig = config } },
            onBack = { editing = null; issue = null })
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Text(stringResource(Res.string.ios_modules_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(Res.string.ios_modules_contracts))
        issue?.let { Text(moduleIssueText(it), color = MaterialTheme.colorScheme.error) }
        Row { Text(stringResource(Res.string.ios_modules_enable)); Switch(prefs?.get(ModulesEnabledKey) == true,
            onCheckedChange = { scope.launch { graph.settings.edit { this[ModulesEnabledKey] = it } } }) }
        Button(enabled = !busy, onClick = { action {
                val file = host.files.pickDocument(host.viewController) ?: return@action
                graph.modules.install(host.files.read(file, 4L * 1024 * 1024))
                refresh()
        } }) { Text(stringResource(Res.string.ios_modules_import)) }
        modules.forEach { module ->
            ListItem(headlineContent = { Text(module.name) }, supportingContent = { Column { Text(module.id); status[module.id]?.let { Text(moduleIssueText(it), color = MaterialTheme.colorScheme.error) } } },
                trailingContent = { Row {
                    TextButton(enabled = !busy, onClick = { action {
                        config = Json.parseToJsonElement(graph.modules.configuration(module.id)).jsonObject; savedConfig = config; editing = module
                    } }) { Text(stringResource(Res.string.ios_modules_configure)) }
                    TextButton(enabled = !busy, onClick = { action {
                        graph.modules.remove(module.id); refresh()
                    } }) { Text(stringResource(Res.string.ios_modules_remove)) }
                } })
        }
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
