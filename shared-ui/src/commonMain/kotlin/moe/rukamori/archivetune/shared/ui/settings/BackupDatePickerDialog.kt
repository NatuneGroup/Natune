package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupDatePickerDialog(initialEpochDay: Long?, earliestEpochDay: Long, onSelect: (Long?) -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initialEpochDay?.times(86_400_000L))
    val selected = state.selectedDateMillis?.div(86_400_000L)
    DatePickerDialog(onDismissRequest = { onSelect(null) },
        confirmButton = { TextButton(enabled = selected != null && selected >= earliestEpochDay, onClick = { onSelect(selected) }) {
            Text(stringResource(Res.string.m6_save))
        } }, dismissButton = { TextButton(onClick = { onSelect(null) }) { Text(stringResource(Res.string.m6_cancel)) } }) {
        DatePicker(state)
    }
}
