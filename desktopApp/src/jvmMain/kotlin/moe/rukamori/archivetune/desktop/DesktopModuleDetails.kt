package moe.rukamori.archivetune.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.desktop.integration.DesktopStrings
import moe.rukamori.archivetune.modules.*
import org.json.JSONObject

@Composable
internal fun DesktopModuleDetails(graph: DesktopSessionGraph, entry: DesktopModules.Entry, back: () -> Unit) {
    val manifest = entry.manifest
    val scope = rememberCoroutineScope()
    var values by remember(entry.id, entry.source) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var editing by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var operation by remember { mutableStateOf<Job?>(null) }
    var authenticated by remember(entry.id, entry.source) { mutableStateOf<Boolean?>(null) }
    var updateAddress by remember(entry.id) { mutableStateOf("") }
    fun run(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        operation = scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { failed = true; graph.report("Module details operation declined: ${failure.javaClass.simpleName}") }
            finally { busy = false }
        }
    }
    LaunchedEffect(entry.id, entry.source) {
        if (manifest.authentication) {
            try { authenticated = graph.modules.checkAuthorization(entry) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true }
        }
    }
    Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = back) { Text(DesktopStrings["desktop_modules_back"]) }
        Text(manifest.displayName.ifBlank { manifest.name }, style = MaterialTheme.typography.headlineSmall)
        Text(manifest.description)
        ModuleInformation("desktop_modules_identity", manifest.id)
        ModuleInformation("desktop_modules_version", manifest.version)
        ModuleInformation("desktop_modules_author", manifest.author)
        ModuleInformation("desktop_modules_license", manifest.license)
        ModuleInformation("desktop_modules_types", manifest.types.joinToString(", "))
        ModuleInformation("desktop_modules_labels", manifest.labels.joinToString(", "))
        ModuleInformation("desktop_modules_capabilities", manifest.capabilities.joinToString(", "))
        ModuleInformation("desktop_modules_min_version", manifest.minAppVersion)
        ModuleInformation("desktop_modules_runtime", manifest.requiredRuntimeFeatures.joinToString(", "))
        ModuleInformation("desktop_modules_origin", entry.origin)
        for ((label, url) in listOf("desktop_modules_homepage" to manifest.homepage, "desktop_modules_repository" to manifest.repository)) {
            if (url.isNotBlank()) TextButton(onClick = { openDesktopUri(url, graph::report) }) { Text(DesktopStrings[label]) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Switch(entry.enabled, { enabled -> run { graph.modules.enable(entry.id, enabled) } }, enabled = !busy)
            Text(DesktopStrings["desktop_modules_enabled"])
            TextButton(enabled = !busy && manifest.settings.isNotEmpty(), onClick = { run {
                values = graph.modules.settings(entry); editing = true
            } }) { Text(DesktopStrings["desktop_modules_settings"]) }
        }
        if (manifest.authentication) {
            HorizontalDivider()
            Text(DesktopStrings["desktop_modules_session"], style = MaterialTheme.typography.titleMedium)
            Text(DesktopStrings[when (authenticated) {
                true -> "desktop_modules_session_ready"
                false -> "desktop_modules_session_required"
                null -> "desktop_modules_session_unknown"
            }])
            Text(DesktopStrings["desktop_modules_session_browser"])
            Row {
                TextButton(enabled = !busy, onClick = { run { graph.modules.authorize(entry); authenticated = true } }) {
                    Text(DesktopStrings["desktop_modules_sign_in"])
                }
                TextButton(enabled = !busy, onClick = { run { authenticated = graph.modules.checkAuthorization(entry) } }) {
                    Text(DesktopStrings["desktop_modules_check_session"])
                }
                TextButton(enabled = !busy, onClick = { run { graph.modules.clearAuthorization(entry); authenticated = false } }) {
                    Text(DesktopStrings["desktop_modules_sign_out"])
                }
            }
        }
        HorizontalDivider()
        Text(DesktopStrings["desktop_modules_update"], style = MaterialTheme.typography.titleMedium)
        Text(DesktopStrings["desktop_modules_update_review"])
        OutlinedTextField(updateAddress, { updateAddress = it }, enabled = !busy,
            label = { Text(DesktopStrings["desktop_modules_update_url"]) }, modifier = Modifier.fillMaxWidth())
        TextButton(enabled = !busy && updateAddress.startsWith("https://"), onClick = { run {
            graph.modules.installUrl(updateAddress, updateId = entry.id)
        } }) { Text(DesktopStrings["desktop_modules_update"]) }
        if (busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(onClick = { operation?.cancel() }) { Text(DesktopStrings["cancel"]) }
        }
    }
    if (editing) AlertDialog(onDismissRequest = { if (!busy) editing = false }, title = { Text(manifest.name) },
        text = { Column(Modifier.heightIn(max = 450.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            manifest.settings.forEach { field -> ModuleSettingEditor(field, values[field.key] ?: field.defaultJson, !busy) {
                values = values + (field.key to it)
            } }
        } }, confirmButton = { TextButton(enabled = !busy && manifest.settings.all { it.acceptsValue(values[it.key] ?: it.defaultJson) },
            onClick = { run { graph.modules.saveSettings(entry, values); editing = false } }) { Text(DesktopStrings["ok"]) } },
        dismissButton = { TextButton(enabled = !busy, onClick = { editing = false }) { Text(DesktopStrings["cancel"]) } })
    if (failed) AlertDialog(onDismissRequest = { failed = false }, text = { Text(DesktopStrings["desktop_modules_failed"]) },
        confirmButton = { TextButton(onClick = { failed = false }) { Text(DesktopStrings["ok"]) } })
}

@Composable
private fun ModuleInformation(label: String, value: String) {
    if (value.isNotBlank()) Column {
        Text(DesktopStrings[label], style = MaterialTheme.typography.labelLarge)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ModuleSettingEditor(field: ModuleSetting, json: String, enabled: Boolean, change: (String) -> Unit) {
    when (field.type) {
        ModuleSettingType.BOOLEAN -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Switch(runCatching { parseJsonValue(json) == true }.getOrDefault(false), { change(it.toString()) }, enabled = enabled)
            Column { Text(field.label); Text(field.description, style = MaterialTheme.typography.bodySmall) }
        }
        ModuleSettingType.SELECT -> {
            var expanded by remember { mutableStateOf(false) }
            Column {
                Text(field.label)
                Text(field.description, style = MaterialTheme.typography.bodySmall)
                Box {
                    OutlinedButton(enabled = enabled, onClick = { expanded = true }) {
                        Text(field.options.firstOrNull { equivalentJsonValue(it.valueJson, json) }?.label ?: DesktopStrings["select"])
                    }
                    DropdownMenu(expanded, { expanded = false }) { field.options.forEach { option ->
                        DropdownMenuItem(text = { Text(option.label) }, onClick = { change(option.valueJson); expanded = false })
                    } }
                }
            }
        }
        ModuleSettingType.UNSUPPORTED -> Text(DesktopStrings["desktop_modules_setting_unsupported"])
        else -> {
            val number = field.type == ModuleSettingType.NUMBER
            val text = if (number) json.takeUnless { it == "null" }.orEmpty()
                else runCatching { parseJsonValue(json) as? String }.getOrNull().orEmpty()
            OutlinedTextField(text, { change(if (number) it.ifBlank { "null" } else JSONObject.quote(it)) }, enabled = enabled,
                label = { Text(field.label) }, supportingText = { Text(field.description) },
                isError = !field.acceptsValue(json), modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (field.secret) PasswordVisualTransformation() else VisualTransformation.None)
        }
    }
}
