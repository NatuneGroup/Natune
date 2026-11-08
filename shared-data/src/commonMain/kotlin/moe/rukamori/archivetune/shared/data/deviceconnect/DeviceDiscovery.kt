package moe.rukamori.archivetune.shared.data.deviceconnect

import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceEndpoint
import moe.rukamori.archivetune.shared.domain.deviceconnect.DevicePlatform

/** What the local device publishes over mDNS while Device Connect is enabled. */
data class DeviceAdvertisement(
    val deviceId: String,
    val name: String,
    val platform: DevicePlatform,
    val port: Int,
    /** `pairing`, `paired` or `idle`; informational only, never a trust decision. */
    val state: String = "idle",
)

data class DiscoveredDevice(
    val deviceId: String,
    val name: String,
    val platform: DevicePlatform,
    val model: String = "",
    val endpoint: DeviceEndpoint,
    val lastSeenEpochMs: Long,
)

/**
 * mDNS discovery and advertisement. Android uses NsdManager, desktop JmDNS and iOS NSNetService;
 * the common owner never sees a platform API. Discovery is only a rendezvous: authentication still
 * requires the pair secret or the six-digit SAS.
 */
interface DeviceDiscovery {
    interface Listener {
        fun onFound(device: DiscoveredDevice)
        fun onLost(deviceId: String)
        fun onFailure(message: String)
    }

    suspend fun start(advertisement: DeviceAdvertisement, listener: Listener)

    suspend fun updateAdvertisement(advertisement: DeviceAdvertisement)

    fun stop()
}
