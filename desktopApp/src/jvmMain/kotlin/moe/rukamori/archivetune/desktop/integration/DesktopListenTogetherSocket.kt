package moe.rukamori.archivetune.desktop.integration

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import moe.rukamori.archivetune.shared.data.listentogether.ListenTogetherSocket
import moe.rukamori.archivetune.shared.data.listentogether.ListenTogetherSocketFactory
import moe.rukamori.archivetune.shared.data.listentogether.ListenTogetherSocketListener

/** How long one connection attempt may wait for the handshake. The owner's own retry rules decide what follows. */
private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(30)

/**
 * The largest single message the desktop client accepts. Listen Together frames are small, so a larger message is treated
 * as a broken or hostile server and ends the attempt instead of growing a buffer without bound.
 */
private const val MAX_FRAME_BYTES = 8 * 1024 * 1024

/**
 * The desktop Listen Together transport: a JDK WebSocket behind the shared [ListenTogetherSocketFactory] port. The JDK
 * client is part of the platform, and `java.net.http` is already in the packaged module list, so no dependency is added.
 *
 * Every outgoing frame goes out as a binary message, as the Android client sends them. A text frame from the server is
 * passed on as its UTF-8 bytes. The JDK asks for one message at a time and sends one at a time, so this adapter requests the
 * next message only after the current one has been handed on, and it queues outgoing frames behind the one in flight. A
 * frame is therefore never interleaved with another, and a failed send ends the attempt rather than dropping a frame.
 */
