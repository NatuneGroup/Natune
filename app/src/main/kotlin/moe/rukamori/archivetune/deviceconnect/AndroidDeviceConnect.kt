package moe.rukamori.archivetune.deviceconnect

import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import moe.rukamori.archivetune.BuildConfig
import moe.rukamori.archivetune.cast.CastPlaybackRepositoryLocator
import moe.rukamori.archivetune.cast.CastScreenState
import moe.rukamori.archivetune.kmp.data.AndroidSecureCredentialStore
import moe.rukamori.archivetune.kmp.settings.AndroidSettingsStore
import moe.rukamori.archivetune.playback.MusicService
import moe.rukamori.archivetune.shared.data.deviceconnect.AndroidDeviceCrypto
import moe.rukamori.archivetune.shared.data.deviceconnect.AndroidDeviceDiscovery
import moe.rukamori.archivetune.shared.data.deviceconnect.DeviceConnectOwner
import moe.rukamori.archivetune.shared.data.deviceconnect.DeviceIdentityStore
import moe.rukamori.archivetune.shared.data.deviceconnect.SecureDevicePairRepository
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceConnectEnvironment
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceConnectState
import moe.rukamori.archivetune.shared.domain.deviceconnect.DevicePlatform
import moe.rukamori.archivetune.utils.dataStore

/**
 * The Android owner. MusicService only holds this object and closes it in onDestroy; every Device
 * Connect detail (server, discovery, pair vault, queue bridge) lives here or in :shared-data.
 */
class AndroidDeviceConnect(
    private val service: MusicService,
    private val scope: CoroutineScope,
) {
    private val http = HttpClient(OkHttp) {
        expectSuccess = false
    }
    private val settings = AndroidSettingsStore(service.dataStore, scope)
    private val crypto = AndroidDeviceCrypto()
    private val pairs = SecureDevicePairRepository(AndroidSecureCredentialStore(service), settings)
    private val playback = AndroidDevicePlaybackPort(service)
    val owner = DeviceConnectOwner(
        scope = scope,
        settings = settings,
        identityStore = DeviceIdentityStore(
            settings = settings,
            platform = DevicePlatform.ANDROID,
            defaultName = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android",
            model = Build.MODEL.orEmpty(),
            appVersion = BuildConfig.VERSION_NAME,
            randomBytes = crypto::randomBytes,
        ),
        pairs = pairs,
        crypto = crypto,
        playback = playback,
        discovery = AndroidDeviceDiscovery(service),
        httpClient = http,
        environment = { environment() },
    )

    val state: kotlinx.coroutines.flow.StateFlow<DeviceConnectState> = owner.state

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            scope.launch { owner.rescan() }
        }
    }

    init {
        scope.coroutineContext[Job]?.invokeOnCompletion { close() }
        owner.start()
        runCatching {
            service.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(networkCallback)
        }
    }

    fun close() {
        runCatching {
            service.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(networkCallback)
        }
        owner.stop()
        runCatching { http.close() }
    }

    private fun environment(): DeviceConnectEnvironment {
        val casting = runCatching {
            (CastPlaybackRepositoryLocator.get(service).screenState.value as? CastScreenState.Success)
                ?.uiState?.isConnected == true
        }.getOrDefault(false)
        val listeningTogether = runCatching {
            service.listenTogetherManager.isInRoom
        }.getOrDefault(false)
        return DeviceConnectEnvironment(casting = casting, listenTogetherActive = listeningTogether)
    }

    companion object {
        const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
    }
}
