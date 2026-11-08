package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.*
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Shared grouped preferences; the host owns package validation, credentials and source order. */
@Composable
fun ModuleDetailsSettings(
    id: String,
    name: String,
    metadata: JsonObject,
    configuration: JsonObject,
    enabled: Boolean,
    position: Int,
    count: Int,
    busy: Boolean,
    dirty: Boolean,
    issue: String?,
    globalEnabled: Boolean,
    onEnabled: (Boolean) -> Unit,
    onMove: (Int) -> Unit,
    onField: (String, JsonElement) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
) {
    var informationExpanded by remember(id) { mutableStateOf(false) }
    var editing by remember(id) { mutableStateOf<String?>(null) }
    val fields = (metadata["settings"] as? JsonObject).orEmpty()
    fun value(key: String, field: JsonObject) = configuration[key] ?: field["defaultValue"] ?: field["value"] ?: JsonNull
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) { Text(stringResource(Res.string.m4_back)) }
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
            ListItem(headlineContent = { Text(name, style = MaterialTheme.typography.headlineSmall) },
                supportingContent = { Text(stringResource(if (enabled) Res.string.shared_module_enabled else Res.string.shared_module_disabled)) },
                trailingContent = { Switch(enabled, onCheckedChange = onEnabled, enabled = !busy) })
        }
        if (!globalEnabled) Text(stringResource(Res.string.shared_module_global_off))
        issue?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        ModulePreferenceGroup(Res.string.shared_module_playback_source) {
            val sourceFields = fields.entries.filter { it.key != "quality" }.sortedBy { if (it.key == "mode") 0 else 1 }
            sourceFields.forEach { (key, raw) ->
                val field = raw as? JsonObject ?: return@forEach
                ModuleFieldPreference(key, field, value(key, field), !busy,
                    onToggle = { onField(key, JsonPrimitive(it)) }, onEdit = { editing = key })
            }
            ListItem(headlineContent = { Text(stringResource(Res.string.shared_module_account_lanes)) },
                supportingContent = { Text(stringResource(Res.string.shared_module_account_lanes_unavailable)) })
        }
        (fields["quality"] as? JsonObject)?.let { field ->
            ModulePreferenceGroup(Res.string.shared_module_quality) {
                ModuleFieldPreference("quality", field, value("quality", field), !busy,
                    onToggle = { onField("quality", JsonPrimitive(it)) }, onEdit = { editing = "quality" })
            }
        }
        ModulePreferenceGroup(Res.string.shared_module_priority) {
            ListItem(headlineContent = { Text(stringResource(Res.string.shared_module_position, position, count)) },
                supportingContent = { Text(stringResource(Res.string.shared_module_priority_description)) },
                trailingContent = { Row {
                    TextButton(enabled = !busy && position > 1, onClick = { onMove(-1) }) { Text(stringResource(Res.string.shared_module_earlier)) }
                    TextButton(enabled = !busy && position < count, onClick = { onMove(1) }) { Text(stringResource(Res.string.shared_module_later)) }
                } })
        }
        TextButton(onClick = { informationExpanded = !informationExpanded }) {
            Text(stringResource(if (informationExpanded) Res.string.shared_module_hide_information else Res.string.shared_module_information))
        }
        if (informationExpanded) ModulePreferenceGroup(Res.string.shared_module_information) {
            listOf("description", "version", "author", "license", "homepage", "repository").forEach { key ->
                (metadata[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { text ->
                    ListItem(headlineContent = { Text(text) })
                }
            }
        }
        Button(onClick = onSave, enabled = dirty && !busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(Res.string.shared_module_save))
        }
    }
    editing?.let { key -> (fields[key] as? JsonObject)?.let { field ->
        ModuleFieldDialog(key, field, value(key, field), { onField(key, it); editing = null }, { editing = null })
    } }
}

@Composable
private fun ModulePreferenceGroup(title: StringResource, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(stringResource(title), color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, bottom = 6.dp))
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) { Column(content = content) }
    }
}

private fun moduleOptionValue(option: JsonElement): JsonElement =
    (option as? JsonObject)?.let { it["value"] ?: it["id"] } ?: option
private fun moduleOptionLabel(option: JsonElement): String =
    (option as? JsonObject)?.get("label")?.jsonPrimitive?.content ?: (moduleOptionValue(option) as? JsonPrimitive)?.content.orEmpty()
