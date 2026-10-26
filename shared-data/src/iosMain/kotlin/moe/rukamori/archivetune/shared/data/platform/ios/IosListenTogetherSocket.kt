@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.data.platform.ios

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import moe.rukamori.archivetune.shared.data.listentogether.ListenTogetherSocket
import moe.rukamori.archivetune.shared.data.listentogether.ListenTogetherSocketFactory
import moe.rukamori.archivetune.shared.data.listentogether.ListenTogetherSocketListener
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionWebSocketCloseCode
import platform.Foundation.NSURLSessionWebSocketDelegateProtocol
import platform.Foundation.NSURLSessionWebSocketMessage
import platform.Foundation.NSURLSessionWebSocketTask
import platform.Foundation.NSURLRequest
import platform.darwin.NSObject

/**
 * The iOS Listen Together transport: an `NSURLSessionWebSocketTask` behind the shared
 * [ListenTogetherSocketFactory] port. Binary frames go out as binary messages, as the Android and
 * desktop clients send them; a text frame from the server is passed on as its UTF-8 bytes.
 *
 * Every callback is delivered on a Foundation queue, which is why this adapter only forwards: the owner
 * moves each callback onto its serial scope and drops callbacks from attempts it replaced.
 */
class IosListenTogetherSocketFactory : ListenTogetherSocketFactory {
    override fun open(url: String, listener: ListenTogetherSocketListener): ListenTogetherSocket {
        val nsUrl = NSURL.URLWithString(url)
            ?: throw IllegalArgumentException("Invalid Listen Together URL")
        val socket = IosSocket(listener)
        val session = NSURLSession.sessionWithConfiguration(
            NSURLSessionConfiguration.defaultSessionConfiguration,
            socket,
            null,
        )
        val task = session.webSocketTaskWithRequest(NSURLRequest.requestWithURL(nsUrl))
        socket.attach(session, task)
        task.resume()
        return socket
    }
}

private class IosSocket(
    private val listener: ListenTogetherSocketListener,
) : NSObject(), ListenTogetherSocket, NSURLSessionWebSocketDelegateProtocol {
    private var session: NSURLSession? = null
    private var task: NSURLSessionWebSocketTask? = null

    /** Set by the first terminal outcome; later callbacks are dropped. */
    private var terminated = false
    private var closeSent = false

    fun attach(session: NSURLSession, task: NSURLSessionWebSocketTask) {
        this.session = session
        this.task = task
    }

    override fun URLSession(
        session: NSURLSession,
        webSocketTask: NSURLSessionWebSocketTask,
        didOpenWithProtocol: String?,
    ) {
        if (terminated) return
        listener.onOpen()
        receive()
    }

    override fun URLSession(
        session: NSURLSession,
        webSocketTask: NSURLSessionWebSocketTask,
        didCloseWithCode: NSURLSessionWebSocketCloseCode,
        reason: NSData?,
    ) {
        if (terminated) return
        terminated = true
        listener.onClosed(didCloseWithCode.toInt(), reason?.toByteArray()?.decodeToString().orEmpty())
    }

    override fun send(frame: ByteArray): Boolean {
        val task = this.task ?: return false
        if (terminated || closeSent) return false
        val data = if (frame.isEmpty()) {
            NSData()
        } else {
            frame.usePinned { pinned -> NSData.dataWithBytes(pinned.addressOf(0), frame.size.convert()) }
        } ?: return false
        task.sendMessage(NSURLSessionWebSocketMessage(data)) { error -> if (error != null) fail(error) }
        return true
    }

    override fun close(code: Int, reason: String) {
        if (terminated || closeSent) return
        closeSent = true
        task?.cancelWithCloseCode(code.convert(), null)
    }

    override fun cancel() {
        if (terminated || closeSent) return
        terminated = true
        closeSent = true
        task?.cancel()
        session?.invalidateAndCancel()
    }

    private fun receive() {
        val task = this.task ?: return
        task.receiveMessageWithCompletionHandler { message, error ->
            when {
                terminated -> Unit
                error != null -> fail(error)
                message == null -> receive()
                else -> {
                    val bytes = message.data?.toByteArray() ?: message.string?.encodeToByteArray()
                    if (bytes != null) listener.onMessage(bytes)
                    receive()
                }
            }
        }
    }

    private fun fail(error: NSError) {
        if (terminated) return
        terminated = true
        listener.onFailure(IllegalStateException(error.localizedDescription ?: "WebSocket error"))
    }

    private fun NSData.toByteArray(): ByteArray {
        val size = length.toInt()
        if (size == 0) return ByteArray(0)
        return bytes!!.reinterpret<ByteVar>().readBytes(size)
    }
}
