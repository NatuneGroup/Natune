package moe.rukamori.archivetune.shared.data.deviceconnect

import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.origin
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceConnectHosts
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceConnectLimits

/** The Ktor CIO WebSocket server: OS-assigned port, `/connect`, binary frames, LAN admission. */
class DeviceConnectServer(
    private val onChannel: suspend (DeviceFrameChannel) -> Unit,
) {
    private var server: EmbeddedServer<*, *>? = null

    suspend fun start(): Int = withContext(Dispatchers.Default) {
        check(server == null) { "Device Connect server already started" }
        println("dc-server build")
        val started = embeddedServer(CIO, port = 0, host = "0.0.0.0") {
            install(WebSockets) {
                maxFrameSize = DeviceConnectLimits.MAX_FRAME_BYTES.toLong()
                pingPeriodMillis = DeviceConnectLimits.HEARTBEAT_INTERVAL_MS
                timeoutMillis = DeviceConnectLimits.HEARTBEAT_TIMEOUT_MS
            }
            routing {
                webSocket(DeviceConnectLimits.WS_ROUTE) {
                    val remoteHost = call.request.origin.remoteHost
                    if (!DeviceConnectHosts.isPrivateOrLocal(remoteHost)) return@webSocket
                    onChannel(KtorDeviceChannel(this))
                }
            }
        }.also { println("dc-server built") }.start(wait = false)
        println("dc-server started")
        server = started
        println("dc-server resolving")
        val port = started.engine.resolvedConnectors().firstOrNull()?.port
        println("dc-server resolved $port")
        if (port == null) {
            started.stop(500, 1_000)
            server = null
            error("Ktor did not resolve the Device Connect port")
        }
        port
    }

    fun stop() {
        val current = server
        server = null
        runCatching { current?.stop(500, 1_000) }
    }
}
