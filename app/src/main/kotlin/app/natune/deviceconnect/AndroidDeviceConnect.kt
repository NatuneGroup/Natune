package app.natune.deviceconnect

import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import app.natune.BuildConfig
import app.natune.cast.CastPlaybackRepositoryLocator
import app.natune.cast.CastScreenState
import app.natune.kmp.data.AndroidSecureCredentialStore
import app.natune.kmp.settings.AndroidSettingsStore
import app.natune.playback.MusicService
import app.natune.shared.data.deviceconnect.AndroidDeviceCrypto
import app.natune.shared.data.deviceconnect.AndroidDeviceDiscovery
import app.natune.shared.data.deviceconnect.DeviceConnectOwner
import app.natune.shared.data.deviceconnect.DeviceIdentityStore
import app.natune.shared.data.deviceconnect.SecureDevicePairRepository
import app.natune.shared.domain.deviceconnect.DeviceConnectEnvironment
import app.natune.shared.domain.deviceconnect.DeviceConnectState
import app.natune.shared.domain.deviceconnect.DevicePlatform
import app.natune.utils.dataStore

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
