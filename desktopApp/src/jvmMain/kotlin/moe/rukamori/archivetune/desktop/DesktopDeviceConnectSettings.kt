package moe.rukamori.archivetune.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.ui.deviceconnect.ConnectedDevicesSheet
import moe.rukamori.archivetune.shared.ui.deviceconnect.DeviceConnectActions
import moe.rukamori.archivetune.shared.ui.deviceconnect.DeviceConnectSettingsPage
import moe.rukamori.archivetune.shared.ui.deviceconnect.DevicePairingDialog
import moe.rukamori.archivetune.shared.ui.deviceconnect.DeviceTransferDialog

@Composable
internal fun DesktopDeviceConnectSettingsPage(graph: DesktopSessionGraph) {
    val manager = graph.deviceConnect
    val state by manager.state.collectAsState()
    val scope = rememberCoroutineScope()
    val actions = desktopDeviceConnectActions(manager, scope)
    // Pairing and incoming-transfer dialogs are owned by [DesktopDeviceConnectHost], mounted over the
    // whole window, so a request that arrives while this page is closed still shows. This page only
    // draws its settings rows.
    DeviceConnectSettingsPage(state = state, actions = actions, modifier = Modifier.fillMaxSize())
}

/**
 * The desktop Device Connect host, mounted once over the whole window (like the iOS host). It is the
 * one place that owns the pairing and incoming-transfer dialogs, so a request shows even when neither
 * the settings page nor the Connected devices sheet is open. The sheet itself appears when
 * [DesktopDeviceConnect.openConnectedDevices] raises the visible flag, which the settings tree's
 * Connected devices row calls.
 */
@Composable
internal fun DesktopDeviceConnectHost(
    graph: DesktopSessionGraph,
    onOpenSettings: () -> Unit,
) {
    val manager = graph.deviceConnect
    val state by manager.state.collectAsState()
    val visible by manager.sheetVisible.collectAsState()
    val scope = rememberCoroutineScope()
    val actions = desktopDeviceConnectActions(manager, scope)
    state.pairing?.let { prompt ->
        DevicePairingDialog(prompt, onApprove = actions.approvePairing, onReject = actions.rejectPairing)
    }
    state.incomingTransfer?.let { request ->
        DeviceTransferDialog(request, onAccept = actions.acceptTransfer, onDecline = actions.declineTransfer)
    }
    if (visible) {
        ConnectedDevicesSheet(
            state = state,
            actions = actions,
            onOpenSettings = {
                manager.closeConnectedDevices()
                onOpenSettings()
            },
            onDismiss = manager::closeConnectedDevices,
        )
    }
}

private fun desktopDeviceConnectActions(manager: DesktopDeviceConnect, scope: CoroutineScope) = DeviceConnectActions(
    setEnabled = { enabled -> scope.launch { manager.owner.setEnabled(enabled) } },
    rename = { name -> scope.launch { manager.owner.setDeviceName(name) } },
    rescan = { scope.launch { manager.owner.rescan() } },
    pair = { id -> scope.launch { manager.owner.pair(id) } },
    unpair = { id -> scope.launch { manager.owner.unpair(id) } },
    transferTo = { id -> scope.launch { manager.owner.transferTo(id) } },
    pullHere = manager.owner::pullBack,
    release = manager.owner::release,
    acceptTransfer = { scope.launch { manager.owner.acceptIncomingTransfer() } },
    declineTransfer = { scope.launch { manager.owner.declineIncomingTransfer() } },
    approvePairing = { scope.launch { manager.owner.approvePairing() } },
    rejectPairing = { scope.launch { manager.owner.rejectPairing() } },
    connectByIp = { text -> scope.launch { manager.owner.connectManual(text) } },
    clearError = manager.owner::clearError,
    remotePlay = manager.owner::remotePlay,
    remotePause = manager.owner::remotePause,
    remoteNext = manager.owner::remoteNext,
    remotePrevious = manager.owner::remotePrevious,
)
