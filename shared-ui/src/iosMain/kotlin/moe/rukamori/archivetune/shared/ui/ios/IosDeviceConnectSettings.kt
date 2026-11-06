package moe.rukamori.archivetune.shared.ui.ios

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
internal fun IosDeviceConnectSettingsPage(graph: IosSessionGraph) {
    val manager = graph.deviceConnect
    val state by manager.state.collectAsState()
    val scope = rememberCoroutineScope()
    val actions = iosDeviceConnectActions(manager, scope)
    state.pairing?.let { prompt ->
        DevicePairingDialog(prompt, onApprove = actions.approvePairing, onReject = actions.rejectPairing)
    }
    state.incomingTransfer?.let { request ->
        DeviceTransferDialog(request, onAccept = actions.acceptTransfer, onDecline = actions.declineTransfer)
    }
    DeviceConnectSettingsPage(state = state, actions = actions, modifier = Modifier.fillMaxSize())
}

/** The iOS Connected devices hook, mounted by IosMountedRoot and opened by the settings/player shell. */
@Composable
internal fun IosDeviceConnectHost(graph: IosSessionGraph) {
    val manager = graph.deviceConnect
    val visible by manager.sheetVisible.collectAsState()
    if (!visible) return
    val state by manager.state.collectAsState()
    val scope = rememberCoroutineScope()
    val actions = iosDeviceConnectActions(manager, scope)
    state.pairing?.let { prompt ->
        DevicePairingDialog(prompt, onApprove = actions.approvePairing, onReject = actions.rejectPairing)
    }
    state.incomingTransfer?.let { request ->
        DeviceTransferDialog(request, onAccept = actions.acceptTransfer, onDecline = actions.declineTransfer)
    }
    ConnectedDevicesSheet(
        state = state,
        actions = actions,
        onOpenSettings = manager::closeConnectedDevices,
        onDismiss = manager::closeConnectedDevices,
    )
}

private fun iosDeviceConnectActions(manager: IosDeviceConnect, scope: CoroutineScope) = DeviceConnectActions(
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