private fun moduleSecret(key: String, field: JsonObject): Boolean =
    field["type"]?.jsonPrimitive?.content == "password" || field["secret"]?.jsonPrimitive?.booleanOrNull == true ||
        Regex("(?i)token|password|secret|credential|api.?key|cookie|authorization").containsMatchIn(key)
private val MODULE_BOOLEAN_TYPES = setOf("boolean", "switch", "toggle", "checkbox")
private val MODULE_SELECT_TYPES = setOf("select", "selector", "dropdown")

@Composable
private fun ModuleFieldPreference(key: String, field: JsonObject, value: JsonElement, enabled: Boolean,
    onToggle: (Boolean) -> Unit, onEdit: () -> Unit) {
    val type = field["type"]?.jsonPrimitive?.content ?: "text"
    val boolean = type in MODULE_BOOLEAN_TYPES
    ListItem(modifier = Modifier.clickable(enabled = enabled && !boolean, onClick = onEdit),
        headlineContent = { Text(field["label"]?.jsonPrimitive?.content ?: key) },
        supportingContent = { Column {
            if (!boolean) Text(when {
                moduleSecret(key, field) -> stringResource(if (value == JsonNull || value.jsonPrimitive.content.isBlank()) Res.string.shared_module_not_set else Res.string.shared_module_secret_set)
                type in MODULE_SELECT_TYPES -> (field["options"] as? JsonArray).orEmpty().firstOrNull { moduleOptionValue(it) == value }?.let(::moduleOptionLabel).orEmpty()
                else -> (value as? JsonPrimitive)?.contentOrNull?.takeUnless { value == JsonNull }.orEmpty()
            })
            field["description"]?.jsonPrimitive?.contentOrNull?.let { Text(it) }
        } }, trailingContent = { if (boolean) Switch((value as? JsonPrimitive)?.booleanOrNull == true, onToggle, enabled = enabled) })
}

@Composable
private fun ModuleFieldDialog(key: String, field: JsonObject, value: JsonElement,
    onConfirm: (JsonElement) -> Unit, onDismiss: () -> Unit) {
    val type = field["type"]?.jsonPrimitive?.content ?: "text"
    val select = type in MODULE_SELECT_TYPES
    var choice by remember(key) { mutableStateOf(value) }
    var text by remember(key) { mutableStateOf((value as? JsonPrimitive)?.contentOrNull?.takeUnless { value == JsonNull }.orEmpty()) }
    val number = type in setOf("number", "integer")
    val parsed = text.toDoubleOrNull()?.takeIf { it.isFinite() }
    val required = field["required"]?.jsonPrimitive?.booleanOrNull == true
    val valid = if (select) (field["options"] as? JsonArray).orEmpty().any { moduleOptionValue(it) == choice }
        else text.length <= 8192 && when {
            text.isBlank() -> !required
            number -> parsed != null && (type != "integer" || parsed % 1.0 == 0.0) &&
                (field["min"]?.jsonPrimitive?.doubleOrNull?.let { parsed >= it } ?: true) &&
                (field["max"]?.jsonPrimitive?.doubleOrNull?.let { parsed <= it } ?: true)
            else -> true
        }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(field["label"]?.jsonPrimitive?.content ?: key) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            if (select) (field["options"] as? JsonArray).orEmpty().forEach { option ->
                Row(Modifier.fillMaxWidth().clickable { choice = moduleOptionValue(option) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(choice == moduleOptionValue(option), { choice = moduleOptionValue(option) })
                    Column { Text(moduleOptionLabel(option)); (option as? JsonObject)?.get("description")?.jsonPrimitive?.contentOrNull?.let { Text(it, style = MaterialTheme.typography.bodySmall) } }
                }
            } else OutlinedTextField(text, { text = it }, singleLine = true, isError = !valid,
                visualTransformation = if (moduleSecret(key, field)) PasswordVisualTransformation() else VisualTransformation.None)
            field["description"]?.jsonPrimitive?.contentOrNull?.let { Text(it) }
        } }, confirmButton = { TextButton(enabled = valid, onClick = {
            onConfirm(if (select) choice else if (number) parsed?.let(::JsonPrimitive) ?: JsonNull else JsonPrimitive(text))
        }) { Text(stringResource(Res.string.m6_done)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.m6_cancel)) } })
}
