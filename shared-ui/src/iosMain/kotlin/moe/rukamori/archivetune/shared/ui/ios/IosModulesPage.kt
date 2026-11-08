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
import kotlinx.serialization.json.*
import moe.rukamori.archivetune.shared.data.modules.IosModuleInfo
import moe.rukamori.archivetune.shared.domain.settings.ModulesEnabledKey
import moe.rukamori.archivetune.shared.ui.IosHost
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun IosModulesPage(host: IosHost, graph: IosSessionGraph, onFailure: () -> Unit) {
    val scope = rememberCoroutineScope()
    val prefs by graph.settings.snapshots.collectAsState()
    var modules by remember(graph) { mutableStateOf(graph.modules.installed()) }
    var editing by remember { mutableStateOf<IosModuleInfo?>(null) }
    var config by remember { mutableStateOf<JsonObject>(JsonObject(emptyMap())) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Text(stringResource(Res.string.ios_modules_title), style = MaterialTheme.typography.headlineSmall)
        Row { Text(stringResource(Res.string.ios_modules_enable)); Switch(prefs?.get(ModulesEnabledKey) == true,
            onCheckedChange = { scope.launch { graph.settings.edit { this[ModulesEnabledKey] = it } } }) }
        Button(onClick = { scope.launch {
            try {
                val file = host.files.pickDocument(host.viewController) ?: return@launch
                graph.modules.install(host.files.read(file, 4L * 1024 * 1024))
                modules = graph.modules.installed()
            } catch (_: Exception) { onFailure() }
        } }) { Text(stringResource(Res.string.ios_modules_import)) }
        modules.forEach { module ->
            ListItem(headlineContent = { Text(module.name) }, supportingContent = { Text(module.id) },
                trailingContent = { Row {
                    TextButton(onClick = { scope.launch {
                        config = Json.parseToJsonElement(graph.modules.configuration(module.id)).jsonObject; editing = module
                    } }) { Text(stringResource(Res.string.ios_modules_configure)) }
                    TextButton(onClick = { scope.launch {
                        graph.modules.remove(module.id); modules = graph.modules.installed()
                    } }) { Text(stringResource(Res.string.ios_modules_remove)) }
                } })
        }
    }
    editing?.let { module ->
        AlertDialog(onDismissRequest = { editing = null }, title = { Text(module.name) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
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
                            TextButton(onClick = { config = JsonObject(config + (name to choice)) }) { Text(text) }
                        }
                    } else OutlinedTextField((value as? JsonPrimitive)?.contentOrNull.orEmpty(), { text ->
                        val stored = if (type in setOf("number", "integer")) text.toDoubleOrNull()?.let(::JsonPrimitive) ?: JsonNull else JsonPrimitive(text)
                        config = JsonObject(config + (name to stored))
                    }, label = { Text(label) }, singleLine = true,
                        visualTransformation = if (type == "password" || field["secret"]?.jsonPrimitive?.booleanOrNull == true)
                            PasswordVisualTransformation() else VisualTransformation.None)
                }
            } }, confirmButton = { TextButton(onClick = { scope.launch {
                try { graph.modules.configure(module.id, config.toString()); editing = null } catch (_: Exception) { onFailure() }
            } }) { Text(stringResource(Res.string.ios_lyrics_save)) } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(Res.string.m6_cancel)) } })
    }
}
