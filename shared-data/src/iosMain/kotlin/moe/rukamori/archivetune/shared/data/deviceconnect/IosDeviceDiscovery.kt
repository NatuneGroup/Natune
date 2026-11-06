@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.data.deviceconnect

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.usePinned
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceConnectHosts
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceConnectLimits
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceEndpoint
import moe.rukamori.archivetune.shared.domain.deviceconnect.DevicePlatform
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSNetService
import platform.Foundation.NSNetServiceBrowser
import platform.Foundation.NSNetServiceBrowserDelegateProtocol
import platform.Foundation.NSNetServiceDelegateProtocol
import platform.Foundation.NSObject
import platform.Foundation.create
import platform.Foundation.timeIntervalSince1970

/**
 * Bonjour discovery through NSNetService/NSNetServiceBrowser. Info.plist must declare the service in
 * NSBonjourServices and the local-network usage string, or iOS stops discovery before a callback fires.
 */
class IosDeviceDiscovery : DeviceDiscovery {
    private var service: NSNetService? = null
    private var browser: NSNetServiceBrowser? = null
    private var delegate: Delegate? = null
    private var listener: DeviceDiscovery.Listener? = null
    private var advertisement: DeviceAdvertisement? = null
    private val names = mutableMapOf<String, String>()

    override suspend fun start(advertisement: DeviceAdvertisement, listener: DeviceDiscovery.Listener) {
        stop()
        this.listener = listener
        this.advertisement = advertisement
        val delegate = Delegate(this)
        this.delegate = delegate
        val service = NSNetService(
            DeviceConnectLimits.SERVICE_DOMAIN,
            DeviceConnectLimits.SERVICE_TYPE,
            "${advertisement.name}-${advertisement.deviceId.take(6)}",
            advertisement.port.toLong(),
        )
        service.delegate = delegate
        service.setTXTRecordData(txtRecord(advertisement))
        service.publish()
        this.service = service
        val browser = NSNetServiceBrowser()
        browser.delegate = delegate
        browser.searchForServicesOfType(DeviceConnectLimits.SERVICE_TYPE, DeviceConnectLimits.SERVICE_DOMAIN)
        this.browser = browser
    }

    override suspend fun updateAdvertisement(advertisement: DeviceAdvertisement) {
        val previous = this.advertisement ?: return
        if (previous.name == advertisement.name && previous.port == advertisement.port && previous.state == advertisement.state) return
        val listener = this.listener ?: return
        start(advertisement, listener)
    }

    override fun stop() {
        runCatching { service?.stop() }
        runCatching { browser?.stop() }
        service?.delegate = null
        browser?.delegate = null
        service = null
        browser = null
        delegate = null
        listener = null
        advertisement = null
        names.clear()
    }

    internal fun publish(service: NSNetService) {
        val host = service.hostName?.removeSuffix(".") ?: return
        if (!DeviceConnectHosts.isPrivateOrLocal(host) && !host.endsWith(".local")) return
        val dictionary = service.TXTRecordData()?.let { NSNetService.dictionaryFromTXTRecordData(it) }.orEmpty()
        val id = (dictionary["id"] as? NSData)?.toByteArray()?.decodeToString() ?: return
        if (!DeviceConnectHosts.isValidDeviceId(id)) return
        if (id == advertisement?.deviceId) return
        val name = (dictionary["name"] as? NSData)?.toByteArray()?.decodeToString().orEmpty().ifBlank { service.name }
        val platform = DevicePlatform.fromWire((dictionary["platform"] as? NSData)?.toByteArray()?.decodeToString())
        names[service.name] = id
        listener?.onFound(
            DiscoveredDevice(
                deviceId = id,
                name = name,
                platform = platform,
                endpoint = DeviceEndpoint(host, service.port.toInt()),
                lastSeenEpochMs = (NSDate().timeIntervalSince1970 * 1000.0).toLong(),
            ),
        )
    }

    internal fun removed(serviceName: String) {
        val id = names.remove(serviceName) ?: return
        listener?.onLost(id)
    }

    internal fun failure(message: String) {
        listener?.onFailure(message)
    }

    private fun txtRecord(advertisement: DeviceAdvertisement): NSData? {
        val dictionary: Map<Any?, Any> = mapOf(
            "id" to advertisement.deviceId.encodeToByteArray().toNSData(),
            "name" to advertisement.name.encodeToByteArray().toNSData(),
            "platform" to advertisement.platform.wire.encodeToByteArray().toNSData(),
            "proto" to DeviceConnectLimits.PROTOCOL_VERSION.toString().encodeToByteArray().toNSData(),
            "state" to advertisement.state.encodeToByteArray().toNSData(),
        )
        return NSNetService.dataFromTXTRecordDictionary(dictionary)
    }

    private class Delegate(private val owner: IosDeviceDiscovery) :
        NSObject(),
        NSNetServiceDelegateProtocol,
        NSNetServiceBrowserDelegateProtocol {
        override fun netServiceDidResolveAddress(service: NSNetService) {
            owner.publish(service)
        }

        override fun netServiceBrowser(
            browser: NSNetServiceBrowser,
            didFindService: NSNetService,
            moreComing: Boolean,
        ) {
            didFindService.delegate = this
            didFindService.resolveWithTimeout(5.0)
        }

        override fun netServiceBrowser(
            browser: NSNetServiceBrowser,
            didRemoveService: NSNetService,
            moreComing: Boolean,
        ) {
            owner.removed(didRemoveService.name)
        }

    }
}

private fun ByteArray.toNSData(): NSData = usePinned {
    NSData.create(bytes = it.addressOf(0), length = size.toULong())
}

private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    return bytes?.reinterpret<ByteVar>()?.readBytes(size) ?: ByteArray(0)
}
