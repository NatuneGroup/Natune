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
import java.nio.file.Files
import kotlinx.coroutines.*
import moe.rukamori.archivetune.desktop.integration.DesktopFilePickers
import moe.rukamori.archivetune.desktop.integration.DesktopStrings
import moe.rukamori.archivetune.modules.ModuleCatalogEntry
import moe.rukamori.archivetune.shared.domain.settings.ModulesEnabledKey

/** Uses the desktop settings surface and the retained module reader, catalog and runtime. */
@Composable
internal fun DesktopModulesPage(graph: DesktopSessionGraph) {
    val entries by graph.modules.entries.collectAsState()
    val preferences by graph.settings.snapshots.collectAsState()
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf("") }
    var catalog by remember { mutableStateOf<List<ModuleCatalogEntry>>(emptyList()) }
    var editing by remember { mutableStateOf<DesktopModules.Entry?>(null) }
    var values by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var failed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    fun run(action: suspend () -> Unit) {
        scope.launch {
            busy = true
            try { action() } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { failed = true; graph.report("Module operation declined: ${failure.javaClass.simpleName}") }
            finally { busy = false }
        }
    }
    Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(DesktopStrings["desktop_modules_title"], style = MaterialTheme.typography.headlineSmall)
        Text(DesktopStrings["desktop_modules_scope"])
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Switch(preferences?.get(ModulesEnabledKey, false) == true,
                { enabled -> run { graph.settings.edit { this[ModulesEnabledKey] = enabled } } })
            Text(DesktopStrings["desktop_modules_enable"])
            Button(enabled = !busy, onClick = { run {
                val file = DesktopFilePickers().use { it.files(false).singleOrNull() } ?: return@run
                val bytes = withContext(Dispatchers.IO) { require(Files.size(file) <= 2 * 1024 * 1024); Files.readAllBytes(file) }
                graph.modules.install(bytes)
            } }) { Text(DesktopStrings["desktop_modules_install"]) }
        }
        OutlinedTextField(address, { address = it }, label = { Text(DesktopStrings["desktop_modules_catalog"]) }, modifier = Modifier.fillMaxWidth())
        TextButton(enabled = !busy && address.startsWith("https://"), onClick = { run { catalog = graph.modules.catalog(address).entries } }) {
            Text(DesktopStrings["desktop_modules_load_catalog"])
        }
        catalog.forEach { candidate ->
            ListItem(headlineContent = { Text("${candidate.name} ${candidate.version}") }, supportingContent = { Text(candidate.description) },
                trailingContent = { TextButton(enabled = candidate.supported && candidate.downloadUrl != null && !busy,
                    onClick = { run { graph.modules.installUrl(requireNotNull(candidate.downloadUrl), candidate.sha256) } }) {
                    Text(DesktopStrings["desktop_modules_install"])
                } })
        }
        entries.forEach { entry ->
            HorizontalDivider()
            Text("${entry.manifest.name} ${entry.manifest.version}", style = MaterialTheme.typography.titleMedium)
            Text(entry.manifest.description)
            Text(entry.manifest.capabilities.joinToString(", "), style = MaterialTheme.typography.bodySmall)
            Row {
                Switch(entry.enabled, { enabled -> run { graph.modules.enable(entry.id, enabled) } }, enabled = !busy)
                TextButton(enabled = !busy, onClick = { run { values = graph.modules.settings(entry); editing = entry } }) {
                    Text(DesktopStrings["desktop_modules_settings"])
                }
                TextButton(enabled = !busy, onClick = { run { graph.modules.remove(entry.id) } }) { Text(DesktopStrings["desktop_modules_remove"]) }
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    editing?.let { entry -> AlertDialog(onDismissRequest = { editing = null }, title = { Text(entry.manifest.name) },
        text = { Column(Modifier.heightIn(max = 450.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            entry.manifest.settings.forEach { field ->
                OutlinedTextField(values[field.key].orEmpty(), { values = values + (field.key to it) }, label = { Text(field.label) },
                    supportingText = { Text(field.description) }, visualTransformation = if (field.secret) PasswordVisualTransformation() else VisualTransformation.None)
            }
        } }, confirmButton = { TextButton(onClick = { run { graph.modules.saveSettings(entry, values); editing = null } }) { Text(DesktopStrings["ok"]) } },
        dismissButton = { TextButton(onClick = { editing = null }) { Text(DesktopStrings["cancel"]) } }) }
    if (failed) AlertDialog(onDismissRequest = { failed = false }, text = { Text(DesktopStrings["desktop_modules_failed"]) },
        confirmButton = { TextButton(onClick = { failed = false }) { Text(DesktopStrings["ok"]) } })
}
