package moe.rukamori.archivetune.ui.screens.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.deviceconnect.AndroidDeviceConnect
import moe.rukamori.archivetune.shared.ui.deviceconnect.DeviceConnectActions
import moe.rukamori.archivetune.shared.ui.deviceconnect.DeviceConnectSettingsPage
import moe.rukamori.archivetune.shared.ui.deviceconnect.DevicePairingDialog
import moe.rukamori.archivetune.shared.ui.deviceconnect.DeviceTransferDialog
import moe.rukamori.archivetune.ui.component.IconButton
import moe.rukamori.archivetune.ui.component.SettingsTopAppBar
import moe.rukamori.archivetune.ui.utils.backToMain

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceConnectSettings(
    navController: NavController,
    scrollTo: String? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = LocalPlayerConnection.current?.service?.deviceConnect
    val permission = deviceConnectPermission()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) manager?.owner?.clearPermissionRequirement() else manager?.owner?.markPermissionRequired()
    }
    if (manager == null) return
    val state by manager.state.collectAsState()
    LaunchedEffect(state.enabled, permission) {
        if (state.enabled && permission != null &&
            ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
        ) {
            manager.owner.markPermissionRequired()
        }
    }
    val actions = DeviceConnectActions(
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
        requestPermission = {
            if (permission == null) {
                manager.owner.clearError()
            } else if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
                manager.owner.clearError()
            } else {
                launcher.launch(permission)
            }
        },
        clearError = manager.owner::clearError,
        remotePlay = manager.owner::remotePlay,
        remotePause = manager.owner::remotePause,
        remoteNext = manager.owner::remoteNext,
        remotePrevious = manager.owner::remotePrevious,
    )
    state.pairing?.let { prompt ->
        DevicePairingDialog(prompt, onApprove = actions.approvePairing, onReject = actions.rejectPairing)
    }
    state.incomingTransfer?.let { request ->
        DeviceTransferDialog(request, onAccept = actions.acceptTransfer, onDecline = actions.declineTransfer)
    }
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            SettingsTopAppBar(
                title = { Text(stringResource(R.string.device_connect)) },
                navigationIcon = {
                    IconButton(
                        onClick = navController::navigateUp,
                        onLongClick = navController::backToMain,
                    ) {
                        Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
                    }
                },
            )
        },
    ) { innerPadding ->
        val bottom = LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Bottom)
            .asPaddingValues().calculateBottomPadding()
        DeviceConnectSettingsPage(
            state = state,
            actions = actions,
            modifier = Modifier
                .padding(top = innerPadding.calculateTopPadding())
                .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal))
                .padding(bottom = bottom),
        )
    }
}

private fun deviceConnectPermission(): String? = when {
    Build.VERSION.SDK_INT >= 37 -> AndroidDeviceConnect.LOCAL_NETWORK_PERMISSION
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> Manifest.permission.NEARBY_WIFI_DEVICES
    else -> null
}
