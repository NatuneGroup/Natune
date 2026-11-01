@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.data.platform.ios

import kotlinx.cinterop.*
import platform.CoreFoundation.CFRelease
import platform.SystemConfiguration.*
import platform.posix.*
import moe.rukamori.archivetune.shared.domain.settings.CanvasConnectivity

/**
 * One default-route snapshot, not an Internet probe or a pack-specific network observer.
 * A reachable route is only permission to TRY the bounded transfer; it does not prove server access.
 * Cellular is deliberately not excluded. No DNS/IP geolocation or secondary locale is involved.
 */
fun iosNetworkAvailable(): Boolean = memScoped {
    val address = alloc<sockaddr_in>()
    memset(address.ptr, 0, sizeOf<sockaddr_in>().toULong())
    address.sin_len = sizeOf<sockaddr_in>().toUByte()
    address.sin_family = AF_INET.toUByte()
    val route = SCNetworkReachabilityCreateWithAddress(null, address.ptr.reinterpret())
        ?: return@memScoped false
    try {
        val flags = alloc<SCNetworkReachabilityFlagsVar>()
        if (!SCNetworkReachabilityGetFlags(route, flags.ptr)) return@memScoped false
        val value = flags.value
        val reachable = value and kSCNetworkReachabilityFlagsReachable != 0u
        val needsConnection = value and kSCNetworkReachabilityFlagsConnectionRequired != 0u
        val automatic = value and (kSCNetworkReachabilityFlagsConnectionOnDemand or
            kSCNetworkReachabilityFlagsConnectionOnTraffic) != 0u
        val needsInteraction = value and kSCNetworkReachabilityFlagsInterventionRequired != 0u
        reachable && (!needsConnection || (automatic && !needsInteraction))
    } finally { CFRelease(route) }
}
/**
 * The reachability snapshot the canvas owner measures its playback gate on: online, and whether the default route
 * rides a cellular (WWAN) interface, which the canvas policy treats as metered and cellular. Wi-Fi is the absence
 * of WWAN; this host does not measure metering itself. The same caution applies as for [iosNetworkAvailable]: a
 * reachable route is only permission to try the bounded request.
 */
fun iosCanvasConnectivity(): CanvasConnectivity = memScoped {
    val address = alloc<sockaddr_in>()
    memset(address.ptr, 0, sizeOf<sockaddr_in>().toULong())
    address.sin_len = sizeOf<sockaddr_in>().toUByte()
    address.sin_family = AF_INET.toUByte()
    val route = SCNetworkReachabilityCreateWithAddress(null, address.ptr.reinterpret())
        ?: return@memScoped CanvasConnectivity(online = false, wifi = false, metered = true)
    try {
        val flags = alloc<SCNetworkReachabilityFlagsVar>()
        if (!SCNetworkReachabilityGetFlags(route, flags.ptr)) {
            return@memScoped CanvasConnectivity(online = false, wifi = false, metered = true)
        }
        val value = flags.value
        val reachable = value and kSCNetworkReachabilityFlagsReachable != 0u
        val needsConnection = value and kSCNetworkReachabilityFlagsConnectionRequired != 0u
        val automatic = value and (kSCNetworkReachabilityFlagsConnectionOnDemand or
            kSCNetworkReachabilityFlagsConnectionOnTraffic) != 0u
        val needsInteraction = value and kSCNetworkReachabilityFlagsInterventionRequired != 0u
        val online = reachable && (!needsConnection || (automatic && !needsInteraction))
        val wwan = value and kSCNetworkReachabilityFlagsIsWWAN != 0u
        CanvasConnectivity(online = online, wifi = !wwan, metered = wwan, cellular = wwan)
    } finally { CFRelease(route) }
}
