package moe.rukamori.archivetune.ui.component

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue

/** Existing settings editor, shared by text, credential and decimal/nullable numeric preferences. */
@Composable
internal fun PreferenceTextEditor(
    modifier: Modifier = Modifier,
    title: @Composable () -> Unit,
    icon: (@Composable () -> Unit)? = null,
    value: String,
    onValueChange: (String) -> Unit,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
    isInputValid: (String) -> Boolean = { true },
    isEnabled: Boolean = true,
    masked: Boolean = false,
    description: String? = null,
) {
    var showDialog by remember {
        mutableStateOf(false)
    }

    if (showDialog) {
        TextFieldDialog(
            title = title,
            initialTextFieldValue =
                TextFieldValue(
                    text = value,
                    selection = TextRange(value.length),
                ),
            singleLine = singleLine,
            keyboardOptions = keyboardOptions,
            isInputValid = isInputValid,
            masked = masked,
            onDone = onValueChange,
            onDismiss = { showDialog = false },
        )
    }

    PreferenceEntry(
        modifier = modifier,
        title = title,
        description = description ?: if (masked && value.isNotEmpty()) "••••••••" else value,
        icon = icon,
        onClick = { showDialog = true },
        isEnabled = isEnabled,
    )
}

/** Preserve a module's numeric JSON text, including an unset optional value and decimals. */
@Composable
fun NumberEditTextPreference(
    modifier: Modifier = Modifier,
    title: @Composable () -> Unit,
    icon: (@Composable () -> Unit)? = null,
    value: String,
    onValueChange: (String) -> Unit,
    isInputValid: (String) -> Boolean = { it.toDoubleOrNull()?.isFinite() == true },
    isEnabled: Boolean = true,
    integerOnly: Boolean = false,
    description: String? = null,
) = EditTextPreference(modifier = modifier, title = title, icon = icon, value = value,
    onValueChange = onValueChange, isInputValid = isInputValid, isEnabled = isEnabled, description = description,
    keyboardOptions = KeyboardOptions(keyboardType = if (integerOnly) KeyboardType.Number else KeyboardType.Decimal,
        imeAction = ImeAction.Done))
