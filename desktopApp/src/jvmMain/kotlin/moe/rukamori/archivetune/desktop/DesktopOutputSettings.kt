package moe.rukamori.archivetune.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.desktop.integration.DesktopStrings
import moe.rukamori.archivetune.desktop.playback.DesktopExclusiveOutputPolicy
import moe.rukamori.archivetune.desktop.playback.DesktopOutputDevice
import moe.rukamori.archivetune.desktop.playback.DesktopOutputState
import moe.rukamori.archivetune.shared.domain.settings.DesktopOutputDeviceIdKey
import moe.rukamori.archivetune.shared.ui.resources.*
import moe.rukamori.archivetune.shared.ui.settings.*

internal object DesktopOutputCapabilities {
    val supported = System.getProperty("os.name").lowercase().let { it.startsWith("windows") || it.contains("linux") || it.contains("mac") }
}

@Composable
internal fun DesktopPlaybackOutputSettings(
    graph: DesktopSessionGraph,
    controller: SettingsController,
    navigate: (String) -> Unit,
    arrangeArtworkProviders: () -> Unit,
    managePlaylistTags: () -> Unit,
    onFailure: () -> Unit,
    scrollTo: String?,
    onOpenEqualizer: () -> Unit,
) {
    val preferences by controller.store.snapshots.collectAsState()
    val output by graph.outputState.collectAsState()
    val saved = preferences ?: run { CircularProgressIndicator(); return }
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<DesktopOutputDevice>>(emptyList()) }
    var picker by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val base = playbackSettingsRows(saved, DesktopOutputCapabilities.supported, output.exclusiveActive,
        DesktopOutputCapabilities.supported, navigate, arrangeArtworkProviders, managePlaylistTags,
        onOpenEqualizer = onOpenEqualizer, desktop = true)
    val deviceRow = SettingsRow.Action("output_device", Res.string.desktop_output_device, run = {
        if (!busy) {
            busy = true
            scope.launch {
                try { devices = graph.outputDevices().distinctBy { it.id }; picker = true }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { onFailure() }
                catch (_: LinkageError) { onFailure() }
                finally { busy = false }
            }
        }
    }, enabled = !busy)
    val statusRow = SettingsRow.Custom("active_sink", Res.string.desktop_output_status) { DesktopOutputStatus(output) }
    SettingsPage(base.take(1) + listOf(deviceRow, statusRow) + base.drop(1), saved, controller,
        scrollTo = scrollTo, onFailure = onFailure, onCommitted = { graph.refreshOutputSelection() })
    if (picker) AlertDialog(onDismissRequest = { if (!busy) picker = false }, title = { Text(DesktopStrings["desktop_output_device"]) },
        text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
            Text(DesktopStrings["desktop_output_device_scope"])
            fun select(id: String) {
                if (busy) return
                busy = true
                scope.launch {
                    try { controller.set(DesktopOutputDeviceIdKey, id); graph.refreshOutputSelection(); picker = false }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { onFailure() }
                    finally { busy = false }
                }
            }
            TextButton(enabled = !busy, onClick = { select("") }) { Text(DesktopStrings["desktop_output_default_device"]) }
            devices.forEach { device -> ListItem(headlineContent = { Text(device.name) },
                trailingContent = { RadioButton(selected = saved[DesktopOutputDeviceIdKey] == device.id,
                    enabled = !busy, onClick = { select(device.id) }) }) }
            if (devices.isEmpty()) Text(DesktopStrings["desktop_output_no_devices"])
        } }, confirmButton = { TextButton(enabled = !busy, onClick = { picker = false }) { Text(DesktopStrings["cancel"]) } })
}

@Composable
internal fun DesktopOutputStatus(output: DesktopOutputState) {
    val decision = DesktopExclusiveOutputPolicy.decision(output)
    val label = when {
        output.muted -> "desktop_output_muted"
        decision.verifiedBitPerfect -> "desktop_output_direct"
        output.exclusiveActive -> "desktop_output_switching"
        output.requested && output.outputRateHz > 0 -> "desktop_output_fallback"
        output.requested -> "desktop_output_waiting"
        output.outputRateHz == 0 -> "desktop_output_inactive"
        else -> "desktop_output_shared"
    }
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(DesktopStrings["desktop_output_status"], style = MaterialTheme.typography.titleMedium)
        Text(DesktopStrings[label])
        if (output.deviceName.isNotBlank()) Text(listOf(output.deviceName, output.backend).filter(String::isNotBlank).joinToString(" · "))
        if (output.inputRateHz > 0) Text(DesktopStrings["desktop_output_source_pcm"] + ": " +
            "${output.inputRateHz} Hz · ${output.inputBits} bit · ${output.inputChannels} ch · ${output.inputFormat.name}")
        if (output.outputRateHz > 0) Text(DesktopStrings["desktop_output_sink_pcm"] + ": " +
            "${output.outputRateHz} Hz · ${output.outputBits} bit · ${output.outputChannels} ch · ${output.outputFormat.name}")
        if (output.reason.isNotBlank()) Text(output.reason, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(DesktopStrings["desktop_output_mode_scope"], style = MaterialTheme.typography.bodySmall)
        if (output.requested || output.exclusiveActive) Text(DesktopStrings["desktop_output_volume_scope"], style = MaterialTheme.typography.bodySmall)
    }
}
