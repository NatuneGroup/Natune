package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Single- or multi-line entry used by the settings pages. A masked entry hides typed text. Confirm stays disabled until
 * [isValid] accepts the text, and the caller receives the text only after confirm.
 */
@Composable
internal fun SettingsTextEntryDialog(
    title: StringResource,
    placeholder: StringResource,
    multiline: Boolean,
    masked: Boolean,
    description: StringResource?,
    isValid: (String) -> Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = !multiline,
                    minLines = if (multiline) 3 else 1,
                    placeholder = { Text(stringResource(placeholder)) },
                    visualTransformation = if (masked) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (masked) KeyboardType.Password else KeyboardType.Uri,
                    ),
                )
                description?.let { Text(stringResource(it)) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = isValid(text),
                onClick = {
                    onConfirm(text)
                    onDismiss()
                },
            ) { Text(stringResource(Res.string.m6_done)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.m6_cancel)) } },
    )
}

/** Label and value pair for read-only detail dialogs. */
@Composable
internal fun SettingsDetailField(label: String, value: String) {
    Column(modifier = Modifier.padding(bottom = 10.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
