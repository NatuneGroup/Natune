@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.ui.ios

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.data.deviceconnect.DeviceConnectOwner
import moe.rukamori.archivetune.shared.data.deviceconnect.DeviceIdentityStore
import moe.rukamori.archivetune.shared.data.deviceconnect.IosDeviceCrypto
import moe.rukamori.archivetune.shared.data.deviceconnect.IosDeviceDiscovery
import moe.rukamori.archivetune.shared.data.deviceconnect.QueueDevicePlaybackPort
import moe.rukamori.archivetune.shared.data.deviceconnect.SecureDevicePairRepository
import moe.rukamori.archivetune.shared.data.platform.ios.IosKeychainCredentialStore
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceConnectEnvironment
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceConnectState
import moe.rukamori.archivetune.shared.domain.deviceconnect.DevicePlatform
import moe.rukamori.archivetune.shared.domain.queue.QueueRepeatMode
import moe.rukamori.archivetune.shared.domain.settings.RepeatModeKey
import platform.Foundation.NSBundle
import platform.UIKit.UIDevice

/**
 * The iOS Device Connect owner over the one session graph. It runs while the app is alive and the
 * audio background mode keeps it alive during playback; it adds no player or queue of its own.
 */
internal class IosDeviceConnect(private val graph: IosSessionGraph) {
    private val crypto = IosDeviceCrypto()
    val owner = DeviceConnectOwner(
        scope = graph.scope,
        settings = graph.settings,
        identityStore = DeviceIdentityStore(
            settings = graph.settings,
            platform = DevicePlatform.IOS,
            defaultName = UIDevice.currentDevice.name,
            model = UIDevice.currentDevice.model,
            appVersion = (NSBundle.mainBundle.infoDictionary?.get("CFBundleShortVersionString") as? String).orEmpty(),
            randomBytes = crypto::randomBytes,
        ),
        pairs = SecureDevicePairRepository(IosKeychainCredentialStore(), graph.settings),
        crypto = crypto,
        playback = QueueDevicePlaybackPort(
            queue = graph.queue,
            editing = graph.queue,
            repeatMode = {
                QueueRepeatMode.fromStoredValue(graph.settings.snapshots.value?.get(RepeatModeKey) ?: 0)
            },
            setRepeatMode = { mode ->
                graph.scope.launch { graph.settings.edit { this[RepeatModeKey] = mode.storedValue } }
            },
            setPlatformVolume = { volume -> graph.playbackPort.setVolume(volume) },
        ),
        discovery = IosDeviceDiscovery(),
        httpClient = graph.http,
        environment = {
            DeviceConnectEnvironment(
                casting = false,
                listenTogetherActive = runCatching { graph.listenTogether.client.isInRoom }.getOrDefault(false),
            )
        },
    )

    val state: StateFlow<DeviceConnectState> = owner.state
    private val _sheetVisible = MutableStateFlow(false)
    val sheetVisible: StateFlow<Boolean> = _sheetVisible.asStateFlow()

    init {
        owner.start()
    }

    /** The iOS shell hook for the Connected devices sheet. */
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
