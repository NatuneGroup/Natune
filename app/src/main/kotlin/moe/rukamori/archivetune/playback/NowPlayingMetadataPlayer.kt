package moe.rukamori.archivetune.playback

import androidx.media3.common.FlagSet
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import java.util.IdentityHashMap

/** Session/notification metadata is a projection; resolving artwork never edits the playlist. */
internal class NowPlayingMetadataPlayer(
    target: Player,
    private val resolve: (MediaItem?, MediaMetadata) -> MediaMetadata,
) : ForwardingPlayer(target) {
    private val listeners = IdentityHashMap<Player.Listener, Player.Listener>()
    private var publishedMetadata: MediaMetadata? = null

    override fun getMediaMetadata(): MediaMetadata = resolve(currentMediaItem, super.getMediaMetadata())

    override fun addListener(listener: Player.Listener) {
        if (listeners.containsKey(listener)) return
        val forwarding = object : Player.Listener by listener {
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                val resolved = getMediaMetadata()
                publishedMetadata = resolved
                listener.onMediaMetadataChanged(resolved)
            }
        }
        listeners[listener] = forwarding
        super.addListener(forwarding)
    }

    override fun removeListener(listener: Player.Listener) {
        listeners.remove(listener)?.let { super.removeListener(it) }
    }

    /** Called after normal transitions, crossfade promotion, and current-track enrichment. */
    fun publishMetadata() {
        val metadata = mediaMetadata
        if (metadata == publishedMetadata) return
        publishedMetadata = metadata
        val events = Player.Events(FlagSet.Builder().add(Player.EVENT_MEDIA_METADATA_CHANGED).build())
        val snapshot = listeners.keys.toList()
        snapshot.forEach { if (listeners.containsKey(it)) it.onMediaMetadataChanged(metadata) }
        snapshot.forEach { if (listeners.containsKey(it)) it.onEvents(this, events) }
    }
}
