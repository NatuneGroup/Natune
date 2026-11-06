package moe.rukamori.archivetune.shared.data.deviceconnect

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.util.concurrent.ConcurrentHashMap
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceConnectHosts
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceConnectLimits
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceEndpoint
import moe.rukamori.archivetune.shared.domain.deviceconnect.DevicePlatform

/**
 * Android NSD discovery. The service name is unique per install; pairing metadata travels in TXT records
 * and is never trusted before the SAS or pair proof.
 */
class AndroidDeviceDiscovery(context: Context) : DeviceDiscovery {
    private val manager: NsdManager? = context.applicationContext.getSystemService(NsdManager::class.java)
    private var listener: DeviceDiscovery.Listener? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var advertisement: DeviceAdvertisement? = null
    private val resolvedNames = ConcurrentHashMap<String, String>()
    private var discovering = false

    override suspend fun start(advertisement: DeviceAdvertisement, listener: DeviceDiscovery.Listener) {
        stop()
        this.listener = listener
        this.advertisement = advertisement
        register(advertisement)
        startDiscovery()
    }

    override suspend fun updateAdvertisement(advertisement: DeviceAdvertisement) {
        val previous = this.advertisement ?: return
        this.advertisement = advertisement
        if (previous.name == advertisement.name && previous.port == advertisement.port) return
        unregister()
        register(advertisement)
    }

    override fun stop() {
        listener = null
        advertisement = null
        stopDiscovery()
        unregister()
        resolvedNames.clear()
    }

    private fun register(value: DeviceAdvertisement) {
        val nsd = manager
        if (nsd == null) {
            listener?.onFailure("Local network discovery is unavailable")
            return
        }
        val info = NsdServiceInfo().apply {
            serviceName = "${value.name}-${value.deviceId.take(6)}"
            serviceType = DeviceConnectLimits.SERVICE_TYPE
            port = value.port
            setAttribute("id", value.deviceId.encodeToByteArray())
            setAttribute("name", value.name.encodeToByteArray())
            setAttribute("platform", value.platform.wire.encodeToByteArray())
            setAttribute("proto", DeviceConnectLimits.PROTOCOL_VERSION.toString().encodeToByteArray())
            setAttribute("state", value.state.encodeToByteArray())
        }
        val registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                listener?.onFailure("The local discovery service could not be registered")
            }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        this.registration = registration
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration) }
            .onFailure { listener?.onFailure("The local discovery service could not be registered") }
    }

    private fun unregister() {
        val nsd = manager ?: return
        registration?.let { runCatching { nsd.unregisterService(it) } }
        registration = null
    }

    private fun startDiscovery() {
        val nsd = manager ?: return
        if (discovering) return
        val discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                discovering = true
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType != DeviceConnectLimits.SERVICE_TYPE) return
                runCatching {
                    nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
                        override fun onServiceResolved(resolved: NsdServiceInfo) = publish(resolved)
                    })
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val id = resolvedNames.remove(serviceInfo.serviceName) ?: return
                listener?.onLost(id)
            }

            override fun onDiscoveryStopped(serviceType: String) {
                discovering = false
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                discovering = false
                listener?.onFailure("Local network discovery could not start")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                discovering = false
            }
        }
        this.discovery = discovery
        runCatching { nsd.discoverServices(DeviceConnectLimits.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery) }
            .onFailure { listener?.onFailure("Local network discovery could not start") }
    }

    private fun stopDiscovery() {
        val nsd = manager ?: return
        val discovery = discovery ?: return
        this.discovery = null
        if (discovering) runCatching { nsd.stopServiceDiscovery(discovery) }
        discovering = false
    }

    private fun publish(info: NsdServiceInfo) {
        val id = info.attributes?.get("id")?.decodeToString() ?: return
        if (!DeviceConnectHosts.isValidDeviceId(id)) return
        if (id == advertisement?.deviceId) return
        val host = info.host?.hostAddress ?: return
        if (!DeviceConnectHosts.isPrivateOrLocal(host)) return
        val name = info.attributes?.get("name")?.decodeToString().orEmpty().ifBlank { info.serviceName }
        val platform = DevicePlatform.fromWire(info.attributes?.get("platform")?.decodeToString())
        resolvedNames[info.serviceName] = id
        listener?.onFound(
            DiscoveredDevice(
                deviceId = id,
                name = name,
                platform = platform,
                endpoint = DeviceEndpoint(host, info.port),
                lastSeenEpochMs = System.currentTimeMillis(),
            ),
        )
    }
}
