package moe.rukamori.archivetune.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.desktop.integration.DesktopStrings
import moe.rukamori.archivetune.shared.domain.settings.BooleanPreferenceKey
import moe.rukamori.archivetune.shared.domain.settings.PauseListenHistoryKey
import moe.rukamori.archivetune.shared.domain.settings.PauseSearchHistoryKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.ui.settings.SettingsController

/**
 * The Android Privacy page's history group. Pausing listen history stops the listen driver from recording; clearing
 * removes every stored listen, which also resets the statistics built from them. This host records no search history,
 * so the search pause is kept only for the Android app and backups.
 */
@Composable
internal fun DesktopHistoryControls(
    preferences: PreferenceSnapshot,
    controller: SettingsController,
    clearListenHistory: suspend () -> Unit,
    show: (String) -> Unit,
    onFailure: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var confirmClear by remember { mutableStateOf(false) }
    fun run(change: suspend () -> Unit, done: () -> Unit = {}) {
        scope.launch {
            try {
                change()
                done()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                onFailure()
            }
        }
    }
    @Composable
    fun toggle(anchor: String, labelKey: String, key: BooleanPreferenceKey) {
        val checked = preferences[key, false]
        ListItem(
            modifier = Modifier.testTag("setting:$anchor").clickable { run({ controller.edit { this[key] = !checked } }) },
            headlineContent = { Text(DesktopSettingsText[labelKey]) },
            trailingContent = {
                Switch(checked = checked, onCheckedChange = { value -> run({ controller.edit { this[key] = value } }) })
            },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(DesktopSettingsText["history_clear_listen"]) },
            text = { Text(DesktopSettingsText["history_clear_listen_confirm"]) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    run(clearListenHistory) { show(DesktopSettingsText["history_listen_cleared"]) }
                }) { Text(DesktopSettingsText["history_clear_confirm_action"]) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(DesktopStrings["cancel"]) } },
        )
    }
    Column {
        Text(DesktopSettingsText["history_listen_group"], style = MaterialTheme.typography.titleSmall)
        toggle("pause_listen_history", "history_pause_listen", PauseListenHistoryKey)
        ListItem(
            modifier = Modifier.testTag("setting:clear_listen_history").clickable { confirmClear = true },
            headlineContent = { Text(DesktopSettingsText["history_clear_listen"]) },
        )
        Text(DesktopSettingsText["history_search_group"], style = MaterialTheme.typography.titleSmall)
        toggle("pause_search_history", "history_pause_search", PauseSearchHistoryKey)
    }
}
