@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package moe.rukamori.archivetune.shared.data.platform.ios

import kotlin.concurrent.Volatile
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import moe.rukamori.archivetune.shared.data.listentogether.ListenTogetherSocket
import moe.rukamori.archivetune.shared.data.listentogether.ListenTogetherSocketFactory
import moe.rukamori.archivetune.shared.data.listentogether.ListenTogetherSocketListener
import platform.Foundation.*
import platform.darwin.dispatch_after
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_time
import platform.darwin.DISPATCH_TIME_NOW
import platform.posix.memcpy

/**
 * The iOS Listen Together transport: an NSURLSessionWebSocketTask behind the shared
 * [ListenTogetherSocketFactory] port, matching the Android/desktop behaviour — every outgoing frame goes out as a
 * binary message, an incoming text or binary frame is handed on as its UTF-8 bytes, sends after a terminal event are
 * refused, and a failed send ends the attempt instead of dropping a frame. The socket must stay ws/wss only; the
 * shared client counts a throw from [open] as a failed attempt, so an invalid URL reaches the normal retry rules.
 *
 * NSURLSessionWebSocketTask exposes no handshake timeout of its own: [open] arms a timer that fails the attempt when
 * the server does not answer within [CONNECT_TIMEOUT_MS], mirroring the desktop's 30s connect bound. Callbacks may
 * arrive on any session delegate queue; the shared client already re-serialises them onto its own thread, so this
 * adapter only guards once with [terminated] so a stale socket can never alter a newer attempt.
 */
class IosListenTogetherSocketFactory(
    private val session: NSURLSession,
) : ListenTogetherSocketFactory {

    override fun open(url: String, listener: ListenTogetherSocketListener): ListenTogetherSocket {
        val nsUrl = NSURL.URLWithString(url)
            ?: throw IllegalArgumentException("Listen Together URL could not be parsed")
        require(nsUrl.scheme == "ws" || nsUrl.scheme == "wss") {
            "Listen Together connects over ws or wss only"
        }
        val socket = IosSocket(listener)
        val task = session.webSocketTaskWithURL(nsUrl)
        socket.attach(task)
        task.resume()
        return socket
    }

    private class IosSocket(
        private val listener: ListenTogetherSocketListener,
    ) : ListenTogetherSocket {
        /** Set by the first terminal outcome: a failure, a close from the server or a cancel. */
        @Volatile
        private var terminated = false

        @Volatile
        private var task: NSURLSessionWebSocketTask? = null

        /** A close requested before the socket was attached, replayed once the task exists. */
        @Volatile
        private var pendingClose: Pair<Int, String>? = null

        fun attach(task: NSURLSessionWebSocketTask) {
            this.task = task
            armConnectTimeout()
            pendingClose?.let { (code, reason) ->
                pendingClose = null
                close(code, reason)
            }
            readNext()
            probeOpen(task)
        }

        private fun probeOpen(task: NSURLSessionWebSocketTask) {
            // NSURLSessionWebSocketTask fires no open callback of its own: a pong reply is the only handshake proof.
            // The client waits for onOpen before sending, so the first send only happens after the pong arrives.
            task.sendPingWithPongReceiveHandler { error ->
                if (terminated) return@sendPingWithPongReceiveHandler
                if (error != null) {
                    fail(IosListenTogetherSocketException(error.localizedDescription ?: "websocket handshake failed"))
                } else {
                    opened = true
                    listener.onOpen()
                }
            }
        }

        private fun armConnectTimeout() {
            val deadline = dispatch_time(DISPATCH_TIME_NOW, CONNECT_TIMEOUT_MS * 1_000_000L)
            dispatch_after(deadline, dispatch_get_global_queue(0, 0u)) {
                if (!terminated) {
                    val current = task ?: return@dispatch_after
                    if (current.state == NSURLSessionTaskStateRunning && !opened) {
                        fail(IosListenTogetherSocketException("Listen Together handshake timed out"))
                        current.cancel()
                    }
                }
            }
        }

        /** Set when the socket first proves a completed handshake; the connect timeout checks it. */
        @Volatile
        private var opened = false

        private fun readNext() {
            val current = task ?: return
            if (terminated) return
            current.receiveMessageWithCompletionHandler { message, error ->
                if (error != null) {
                    val code = error.code.toInt()
                    // A deliberate cancel/close reports NSURLErrorCancelled; it is not a failure outcome.
                    if (code.toLong() != NSURLErrorCancelled) fail(IosListenTogetherSocketException(error.localizedDescription ?: "websocket read failed"))
                    return@receiveMessageWithCompletionHandler
                }
                val data = message?.data
                val string = message?.string
                val frame: ByteArray? =
                    when {
                        data != null -> data.toByteArray()
                        string != null -> string.encodeToByteArray()
                        else -> null
                    }
                opened = true
                if (frame != null && frame.size > MAX_FRAME_BYTES) {
                    fail(IosListenTogetherSocketException("Listen Together frame exceeded limit"))
                    return@receiveMessageWithCompletionHandler
                }
                if (frame != null && !terminated) listener.onMessage(frame)
                if (!terminated) readNext()
            }
        }

        override fun send(frame: ByteArray): Boolean {
            val current = task ?: return false
            if (terminated) return false
            val message = NSURLSessionWebSocketMessage(frame.toNSData())
            var accepted = true
            current.sendMessage(message) { error ->
                if (error != null) {
                    accepted = false
                    fail(IosListenTogetherSocketException(error.localizedDescription ?: "websocket send failed"))
                }
            }
            return accepted
        }

        override fun close(code: Int, reason: String) {
            val current = task
            if (current == null) {
                pendingClose = code to reason
                return
            }
            current.cancelWithCloseCode(code.toLong(), reason.toNSData())
            // The factory uses no session delegate, so the graceful-close confirmation is not observable here.
            // The port requires onClosed after a normal close; the task is already gone by the contract.
            if (!terminated) {
                terminated = true
                listener.onClosing(code, reason)
                listener.onClosed(code, reason)
            }
        }

        override fun cancel() {
            terminated = true
            task?.cancel()
        }

        private fun fail(cause: Throwable) {
            if (terminated) return
            terminated = true
            listener.onFailure(cause)
            task?.cancel()
        }
    }

    private class IosListenTogetherSocketException(message: String) : Exception(message)

    private companion object {
        const val MAX_FRAME_BYTES = 8 * 1024 * 1024

        /** Mirrors the desktop's 30s connection bound; the shared client owns the retry rules after it. */
        const val CONNECT_TIMEOUT_MS = 30_000L
    }
}

private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    val out = ByteArray(size)
    out.usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, length) }
    return out
}

private fun ByteArray.toNSData(): NSData {
    if (isEmpty()) return NSData()
    return usePinned { pinned -> NSData.create(bytes = pinned.addressOf(0), length = size.convert()) }
}

private fun String.toNSData(): NSData = (this as NSString).dataUsingEncoding(NSUTF8StringEncoding) ?: NSData()
