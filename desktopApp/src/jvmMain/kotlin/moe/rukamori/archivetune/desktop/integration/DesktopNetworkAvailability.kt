package moe.rukamori.archivetune.desktop.integration

import java.net.NetworkInterface
import java.net.SocketException
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

private const val POLL_INTERVAL_MS = 5_000L

/** How long polling continues after the last collector leaves, so a brief pause does not restart it. */
private const val STOP_AFTER_UNSUBSCRIBE_MS = 5_000L

/**
 * The network availability that the Listen Together client waits on, as a [StateFlow] that the shared client collects.
 *
 * The JVM publishes no event for network changes, so this polls the local interfaces, and only while something collects the
 * flow. It reports what the machine can route, not whether a server is reachable. A dropped server is still handled by the
 * client's own retry rules, so this check never replaces them.
 */
internal class DesktopNetworkAvailability(scope: CoroutineScope) {
    val online: StateFlow<Boolean> = flow {
        while (true) {
            emit(hasLocalRoute())
            delay(POLL_INTERVAL_MS)
        }
    }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.WhileSubscribed(STOP_AFTER_UNSUBSCRIBE_MS), hasLocalRoute())
}

/**
 * True when an interface is up and has an address that can route beyond this machine. A platform refusal to list the
 * interfaces reports true, so a refusal never blocks reconnecting: the connection attempt then decides.
 */
private fun hasLocalRoute(): Boolean = try {
    val interfaces = NetworkInterface.getNetworkInterfaces()?.let { Collections.list(it) }.orEmpty()
    interfaces.any { networkInterface ->
        networkInterface.isUp && !networkInterface.isLoopback &&
            Collections.list(networkInterface.inetAddresses).any { address ->
                !address.isLoopbackAddress && !address.isLinkLocalAddress
            }
    }
} catch (_: SocketException) {
    true
}
