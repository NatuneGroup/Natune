package moe.rukamori.archivetune.shared.data.deviceconnect

import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.origin
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceConnectHosts
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceConnectLimits

/** The Ktor CIO WebSocket server: OS-assigned port, `/connect`, binary frames, LAN admission. */
class DeviceConnectServer(
    private val onChannel: suspend (DeviceFrameChannel) -> Unit,
) {
    private var server: EmbeddedServer<*, *>? = null

    /**
     * The engine's own lifetime. Ktor attaches the accept loop of a started engine to the coroutine context it was
     * started in, so starting it inside a `withContext` — or inside the caller's scope — hides a long-lived job
     * inside a coroutine whose completion everybody awaits: `start()` then never returns, `withTimeout` around it
     * cannot break a coroutine that is merely waiting for its own children, and a desktop toggle or a test teardown
     * waits for ever. The engine is started on this scope instead, which is nobody's child, and only the resolved
     * port crosses back to the caller.
     */
    private val engineLifetime = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    suspend fun start(): Int {
        check(server == null) { "Device Connect server already started" }
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
        }
        val bound = CompletableDeferred<Int>()
        engineLifetime.launch {
            runCatching {
                started.start(wait = false)
                started.engine.resolvedConnectors().firstOrNull()?.port
            }.onSuccess { port ->
                // `null` is a bind failure, not a success: `bound` must settle either way so the caller is never
                // parked on an engine that will not resolve a connector.
                if (port == null) bound.completeExceptionally(IllegalStateException("Ktor did not resolve the Device Connect port")) else bound.complete(port)
            }.onFailure { failure -> bound.completeExceptionally(failure) }
        }
        server = started
        return try {
            // Cancellable on purpose: the caller's own budget (or its cancellation) releases it, so a bind that
            // never resolves fails the caller instead of hanging it.
            bound.await()
        } catch (failure: Throwable) {
            server = null
            engineLifetime.launch { runCatching { started.stop(500, 1_000) } }
            throw failure
        }
    }

    /**
     * Stops the engine without parking the caller on it. Ktor joins the application job inside `stop()`, and the
     * per-channel handlers run on that job: a handler still suspended on its connection keeps the join from
     * returning, so a caller-side `stop()` becomes an unbounded wait on the desktop shutdown path and in every
     * test teardown. The handle is dropped first — a stop that does not finish can neither wedge the caller nor
     * keep the next start from binding — and the join runs on the engine's own scope, which is daemon, so a stalled
     * engine cannot hold the process open either.
     */
    fun stop() {
        val current = server
        server = null
        if (current == null) return
        engineLifetime.launch { runCatching { current.stop(500, 1_000) } }
    }
}
