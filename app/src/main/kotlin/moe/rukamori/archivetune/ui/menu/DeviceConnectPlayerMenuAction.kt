package moe.rukamori.archivetune.ui.menu

import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.shared.ui.deviceconnect.ConnectedDevicesSheet
import moe.rukamori.archivetune.shared.ui.deviceconnect.DeviceConnectActions
import moe.rukamori.archivetune.shared.ui.deviceconnect.DevicePairingDialog
import moe.rukamori.archivetune.shared.ui.deviceconnect.DeviceTransferDialog
import moe.rukamori.archivetune.ui.component.NewAction

/** The player's Connected devices entry. The sheet and dialogs stay in the menu's own composition. */
@Composable
fun rememberDeviceConnectPlayerMenuAction(navController: NavController): NewAction? {
    val service = LocalPlayerConnection.current?.service ?: return null
    val manager = service.deviceConnect
    val state by manager.state.collectAsState()
    val scope = rememberCoroutineScope()
    var open by rememberSaveable { mutableStateOf(false) }
    val actions = remember(manager) {
        DeviceConnectActions(
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
    }
    if (open) {
        ConnectedDevicesSheet(
            state = state,
            actions = actions,
            onOpenSettings = {
                open = false
                navController.navigate("settings/integrations/device_connect")
            },
            onDismiss = { open = false },
        )
    }
    state.pairing?.let { prompt ->
        DevicePairingDialog(prompt, onApprove = actions.approvePairing, onReject = actions.rejectPairing)
    }
    state.incomingTransfer?.let { request ->
        DeviceTransferDialog(request, onAccept = actions.acceptTransfer, onDecline = actions.declineTransfer)
    }
    return NewAction(
        icon = {
            Icon(
                painter = painterResource(R.drawable.cast_connected),
                contentDescription = null,
                modifier = Modifier.size(28.dp),
            )
        },
        text = stringResource(R.string.device_connect),
        onClick = { open = true },
    )
}
