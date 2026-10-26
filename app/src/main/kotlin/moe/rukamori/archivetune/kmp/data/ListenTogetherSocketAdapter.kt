/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.kmp.data

import android.content.Context
import android.os.PowerManager
import java.util.concurrent.TimeUnit
import moe.rukamori.archivetune.shared.data.listentogether.ListenTogetherSocket
import moe.rukamori.archivetune.shared.data.listentogether.ListenTogetherSocketFactory
import moe.rukamori.archivetune.shared.data.listentogether.ListenTogetherSocketListener
import moe.rukamori.archivetune.utils.withAppProxy
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/**
 * The Android Listen Together transport: an OkHttp WebSocket behind the shared [ListenTogetherSocketFactory] port.
 * The timeouts, the 30 s ping and the app proxy match the retained Android client, so a server sees the same
 * connection shape. Every frame goes out as a binary message, as the Android client sends them.
 *
 * The partial wake lock belongs to the newest socket only. It is taken on open and again on each received frame, with
 * the retained Android client's ten-minute timeout, and it is released when that socket closes, fails or is cancelled.
 * The Android client takes the lock once per room entry or reconnect and releases it on leave or kick. This lock is
 * therefore narrower during reconnect backoff and is refreshed by traffic, which the Android client does not do.
 * A late callback from a replaced socket cannot release the lock a newer connection holds. Every callback is forwarded
 * unchanged, because the shared client already drops callbacks from attempts that are no longer current.
 */
class OkHttpListenTogetherSocketFactory(
    context: Context,
    private val httpClient: OkHttpClient = OkHttpClient.Builder().withAppProxy()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build(),
) : ListenTogetherSocketFactory {
    private val wakeLock: PowerManager.WakeLock? =
        (context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)

    /** The socket the client currently owns. Only this socket acquires or releases the wake lock. */
    @Volatile
    private var activeSocket: OkHttpSocket? = null

    override fun open(url: String, listener: ListenTogetherSocketListener): ListenTogetherSocket {
        // An invalid URL throws here. The client counts a throw from open as a failed attempt.
        val request = Request.Builder().url(url).build()
        val socket = OkHttpSocket(listener)
        // Assigned before the socket opens, so the first callback already sees it as the active one.
        activeSocket = socket
        socket.attach(httpClient.newWebSocket(request, socket.callbacks))
        return socket
    }

    private fun keepAwake(socket: OkHttpSocket) {
        if (activeSocket !== socket) return
        val lock = wakeLock ?: return
        if (!lock.isHeld) lock.acquire(WAKE_LOCK_TIMEOUT_MS)
    }

    private fun release(socket: OkHttpSocket) {
        if (activeSocket !== socket) return
        activeSocket = null
        val lock = wakeLock ?: return
        if (lock.isHeld) runCatching { lock.release() }
    }

    private inner class OkHttpSocket(private val listener: ListenTogetherSocketListener) : ListenTogetherSocket {
        @Volatile
        private var transport: WebSocket? = null

        /** The callbacks OkHttp delivers to this socket. They run on OkHttp's threads, not on the client's scope. */
        val callbacks: WebSocketListener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                keepAwake(this@OkHttpSocket)
                listener.onOpen()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                keepAwake(this@OkHttpSocket)
                listener.onMessage(text.toByteArray())
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                keepAwake(this@OkHttpSocket)
                listener.onMessage(bytes.toByteArray())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                listener.onClosing(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                release(this@OkHttpSocket)
                listener.onClosed(code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                release(this@OkHttpSocket)
                listener.onFailure(t)
            }
        }

        fun attach(socket: WebSocket) {
            transport = socket
        }

        override fun send(frame: ByteArray): Boolean = transport?.send(ByteString.of(*frame)) ?: false

        override fun close(code: Int, reason: String) {
            transport?.close(code, reason)
        }

        override fun cancel() {
            transport?.cancel()
            release(this)
        }
    }

    private companion object {
        /** The tag matches the retained Android client's wake-lock tag, so a battery report reads the same. */
        const val WAKE_LOCK_TAG = "archivetune:ListenTogether"

        /** Ten minutes, as the Android client uses. A socket that stays open re-takes the lock on the next frame. */
        const val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L
    }
}
