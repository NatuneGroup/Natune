package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.stringResource

/** Behavior page in Android order; destructive operations run only after the shared confirmation. */
@Composable
fun PrivacySettings(
    controller: SettingsController,
    resetListeningStats: suspend () -> Unit,
    onFailure: () -> Unit,
    scrollTo: String? = null,
    listenerPresence: @Composable () -> Unit,
) {
    val snapshot by controller.store.snapshots.collectAsState()
    val preferences = snapshot ?: run { CircularProgressIndicator(); return }
    var resetRequested by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    SettingsPage(listOf(
        SettingsRow.Toggle("pause_listen_history", Res.string.m6_settings_desktop_pause_listen, PauseListenHistoryKey, false),
        SettingsRow.Action("reset_listening_stats", Res.string.phone_reference_reset_listening_stats, { resetRequested = true }, enabled = !busy),
        SettingsRow.Toggle("network_metered", Res.string.m6_low_data, NetworkMeteredKey, PrivacyPreferenceDefaults.LOW_DATA_MODE),
    ), preferences, controller, scrollTo, onFailure = onFailure,
        extraItems = { item(key = "listener_presence") { listenerPresence() } })
    if (resetRequested) {
        AlertDialog(onDismissRequest = { if (!busy) resetRequested = false },
            title = { Text(stringResource(Res.string.phone_reference_reset_listening_stats)) },
            text = { Text(stringResource(Res.string.phone_reset_listening_stats_confirm)) },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    try { resetListeningStats(); resetRequested = false }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { onFailure() }
                    finally { busy = false }
                }
            }) { Text(stringResource(Res.string.m6_integration_ok)) } },
            dismissButton = { TextButton(enabled = !busy, onClick = { resetRequested = false }) { Text(stringResource(Res.string.m6_cancel)) } })
    }
}
