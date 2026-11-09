package moe.rukamori.archivetune.playback

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import moe.rukamori.archivetune.playback.queues.Queue

/** Accept at the actual MediaSession mutation, after asynchronous Auto/voice resolution completes. */
@OptIn(UnstableApi::class)
internal class SessionTimelinePlayer(
    private val target: Player,
    private val install: (List<MediaItem>, () -> Unit) -> Unit,
    private val acceptEdit: (removal: Boolean) -> Unit,
) : ForwardingPlayer(target) {
    override fun setMediaItems(mediaItems: List<MediaItem>) = install(mediaItems) { target.setMediaItems(mediaItems) }
    override fun setMediaItems(mediaItems: List<MediaItem>, resetPosition: Boolean) =
        install(mediaItems) { target.setMediaItems(mediaItems, resetPosition) }
    override fun setMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long) =
        install(mediaItems) { target.setMediaItems(mediaItems, startIndex, startPositionMs) }
    override fun setMediaItem(mediaItem: MediaItem) = install(listOf(mediaItem)) { target.setMediaItem(mediaItem) }
    override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) =
        install(listOf(mediaItem)) { target.setMediaItem(mediaItem, startPositionMs) }
    override fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean) =
        install(listOf(mediaItem)) { target.setMediaItem(mediaItem, resetPosition) }
    override fun clearMediaItems() = install(emptyList()) { target.clearMediaItems() }

    override fun addMediaItem(mediaItem: MediaItem) = edit(false) { target.addMediaItem(mediaItem) }
    override fun addMediaItem(index: Int, mediaItem: MediaItem) = edit(false) { target.addMediaItem(index, mediaItem) }
    override fun addMediaItems(mediaItems: List<MediaItem>) = edit(false) { target.addMediaItems(mediaItems) }
    override fun addMediaItems(index: Int, mediaItems: List<MediaItem>) = edit(false) { target.addMediaItems(index, mediaItems) }
    override fun removeMediaItem(index: Int) = edit(true) { target.removeMediaItem(index) }
    override fun removeMediaItems(fromIndex: Int, toIndex: Int) = edit(true) { target.removeMediaItems(fromIndex, toIndex) }
    override fun replaceMediaItem(index: Int, mediaItem: MediaItem) = edit(true) { target.replaceMediaItem(index, mediaItem) }
    override fun replaceMediaItems(fromIndex: Int, toIndex: Int, mediaItems: List<MediaItem>) =
        edit(true) { target.replaceMediaItems(fromIndex, toIndex, mediaItems) }
    override fun moveMediaItem(currentIndex: Int, newIndex: Int) = edit(false) { target.moveMediaItem(currentIndex, newIndex) }
    override fun moveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int) =
        edit(false) { target.moveMediaItems(fromIndex, toIndex, newIndex) }

    private fun edit(removal: Boolean, mutation: () -> Unit) {
        mutation()
        acceptEdit(removal)
    }
}

internal fun queueSessionTimelinePlayer(
    target: Player,
    gate: QueueCheckpointGate,
    install: (Player, Queue?, String?, () -> Unit) -> Unit,
    save: () -> Unit,
    currentPlayer: () -> Player = { target },
): Player = SessionTimelinePlayer(
    target,
    install = { items, mutation ->
        if (target === currentPlayer()) {
            // Queue taps and "add current to playlist" replay the same occurrences, keeping their identity.
            val retained = items.isNotEmpty() && items.size == target.mediaItemCount &&
                items.indices.all { target.getMediaItemAt(it) === items[it] }
            install(target, gate.persistenceQueue.takeIf { retained }, gate.persistenceTitle.takeIf { retained }, mutation)
        }
    },
    acceptEdit = { removal ->
        if (target === currentPlayer()) {
            if (removal) gate.onUserRemoval() else gate.onUserAddition()
            save()
        }
    },
)
