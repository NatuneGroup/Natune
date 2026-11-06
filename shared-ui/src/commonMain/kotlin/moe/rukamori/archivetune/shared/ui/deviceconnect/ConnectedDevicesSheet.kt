package moe.rukamori.archivetune.shared.ui.deviceconnect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceConnectState
import moe.rukamori.archivetune.shared.domain.deviceconnect.DevicePeerPresence
import moe.rukamori.archivetune.shared.domain.deviceconnect.DevicePeerState
import moe.rukamori.archivetune.shared.ui.resources.Res
import org.jetbrains.compose.resources.stringResource

/**
 * "Connected devices" from the player. Cast remains a separate host surface; this sheet owns only
 * Device Connect's paired, available and remote-session states.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectedDevicesSheet(
    state: DeviceConnectState,
    actions: DeviceConnectActions,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier,
        sheetMaxWidth = 640.dp,
        tonalElevation = 0.dp,
    ) {
        Column(
            Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(bottom = 16.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(Res.string.m9_device_connect_connected_devices),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(Res.string.m9_device_connect_connected_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(onClick = onOpenSettings) {
                    Text(stringResource(Res.string.m9_device_connect_title))
                }
            }
            LazyColumn(Modifier.fillMaxWidth()) {
                item {
                    HorizontalDivider()
                    Text(
                        stringResource(Res.string.m9_device_connect_this_device),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                    )
                }
                item {
                    ListItem(
                        headlineContent = { Text(state.local?.name ?: stringResource(Res.string.m9_device_connect_title)) },
                        supportingContent = {
                            Text(
                                state.local?.platform?.wire.orEmpty() +
                                    (state.serverPort?.let { " · $it" } ?: ""),
                            )
                        },
                        trailingContent = {
                            if (state.enabled) {
                                Text(stringResource(Res.string.m9_device_connect_status_available))
                            }
                        },
                    )
                }
                state.session?.let { session ->
                    item {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    if (session.isController) {
                                        stringResource(Res.string.m9_device_connect_playing_on, session.targetName ?: "")
                                    } else {
                                        stringResource(Res.string.m9_device_connect_playing_here)
                                    },
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                session.remoteState?.let { snapshot ->
                                    Text(
                                        snapshot.entries.firstOrNull { it.occurrenceId == snapshot.currentOccurrenceId }?.title.orEmpty(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            if (session.isController) {
                                OutlinedButton(onClick = actions.pullHere) {
                                    Text(stringResource(Res.string.m9_device_connect_play_here))
                                }
                            }
                            TextButton(onClick = actions.release) {
                                Text(stringResource(Res.string.m9_device_connect_disconnect))
                            }
                        }
                        if (session.isController) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                TextButton(onClick = actions.remotePrevious) {
                                    Text(stringResource(Res.string.m9_device_connect_previous))
                                }
                                TextButton(
                                    onClick = { if (session.remoteState?.playing == true) actions.remotePause() else actions.remotePlay() },
                                ) {
                                    Text(
                                        if (session.remoteState?.playing == true) {
                                            stringResource(Res.string.m9_device_connect_pause)
                                        } else {
                                            stringResource(Res.string.m9_device_connect_play)
                                        },
                                    )
                                }
                                TextButton(onClick = actions.remoteNext) {
                                    Text(stringResource(Res.string.m9_device_connect_next))
                                }
                            }
                        }
                    }
                }
                item {
                    HorizontalDivider(Modifier.padding(top = 8.dp))
                    Text(
                        stringResource(Res.string.m9_device_connect_paired_devices),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                    )
                }
                if (state.paired.isEmpty()) {
                    item { Hint(stringResource(Res.string.m9_device_connect_no_paired)) }
                } else {
                    items(state.paired, key = { "sheet-paired:${it.peerId}" }) { pair ->
                        val peer = state.peer(pair.peerId)
                        val active = state.session?.targetId == pair.peerId
                        ListItem(
                            headlineContent = { Text(pair.peerName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(presenceText(peer)) },
                            trailingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (state.enabled) {
                                        TextButton(
                                            onClick = {
                                                if (active && state.session?.isController == true) {
                                                    actions.pullHere()
                                                } else {
                                                    actions.transferTo(pair.peerId)
                                                }
                                            },
                                        ) {
                                            Text(
                                                if (active) {
                                                    stringResource(Res.string.m9_device_connect_play_here)
                                                } else {
                                                    stringResource(Res.string.m9_device_connect_play_on, pair.peerName)
                                                },
                                            )
                                        }
                                    }
                                    TextButton(onClick = { actions.unpair(pair.peerId) }) {
                                        Text(stringResource(Res.string.m9_device_connect_unpair))
                                    }
                                }
                            },
                        )
                    }
                }
                val available = state.peers.filter { it.presence == DevicePeerPresence.DISCOVERED && state.pair(it.id) == null }
                if (available.isNotEmpty()) {
                    item {
                        HorizontalDivider(Modifier.padding(top = 8.dp))
                        Text(
                            stringResource(Res.string.m9_device_connect_available_devices),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                        )
                    }
                    items(available, key = { "sheet-available:${it.id}" }) { peer ->
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
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(onClick = actions.rescan, enabled = state.enabled) {
                            Text(stringResource(Res.string.m9_device_connect_rescan))
                        }
                    }
                }
                state.error?.let { error ->
                    item {
                        Text(
                            error.message.ifBlank { stringResource(Res.string.m9_device_connect_error_unavailable) },
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
                if (!state.enabled) {
                    item { Hint(stringResource(Res.string.m9_device_connect_enable_to_start)) }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun presenceText(peer: DevicePeerState?): String = when (peer?.presence) {
    DevicePeerPresence.PLAYING_REMOTE -> stringResource(Res.string.m9_device_connect_status_playing_remote)
    DevicePeerPresence.AVAILABLE -> stringResource(Res.string.m9_device_connect_status_available)
    DevicePeerPresence.PAIRED -> stringResource(Res.string.m9_device_connect_status_paired)
    DevicePeerPresence.CONNECTING -> stringResource(Res.string.m9_device_connect_status_pairing)
    DevicePeerPresence.DISCOVERED -> stringResource(Res.string.m9_device_connect_status_available)
    else -> stringResource(Res.string.m9_device_connect_status_offline)
}
