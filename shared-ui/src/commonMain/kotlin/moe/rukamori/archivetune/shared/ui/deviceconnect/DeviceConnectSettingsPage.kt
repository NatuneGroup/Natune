package moe.rukamori.archivetune.shared.ui.deviceconnect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceConnectState
import moe.rukamori.archivetune.shared.domain.deviceconnect.DevicePairingPrompt
import moe.rukamori.archivetune.shared.domain.deviceconnect.DevicePeerPresence
import moe.rukamori.archivetune.shared.domain.deviceconnect.DevicePeerState
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceTransferRequest
import moe.rukamori.archivetune.shared.ui.resources.Res
import org.jetbrains.compose.resources.stringResource

/** All user intents the sheet and settings page need. Hosts launch the corresponding owner call. */
data class DeviceConnectActions(
    val setEnabled: (Boolean) -> Unit,
    val rename: (String) -> Unit = {},
    val rescan: () -> Unit = {},
    val pair: (String) -> Unit = {},
    val unpair: (String) -> Unit = {},
    val transferTo: (String) -> Unit = {},
    val pullHere: () -> Unit = {},
    val release: () -> Unit = {},
    val acceptTransfer: () -> Unit = {},
    val declineTransfer: () -> Unit = {},
    val approvePairing: () -> Unit = {},
    val rejectPairing: () -> Unit = {},
    val connectByIp: (String) -> Unit = {},
    val requestPermission: () -> Unit = {},
    val clearError: () -> Unit = {},
    val remotePlay: () -> Unit = {},
    val remotePause: () -> Unit = {},
    val remoteNext: () -> Unit = {},
    val remotePrevious: () -> Unit = {},
)

