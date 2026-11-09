package moe.rukamori.archivetune.playback

/** Queue ownership can be lost without cancellation; only the last UI publisher may clear it. */
internal class PlaylistLoadStatePublisher {
    private var publisher: Any? = null
    val owner: Any?
        get() = publisher

    fun publish(owner: Any?) { publisher = owner }

    fun release(owner: Any): Boolean {
        if (publisher !== owner) return false
        publisher = null
        return true
    }
}
