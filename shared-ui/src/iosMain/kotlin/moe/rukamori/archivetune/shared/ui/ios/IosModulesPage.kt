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
    moe.rukamori.archivetune.shared.ui.settings.ModulesSettings(
        modules = modules.map { module -> moe.rukamori.archivetune.shared.ui.settings.SettingsModule(
            module.id, module.name, enabled[module.id] == true, status[module.id]?.let { moduleIssueText(it) }) },
        enabled = prefs?.get(ModulesEnabledKey) == true,
        busy = busy,
        issue = issue?.let { moduleIssueText(it) },
        onEnabled = { value -> action { graph.settings.edit { this[ModulesEnabledKey] = value } } },
        onImport = { action {
            val file = host.files.pickDocument(host.viewController) ?: return@action
            graph.modules.install(host.files.read(file, 4L * 1024 * 1024)); refresh()
        } },
        onConfigure = { id -> action {
            config = Json.parseToJsonElement(graph.modules.configuration(id)).jsonObject
            savedConfig = config; editing = modules.first { it.id == id }
        } },
        onModuleEnabled = { id, value -> action { graph.modules.setEnabled(id, value); refresh() } },
        onMove = { id, delta -> action { graph.modules.move(id, delta); refresh() } },
        onRemove = { id -> action { graph.modules.remove(id); refresh() } },
    )
}

@Composable
private fun moduleIssueText(issue: IosModuleIssue): String = stringResource(when (issue) {
    IosModuleIssue.INVALID_PACKAGE -> Res.string.ios_modules_invalid
    IosModuleIssue.UNSUPPORTED_CONTRACT -> Res.string.ios_modules_unsupported
    IosModuleIssue.INVALID_CONFIGURATION -> Res.string.ios_modules_bad_configuration
    IosModuleIssue.EXECUTION_FAILED -> Res.string.ios_modules_execution_failed
    IosModuleIssue.UNSUPPORTED_STREAM -> Res.string.ios_modules_unsupported_stream
})
