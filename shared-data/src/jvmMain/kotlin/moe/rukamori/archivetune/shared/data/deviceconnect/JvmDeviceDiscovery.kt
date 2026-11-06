package moe.rukamori.archivetune.shared.data.deviceconnect

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceConnectLimits
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceEndpoint
import moe.rukamori.archivetune.shared.domain.deviceconnect.DevicePlatform

/**
 * JmDNS discovery and advertisement for the desktop host. Only private IPv4 interfaces are used; virtual
 * interface names are skipped by default so Docker/VPN traffic cannot silently become a discovery surface.
 */
class JvmDeviceDiscovery(
    private val allowVirtualInterfaces: () -> Boolean = { false },
) : DeviceDiscovery {
    private var jmdns: JmDNS? = null
    private var registration: ServiceInfo? = null
    private var listener: DeviceDiscovery.Listener? = null
    private var scope: CoroutineScope? = null
    private var current: DeviceAdvertisement? = null
    private val names = mutableMapOf<String, String>()

    override suspend fun start(advertisement: DeviceAdvertisement, listener: DeviceDiscovery.Listener) = withContext(Dispatchers.IO) {
        stop()
        this@JvmDeviceDiscovery.listener = listener
        this@JvmDeviceDiscovery.current = advertisement
        val address = lanAddress() ?: error("No private LAN address is available")
        val instance = JmDNS.create(address, "Natune-${advertisement.deviceId.take(8)}")
        jmdns = instance
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        this@JvmDeviceDiscovery.scope = scope
        instance.addServiceListener(DeviceConnectLimits.SERVICE_TYPE_FULL, object : ServiceListener {
            override fun serviceAdded(event: ServiceEvent) {
                scope.launch { resolve(instance, event.name) }
            }

            override fun serviceResolved(event: ServiceEvent) {
                publish(event.info)
            }

            override fun serviceRemoved(event: ServiceEvent) {
                val id = names.remove(event.name) ?: return
                listener.onLost(id)
            }
        })
        register(instance, advertisement)
    }

    override suspend fun updateAdvertisement(advertisement: DeviceAdvertisement) = withContext(Dispatchers.IO) {
        val previous = current ?: return@withContext
        if (previous.name == advertisement.name && previous.port == advertisement.port && previous.state == advertisement.state) return@withContext
        current = advertisement
        val instance = jmdns ?: return@withContext
        registration?.let { runCatching { instance.unregisterService(it) } }
        register(instance, advertisement)
    }

    override fun stop() {
        val instance = jmdns
        jmdns = null
        registration = null
        names.clear()
        listener = null
        scope?.cancel()
        scope = null
        runCatching { instance?.unregisterAllServices() }
        runCatching { instance?.close() }
    }

    private fun register(instance: JmDNS, advertisement: DeviceAdvertisement) {
        val properties = mapOf(
            "id" to advertisement.deviceId,
            "name" to advertisement.name,
            "platform" to advertisement.platform.wire,
            "proto" to DeviceConnectLimits.PROTOCOL_VERSION.toString(),
            "state" to advertisement.state,
        )
        val name = "${advertisement.name}-${advertisement.deviceId.take(6)}"
        val info = ServiceInfo.create(DeviceConnectLimits.SERVICE_TYPE_FULL, name, advertisement.port, 0, 0, properties)
        registration = info
        instance.registerService(info)
    }

    private suspend fun resolve(instance: JmDNS, serviceName: String) {
        val info = runCatching { instance.getServiceInfo(DeviceConnectLimits.SERVICE_TYPE_FULL, serviceName, true, 3_000) }.getOrNull()
            ?: return
        publish(info)
    }

    private fun publish(info: ServiceInfo) {
        if (!info.hasData()) return
        val id = info.getPropertyString("id") ?: return
        val name = info.getPropertyString("name") ?: id.take(8)
        val platform = DevicePlatform.fromWire(info.getPropertyString("platform"))
        val host = info.inet4Addresses.firstOrNull()?.hostAddress
            ?: info.inetAddresses.firstOrNull()?.hostAddress
            ?: return
        if (!moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceConnectHosts.isPrivateOrLocal(host)) return
        names[info.name] = id
        listener?.onFound(
            DiscoveredDevice(
                deviceId = id,
                name = name,
                platform = platform,
                model = "",
                endpoint = DeviceEndpoint(host, info.port),
                lastSeenEpochMs = System.currentTimeMillis(),
            ),
        )
    }

    private fun lanAddress(): InetAddress? {
        val virtual = listOf("br-", "docker", "veth", "tun", "tap", "ppp", "vpn", "wg", "utun")
        val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        val addresses = interfaces.asSequence()
            .filter { it.isUp && !it.isLoopback }
            .filter { allowVirtualInterfaces() || virtual.none { prefix -> it.name.startsWith(prefix) } }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .filter { !it.isLoopbackAddress && isPrivate(it.hostAddress) }
            .toList()
        return addresses.firstOrNull()
    }

    private fun isPrivate(host: String): Boolean =
        moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceConnectHosts.isPrivateOrLocal(host)
}
