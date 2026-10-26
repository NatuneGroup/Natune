package moe.rukamori.archivetune.shared.data.listentogether

/**
 * One connection to a Listen Together server, as the room owner sees it. The host wraps its own WebSocket engine
 * (OkHttp on Android, a desktop or iOS engine elsewhere), so common code never names an engine type. A socket is
 * single-use: every new attempt opens a new one.
 */
interface ListenTogetherSocket {
    /** Queues one frame for sending. Returns false when the socket can no longer accept it. */
    fun send(frame: ByteArray): Boolean

    /** Starts a normal close. The listener still receives [ListenTogetherSocketListener.onClosed] afterwards. */
    fun close(code: Int, reason: String)

    /** Drops the connection at once. Callbacks already in flight may still arrive; the owner ignores them. */
    fun cancel()
}

/**
 * Callbacks for one connection attempt. The host may invoke them from any thread: the owner moves each one onto its
 * own serial queue and discards callbacks from attempts that are no longer current, so a stale socket can never
 * alter a newer connection.
 */
interface ListenTogetherSocketListener {
    fun onOpen()

    /** One binary or text frame; a text frame is passed as its UTF-8 bytes. */
    fun onMessage(frame: ByteArray)

    fun onClosing(code: Int, reason: String)

    fun onClosed(code: Int, reason: String)

    /** The attempt failed or dropped. [cause] feeds diagnostics only and is never shown to the user. */
    fun onFailure(cause: Throwable?)
}

/**
 * Opens a socket to [url] that reports every callback to [listener]. Throwing from here counts as a failed attempt,
 * which lets an invalid URL reach the normal retry rules instead of crashing the caller.
 */
fun interface ListenTogetherSocketFactory {
    fun open(url: String, listener: ListenTogetherSocketListener): ListenTogetherSocket
}
