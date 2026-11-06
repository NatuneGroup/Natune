package moe.rukamori.archivetune.shared.data.deviceconnect

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.url
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceEndpoint

/** Dials a discovered or manually entered peer. The host's engine supplies the transport. */
class DeviceConnectClient(private val base: HttpClient) {
    private val client: HttpClient by lazy {
        runCatching { base.config { install(WebSockets) } }.getOrDefault(base)
    }

    suspend fun connect(endpoint: DeviceEndpoint): DeviceFrameChannel {
        require(endpoint.port in 1..65_535)
        val session = client.webSocketSession { url(endpoint.webSocketUrl()) }
        return KtorDeviceChannel(session)
    }
}
