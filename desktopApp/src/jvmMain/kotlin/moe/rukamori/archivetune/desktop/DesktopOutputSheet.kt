package moe.rukamori.archivetune.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.desktop.integration.DesktopStrings
import moe.rukamori.archivetune.desktop.playback.DesktopOutputDevice
import moe.rukamori.archivetune.shared.domain.settings.BitPerfectUsbOutputKey
import moe.rukamori.archivetune.shared.domain.settings.DesktopOutputDeviceIdKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceEditor
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.UsbExclusiveAudioKey
import moe.rukamori.archivetune.shared.domain.settings.setPlaybackOutputPreference
import moe.rukamori.archivetune.shared.ui.resources.*

/** The player's output control and the sheet it opens. Owned by the host bindings, not by a page. */
internal class DesktopOutputSheetState {
    var open by mutableStateOf(false)
}

/**
 * The player's OUTPUT action: the same device picker and exclusive switch the playback settings page owns, over the
 * live sink status. Every write goes through the one settings store, so the playback owner reacts exactly as it does
 * to the settings row; the sheet never holds a second copy of the selection.
 */
@Composable
internal fun DesktopOutputActionButton(
    state: DesktopOutputSheetState,
    modifier: Modifier,
    tint: Color,
    enabled: Boolean,
) {
    IconButton(onClick = { if (enabled) state.open = true }, modifier = modifier, enabled = enabled) {
        Icon(
            painter = painterResource(Res.drawable.solar_cast),
            contentDescription = stringResource(Res.string.m5_output),
            tint = tint,
        )
    }
}

@Composable
private fun DesktopOutputExclusiveRow(exclusive: Boolean, busy: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(Res.string.desktop_exclusive_output))
            Text(stringResource(Res.string.m6_usb_exclusions), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = exclusive, onCheckedChange = onChange, enabled = !busy)
    }
}

@Composable
private fun DesktopOutputDeviceList(
    loading: Boolean,
    devices: List<DesktopOutputDevice>,
    selectedId: String?,
    busy: Boolean,
    onSelect: (String) -> Unit,
) {
    when {
        loading -> CircularProgressIndicator(Modifier.padding(8.dp))
        devices.isEmpty() -> Text(DesktopStrings["desktop_output_no_devices"])
        else -> devices.forEach { device ->
            ListItem(
                headlineContent = { Text(device.name) },
                trailingContent = {
                    RadioButton(
                        selected = selectedId == device.id,
                        enabled = !busy,
                        onClick = { onSelect(device.id) },
                    )
                },
            )
        }
    }
}

@Composable
internal fun DesktopOutputSheet(state: DesktopOutputSheetState, graph: DesktopSessionGraph) {
    if (!state.open) return
    val saved by graph.settings.snapshots.collectAsState()
    val output by graph.outputState.collectAsState()
    val preferences = saved ?: PreferenceSnapshot.EMPTY
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<DesktopOutputDevice>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            devices = graph.outputDevices().distinctBy { it.id }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The empty state below names the missing native device list.
        } catch (_: LinkageError) {
            // A host without the engine library has no physical sinks to offer.
        } finally {
            loading = false
        }
    }

    fun commit(change: PreferenceEditor.() -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                graph.settings.edit(change)
                graph.refreshOutputSelection()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The live sink status keeps reporting the last accepted selection.
            } finally {
                busy = false
            }
        }
    }

    fun select(id: String) = commit { set(DesktopOutputDeviceIdKey, id) }
    val exclusive = preferences[UsbExclusiveAudioKey, false] || preferences[BitPerfectUsbOutputKey, false]
    fun setExclusive(enabled: Boolean) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                if (enabled) {
                    graph.settings.edit { setPlaybackOutputPreference(UsbExclusiveAudioKey, true) }
                } else {
                    graph.settings.edit {
                        set(UsbExclusiveAudioKey, false)
                        set(BitPerfectUsbOutputKey, false)
                    }
                }
                graph.refreshOutputSelection()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The live sink status keeps reporting the last accepted selection.
            } finally {
                busy = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) state.open = false },
        title = { Text(DesktopStrings["desktop_output_device"]) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DesktopOutputStatus(output)
                DesktopOutputExclusiveRow(exclusive, busy) { setExclusive(it) }
                Text(DesktopStrings["desktop_output_device_scope"], style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(enabled = !busy, onClick = { select("") }) {
                    Text(DesktopStrings["desktop_output_default_device"])
                }
                DesktopOutputDeviceList(loading, devices, preferences[DesktopOutputDeviceIdKey], busy) { select(it) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = { state.open = false }) { Text(DesktopStrings["close"]) }
        },
    )
}
