package app.natune.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import app.natune.desktop.integration.auth.DesktopSecureCredentialStore
import app.natune.shared.data.deviceconnect.DeviceConnectOwner
import app.natune.shared.data.deviceconnect.DeviceIdentityStore
import app.natune.shared.data.deviceconnect.JvmDeviceCrypto
import app.natune.shared.data.deviceconnect.JvmDeviceDiscovery
import app.natune.shared.data.deviceconnect.QueueDevicePlaybackPort
import app.natune.shared.data.deviceconnect.SecureDevicePairRepository
import app.natune.shared.domain.deviceconnect.DeviceConnectEnvironment
import app.natune.shared.domain.deviceconnect.DeviceConnectState
import app.natune.shared.domain.deviceconnect.DevicePlatform
import app.natune.shared.domain.settings.DeviceConnectAllowVpnKey
import app.natune.shared.domain.settings.RepeatModeKey
import app.natune.shared.domain.queue.QueueRepeatMode

/**
 * Desktop Device Connect owner over the one session graph. It adds no player or queue: the shared
 * [QueueDevicePlaybackPort] drives [DesktopSessionGraph.queue] and the graph's output volume.
 */
internal class DesktopDeviceConnect(
    private val graph: DesktopSessionGraph,
    private val setOutputVolume: (Float) -> Unit,
) {
    private val discovery = JvmDeviceDiscovery(
        allowVirtualInterfaces = { graph.settings.snapshots.value?.get(DeviceConnectAllowVpnKey) == true },
    )
    private val playback = QueueDevicePlaybackPort(
        queue = graph.queue,
        editing = graph.queue,
        repeatMode = {
            QueueRepeatMode.fromStoredValue(graph.settings.snapshots.value?.get(RepeatModeKey) ?: 0)
        },
        setRepeatMode = { mode ->
            graph.scope.launch { graph.settings.edit { this[RepeatModeKey] = mode.storedValue } }
        },
        setPlatformVolume = setOutputVolume,
    )
    val owner = DeviceConnectOwner(
        scope = graph.scope,
        settings = graph.settings,
        identityStore = DeviceIdentityStore(
            settings = graph.settings,
            platform = DevicePlatform.DESKTOP,
            defaultName = System.getProperty("user.name")?.takeIf { it.isNotBlank() }?.let { "$it Desktop" } ?: "Desktop",
            model = System.getProperty("os.name").orEmpty(),
            appVersion = System.getProperty("natune.version", "desktop"),
            randomBytes = JvmDeviceCrypto()::randomBytes,
        ),
        pairs = SecureDevicePairRepository(DesktopSecureCredentialStore(), graph.settings),
        crypto = JvmDeviceCrypto(),
        playback = playback,
        discovery = discovery,
        httpClient = graph.http,
        environment = {
            DeviceConnectEnvironment(
                casting = false,
                listenTogetherActive = runCatching { graph.listenTogether.isInRoom() }.getOrDefault(false),
            )
        },
    )
    private val _sheetVisible = MutableStateFlow(false)

    val state: StateFlow<DeviceConnectState> = owner.state
    val sheetVisible: StateFlow<Boolean> = _sheetVisible.asStateFlow()

    init {
        owner.start()
    }

    /** The desktop shell's Connected devices hook; the redesigned shell only has to bind this flag. */
    fun openConnectedDevices() {
        _sheetVisible.value = true
    }

    fun closeConnectedDevices() {
        _sheetVisible.value = false
    }

    fun close() {
        owner.stop()
    }
}