@Composable
fun DeviceConnectSettingsPage(
    state: DeviceConnectState,
    actions: DeviceConnectActions,
    modifier: Modifier = Modifier,
) {
    var name by rememberSaveable(state.local?.name) { mutableStateOf(state.local?.name.orEmpty()) }
    var ipDialog by rememberSaveable { mutableStateOf(false) }
    var ipText by rememberSaveable { mutableStateOf("") }
    if (ipDialog) {
        AlertDialog(
            onDismissRequest = { ipDialog = false },
            title = { Text(stringResource(Res.string.m9_device_connect_connect_by_ip)) },
            text = {
                OutlinedTextField(
                    value = ipText,
                    onValueChange = { ipText = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(Res.string.m9_device_connect_connect_by_ip_hint)) },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    actions.connectByIp(ipText)
                    ipDialog = false
                }) { Text(stringResource(Res.string.m9_device_connect_pair)) }
            },
            dismissButton = {
                TextButton(onClick = { ipDialog = false }) { Text(stringResource(Res.string.m9_device_connect_cancel)) }
            },
        )
    }
    LazyColumn(modifier = modifier.fillMaxWidth()) {
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(Res.string.m9_device_connect_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        stringResource(Res.string.m9_device_connect_beta),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    stringResource(Res.string.m9_device_connect_summary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        item {
            ListItem(
                headlineContent = { Text(stringResource(Res.string.m9_device_connect_enabled)) },
                supportingContent = { Text(stringResource(Res.string.m9_device_connect_enabled_description)) },
                trailingContent = {
                    Switch(checked = state.enabled, onCheckedChange = actions.setEnabled)
                },
            )
        }
        if (state.permissionRequired) {
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(stringResource(Res.string.m9_device_connect_local_network_permission))
                    Button(onClick = actions.requestPermission, modifier = Modifier.padding(top = 8.dp)) {
                        Text(stringResource(Res.string.m9_device_connect_grant_permission))
                    }
                }
            }
        }
        item {
            ListItem(
                headlineContent = { Text(stringResource(Res.string.m9_device_connect_device_name)) },
                supportingContent = {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(48) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                trailingContent = {
                    TextButton(
                        onClick = { actions.rename(name) },
                        enabled = name.isNotBlank() && name != state.local?.name,
                    ) { Text(stringResource(Res.string.m9_device_connect_save)) }
                },
            )
        }
        if (state.serverStarting) {
            item {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator()
                }
            }
        }
        if (!state.enabled) {
            item {
                Text(
                    stringResource(Res.string.m9_device_connect_enable_to_start),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        item {
            SectionHeader(stringResource(Res.string.m9_device_connect_paired_devices))
        }
        if (state.paired.isEmpty()) {
            item { EmptyLine(stringResource(Res.string.m9_device_connect_no_paired)) }
        } else {
            items(state.paired, key = { "paired:${it.peerId}" }) { pair ->
                val peer = state.peer(pair.peerId)
                ListItem(
                    headlineContent = { Text(pair.peerName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(pair.peerPlatform.wire) },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (state.enabled) {
                                TextButton(onClick = { actions.transferTo(pair.peerId) }) {
                                    Text(stringResource(Res.string.m9_device_connect_play_on, pair.peerName))
                                }
                            }
                            TextButton(onClick = { actions.unpair(pair.peerId) }) {
                                Text(stringResource(Res.string.m9_device_connect_unpair))
                            }
                        }
                    },
                )
                if (peer != null) PresenceLine(peer)
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = actions.rescan, enabled = state.enabled) {
                    Text(stringResource(Res.string.m9_device_connect_rescan))
                }
                OutlinedButton(onClick = { ipDialog = true }, enabled = state.enabled) {
                    Text(stringResource(Res.string.m9_device_connect_connect_by_ip))
                }
            }
        }
        item { SectionHeader(stringResource(Res.string.m9_device_connect_available_devices)) }
        val available = state.peers.filter { it.presence == DevicePeerPresence.DISCOVERED && state.pair(it.id) == null }
        if (available.isEmpty()) {
            item { EmptyLine(stringResource(Res.string.m9_device_connect_no_available)) }
        } else {
            items(available, key = { "available:${it.id}" }) { peer ->
                ListItem(
                    headlineContent = { Text(peer.name) },
                    supportingContent = { Text(peer.platform.wire) },
                    trailingContent = {
                        TextButton(onClick = { actions.pair(peer.id) }) {
                            Text(stringResource(Res.string.m9_device_connect_pair))
                        }
                    },
                )
            }
        }
        state.error?.let { error ->
            item {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        errorText(error.code),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = actions.clearError) {
                        Text(stringResource(Res.string.m9_device_connect_close))
                    }
                }
            }
        }
        item {
            Text(
                stringResource(Res.string.m9_device_connect_privacy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
fun DevicePairingDialog(
    prompt: DevicePairingPrompt,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onReject,
        title = { Text(stringResource(Res.string.m9_device_connect_pairing_code)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(prompt.peerName, style = MaterialTheme.typography.titleMedium)
                Text(prompt.code, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                Text(
                    if (prompt.isRequester) {
                        stringResource(Res.string.m9_device_connect_pairing_waiting)
                    } else {
                        stringResource(Res.string.m9_device_connect_pairing_confirm)
                    },
                )
            }
        },
        confirmButton = {
            if (!prompt.isRequester) {
                TextButton(onClick = onApprove) { Text(stringResource(Res.string.m9_device_connect_pairing_approve)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onReject) { Text(stringResource(Res.string.m9_device_connect_pairing_reject)) }
        },
    )
}

@Composable
fun DeviceTransferDialog(
    request: DeviceTransferRequest,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDecline,
        title = { Text(stringResource(Res.string.m9_device_connect_continue_title)) },
        text = { Text(stringResource(Res.string.m9_device_connect_continue_message, request.fromName)) },
        confirmButton = {
            TextButton(onClick = onAccept) { Text(stringResource(Res.string.m9_device_connect_continue_action)) }
        },
        dismissButton = {
            TextButton(onClick = onDecline) { Text(stringResource(Res.string.m9_device_connect_cancel)) }
        },
    )
}

@Composable
private fun SectionHeader(text: String) {
    Column {
        HorizontalDivider(Modifier.padding(top = 12.dp))
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 4.dp),
        )
    }
}

@Composable
private fun EmptyLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun PresenceLine(peer: DevicePeerState) {
    val text = when (peer.presence) {
        DevicePeerPresence.OFFLINE -> stringResource(Res.string.m9_device_connect_status_offline)
        DevicePeerPresence.DISCOVERED -> stringResource(Res.string.m9_device_connect_status_available)
        DevicePeerPresence.CONNECTING -> stringResource(Res.string.m9_device_connect_status_pairing)
        DevicePeerPresence.PAIRED -> stringResource(Res.string.m9_device_connect_status_paired)
        DevicePeerPresence.AVAILABLE -> stringResource(Res.string.m9_device_connect_status_available)
        DevicePeerPresence.PLAYING_REMOTE -> stringResource(Res.string.m9_device_connect_status_playing_remote)
    }
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun errorText(code: moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceConnectErrorCode): String = when (code) {
    moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceConnectErrorCode.UNSHAREABLE_QUEUE ->
        stringResource(Res.string.m9_device_connect_error_unshareable)
    moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceConnectErrorCode.TOO_LARGE ->
        stringResource(Res.string.m9_device_connect_error_too_large)
    moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceConnectErrorCode.STALE_QUEUE,
    moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceConnectErrorCode.STALE_TRACK,
    -> stringResource(Res.string.m9_device_connect_error_stale)
    moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceConnectErrorCode.CONFLICT ->
        stringResource(Res.string.m9_device_connect_error_conflict)
    else -> stringResource(Res.string.m9_device_connect_error_unavailable)
}