internal class DesktopListenTogetherSocketFactory(
    private val client: HttpClient = newHttpClient(),
) : ListenTogetherSocketFactory {
    override fun open(url: String, listener: ListenTogetherSocketListener): ListenTogetherSocket {
        // An invalid URL throws here. The client counts a throw from open as a failed attempt, so it reaches the retry rules.
        val uri = URI.create(url)
        require(uri.scheme.equals("ws", ignoreCase = true) || uri.scheme.equals("wss", ignoreCase = true)) {
            "Listen Together connects over ws or wss only"
        }
        val socket = DesktopSocket(listener)
        client.newWebSocketBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .buildAsync(uri, socket.callbacks)
            .whenComplete { webSocket, failure ->
                if (failure != null) socket.fail(failure) else socket.attach(webSocket)
            }
        // Assigned before the handshake completes, so the first callback already sees it as this attempt's socket.
        return socket
    }

    private class DesktopSocket(private val listener: ListenTogetherSocketListener) : ListenTogetherSocket {
        /** Set by the first terminal outcome: a failure, a close from the server or a cancel. Later events are dropped. */
        private val terminated = AtomicBoolean(false)

        @Volatile
        private var transport: WebSocket? = null

        /** A close requested before the handshake completes, replayed once the connection exists. */
        @Volatile
        private var pendingClose: Pair<Int, String>? = null

        private val outbound = ConcurrentLinkedQueue<ByteArray>()
        private val sending = AtomicBoolean(false)
        private val inbound = Inbound()

        val callbacks: WebSocket.Listener = object : WebSocket.Listener {
            override fun onOpen(webSocket: WebSocket) {
                // The JDK delivers nothing until the listener asks for a message, so the first request is made here.
                webSocket.request(1)
                if (!terminated.get()) listener.onOpen()
            }

            override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                if (!inbound.appendText(data)) return overflow(webSocket)
                return received(webSocket, last)
            }

            override fun onBinary(webSocket: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*>? {
                if (!inbound.appendBinary(data)) return overflow(webSocket)
                return received(webSocket, last)
            }

            override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
                if (terminated.compareAndSet(false, true)) {
                    outbound.clear()
                    listener.onClosing(statusCode, reason)
                    listener.onClosed(statusCode, reason)
                }
                return null
            }

            override fun onError(webSocket: WebSocket, error: Throwable) {
                fail(error)
            }
        }

        /** Hands on a complete message and asks for the next one. A part that is not the last only asks for more. */
        private fun received(webSocket: WebSocket, last: Boolean): CompletionStage<*>? {
            if (last) {
                val frame = inbound.takeFrame()
                if (!terminated.get()) listener.onMessage(frame)
            }
            webSocket.request(1)
            return null
        }

        private fun overflow(webSocket: WebSocket): CompletionStage<*>? {
            fail(IOException("A Listen Together message exceeded $MAX_FRAME_BYTES bytes"))
            webSocket.abort()
            return null
        }

        fun attach(connection: WebSocket) {
            transport = connection
            if (terminated.get()) {
                connection.abort()
                return
            }
            pendingClose?.let { (code, reason) ->
                pendingClose = null
                close(code, reason)
            }
            pumpOutbound()
        }

        override fun send(frame: ByteArray): Boolean {
            if (terminated.get()) return false
            // A copy, so the caller may reuse its array once send has returned.
            outbound.add(frame.copyOf())
            pumpOutbound()
            return true
        }

        override fun close(code: Int, reason: String) {
            if (terminated.get()) return
            val connection = transport
            if (connection == null) {
                pendingClose = code to reason
                return
            }
            try {
                connection.sendClose(code, reason).whenComplete { _, failure ->
                    if (failure != null) fail(failure)
                }
            } catch (invalid: IllegalArgumentException) {
                // The close code or reason is not one the protocol allows, so the attempt cannot close normally.
                fail(invalid)
            }
        }

        override fun cancel() {
            // A cancel is not reported back: the owner ignores callbacks from an attempt it has dropped.
            terminated.set(true)
            outbound.clear()
            transport?.abort()
        }

        /** Ends the attempt once and reports it. The socket is aborted, so no further frame or callback follows. */
        fun fail(cause: Throwable) {
            if (!terminated.compareAndSet(false, true)) return
            outbound.clear()
            transport?.abort()
            listener.onFailure(cause)
        }

        /**
         * Sends the next queued frame when no send is in flight. The release is followed by a second look at the queue, so a
         * frame queued between the poll and the release is not left waiting for a later send.
         */
        private fun pumpOutbound() {
            while (true) {
                val connection = transport ?: return
                if (terminated.get() || !sending.compareAndSet(false, true)) return
                val next = outbound.poll()
                if (next == null) {
                    sending.set(false)
                    if (outbound.isEmpty()) return
                    continue
                }
                try {
                    connection.sendBinary(ByteBuffer.wrap(next), true).whenComplete { _, failure ->
                        sending.set(false)
                        if (failure != null) fail(failure) else pumpOutbound()
                    }
                } catch (invalid: IllegalArgumentException) {
                    fail(invalid)
                }
                return
            }
        }
    }

    /**
     * Joins the parts of the message being received. Text is joined as characters, not as encoded bytes, so a surrogate pair
     * that a part boundary splits is still encoded whole.
     */
    private class Inbound {
        private val text = StringBuilder()
        private val binary = ByteArrayOutputStream()
        private var isText = false

        @Synchronized
        fun appendText(part: CharSequence): Boolean {
            if (text.length + part.length > MAX_FRAME_BYTES) return false
            isText = true
            text.append(part)
            return true
        }

        @Synchronized
        fun appendBinary(part: ByteBuffer): Boolean {
            if (binary.size() + part.remaining() > MAX_FRAME_BYTES) return false
            isText = false
            val chunk = ByteArray(part.remaining())
            part.get(chunk)
            binary.write(chunk)
            return true
        }

        /** Returns the joined message as bytes and clears the buffers, so the next message starts empty. */
        @Synchronized
        fun takeFrame(): ByteArray {
            val frame = if (isText) text.toString().toByteArray(Charsets.UTF_8) else binary.toByteArray()
            text.setLength(0)
            binary.reset()
            isText = false
            return frame
        }
    }
}

/**
 * The system proxy settings apply, so a desktop user behind a proxy reaches the same servers as other desktop software.
 * The Android client applies its own app proxy instead; the desktop app has no separate proxy setting to honour.
 */
private fun newHttpClient(): HttpClient = HttpClient.newBuilder()
    .proxy(ProxySelector.getDefault())
    .build()
