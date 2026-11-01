package moe.rukamori.archivetune.desktop.integration.linux

import java.io.ByteArrayOutputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Socket names the Hyprland IPC exposes inside $XDG_RUNTIME_DIR/hypr/$HYPRLAND_INSTANCE_SIGNATURE. */
private const val QUERY_SOCKET = ".socket.sock"
private const val EVENT_SOCKET = ".socket2.sock"
/** Replies are one-shot JSON documents; a reply above this bound is treated as a failure, not buffered. */
private const val QUERY_LIMIT_BYTES = 1 shl 20
private const val QUERY_DEADLINE_MS = 500L
private const val EVALUATE_DEBOUNCE_MS = 100L
private const val IO_BUFFER = 16 * 1024
private const val SOCKET_IO_PAUSE_MS = 5L

/**
 * Hyprland compositor window visibility. An XWayland window on an unseen workspace keeps its X visibility, so the
 * X11 observer alone would report it visible while the compositor shows another workspace. This watcher reads the
 * compositor's IPC instead: `j/clients` and `j/monitors` over the query socket, change events over the event
 * socket. It reports only when the answer changes; a failed query keeps the last answer, and a window the
 * compositor has not listed yet is never reported hidden.
 *
 * [onHidden] receives true while the window is unmapped, compositor-hidden or on a workspace no monitor shows,
 * and false once any monitor's active or special workspace contains it. Pinned windows are always visible.
 */
class HyprlandWorkspaceVisibility(
    private val title: String?,
    private val onHidden: (hidden: Boolean) -> Unit,
) : AutoCloseable {
    private val ownPid = ProcessHandle.current().pid()
    private val directory = Path.of(
        System.getenv("XDG_RUNTIME_DIR") ?: throw IllegalStateException("XDG_RUNTIME_DIR is not set"),
        "hypr",
        System.getenv("HYPRLAND_INSTANCE_SIGNATURE") ?: throw IllegalStateException("HYPRLAND_INSTANCE_SIGNATURE is not set"),
    )
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "hyprland-visibility").apply { isDaemon = true }
    }
    @Volatile private var eventSocket: SocketChannel? = null
    @Volatile private var hidden: Boolean? = null
    @Volatile private var closed = false

    // The event reader blocks on the socket, so it gets its own thread; the single scheduler
    // thread stays free for evaluations or they would never run.
    private val eventThread = Thread({ eventLoop() }, "hyprland-events").apply { isDaemon = true }

    init {
        eventThread.start()
        scheduler.execute { evaluate() }
    }

    private fun eventLoop() {
        try {
            SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
                channel.connect(UnixDomainSocketAddress.of(directory.resolve(EVENT_SOCKET).toString()))
                eventSocket = channel
                val buffer = ByteBuffer.allocate(IO_BUFFER)
                while (!closed && channel.read(buffer) >= 0) {
                    if (buffer.position() > 0) {
                        buffer.clear()
                        // Any event can move the window; the debounce keeps a workspace-switch burst to one query.
                        if (!closed) scheduler.schedule({ evaluate() }, EVALUATE_DEBOUNCE_MS, TimeUnit.MILLISECONDS)
                    }
                }
            }
        } catch (error: Exception) {
            // A compositor restart closes the socket; the last answer stands rather than guessing visibility.
        } finally {
            eventSocket = null
        }
    }

    private fun evaluate() {
        if (closed) return
        val visible = try {
            hyprlandWindowVisible(ownPid, title, request("j/clients"), request("j/monitors"))
        } catch (error: Exception) {
            null
        } ?: return
        if (hidden == !visible) return
        hidden = !visible
        onHidden(!visible)
    }

    /** One bounded request to the query socket; a deadline miss leaves the last answer in place. */
    private fun request(command: String): String {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(QUERY_DEADLINE_MS)
        val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
        try {
            channel.configureBlocking(false)
            channel.connect(UnixDomainSocketAddress.of(directory.resolve(QUERY_SOCKET).toString()))
            while (!channel.finishConnect()) {
                if (System.nanoTime() > deadline || closed) return ""
                Thread.sleep(SOCKET_IO_PAUSE_MS)
            }
            val request = StandardCharsets.UTF_8.encode(command)
            while (request.hasRemaining()) {
                if (channel.write(request) == 0) {
                    if (System.nanoTime() > deadline || closed) return ""
                    Thread.sleep(SOCKET_IO_PAUSE_MS)
                }
            }
            channel.shutdownOutput()
            val reply = ByteArrayOutputStream()
            val buffer = ByteBuffer.allocate(IO_BUFFER)
            while (reply.size() <= QUERY_LIMIT_BYTES) {
                val read = channel.read(buffer)
                if (read < 0) break
                if (read == 0) {
                    if (System.nanoTime() > deadline || closed) break
                    Thread.sleep(SOCKET_IO_PAUSE_MS)
                    continue
                }
                buffer.flip()
                reply.write(buffer.array(), buffer.arrayOffset(), buffer.remaining())
                buffer.clear()
            }
            return reply.toString(StandardCharsets.UTF_8)
        } finally {
            channel.close()
        }
    }

    override fun close() {
        closed = true
        runCatching { eventSocket?.close() }
        scheduler.shutdownNow()
        try {
            eventThread.join(QUERY_DEADLINE_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        hidden = null
        onHidden(false)
    }

    companion object {
        /** The watcher only exists where the compositor exports its instance. */
        fun supported(): Boolean =
            !System.getenv("XDG_RUNTIME_DIR").isNullOrEmpty() &&
                !System.getenv("HYPRLAND_INSTANCE_SIGNATURE").isNullOrEmpty()
    }
}

/**
 * Whether Hyprland reports our own window visible. The owning client is found by pid, the title breaks a tie or
 * substitutes when the compositor lists a helper's pid. Visible means mapped, not compositor-hidden, and either
 * pinned or on a workspace some monitor shows (active or special workspace).
 */
internal fun hyprlandWindowVisible(ownPid: Long, title: String?, clientsJson: String, monitorsJson: String): Boolean {
    val clients = Json.parseToJsonElement(clientsJson).jsonArray.mapNotNull { it as? JsonObject }
    val own = clients.firstOrNull { it.long("pid") == ownPid && it.matchesTitle(title) }
        ?: clients.firstOrNull { it.long("pid") == ownPid }
        ?: clients.firstOrNull { it.matchesTitle(title) }
        ?: return true
    if (own.bool("mapped") == false || own.bool("hidden") == true) return false
    if (own.bool("pinned") == true) return true
    val workspaceId = own.obj("workspace")?.int("id") ?: return true
    val active = Json.parseToJsonElement(monitorsJson).jsonArray.mapNotNull { it as? JsonObject }
        .flatMap { monitor -> listOfNotNull(monitor.obj("activeWorkspace"), monitor.obj("specialWorkspace")) }
        .mapNotNull { it.int("id") }
        .toSet()
    return workspaceId in active
}

private fun JsonObject.matchesTitle(title: String?): Boolean =
    !title.isNullOrEmpty() && (string("title") == title || string("initialTitle") == title)

private fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject
private fun JsonObject.int(name: String): Int? = this[name]?.jsonPrimitive?.intOrNull
private fun JsonObject.long(name: String): Long? = this[name]?.jsonPrimitive?.longOrNull
private fun JsonObject.bool(name: String): Boolean? = this[name]?.jsonPrimitive?.booleanOrNull
private fun JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.takeIf { it.isString }?.content
