package moe.rukamori.archivetune.playback

import androidx.media3.common.C
import androidx.media3.common.Player
import moe.rukamori.archivetune.extensions.mediaItems
import moe.rukamori.archivetune.extensions.toPersistQueue
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.models.PersistQueue
import moe.rukamori.archivetune.playback.queues.EmptyQueue
import moe.rukamori.archivetune.playback.queues.YouTubePlaylistQueue
import moe.rukamori.archivetune.playback.queues.finiteOccurrence

/** Main thread only. Current items/position are paired with the identity that installed them. */
internal fun QueueCheckpointGate.persistQueue(
    player: Player,
    items: List<MediaMetadata>,
    mediaItemIndex: Int = player.currentMediaItemIndex,
    position: Long = player.currentPosition,
): PersistQueue {
    val identity = if (items.isEmpty()) EmptyQueue else persistenceQueue
    val mediaItems = player.mediaItems
    return identity.toPersistQueue(
        title = persistenceTitle.takeIf { items.isNotEmpty() },
        items = items,
        mediaItemIndex = mediaItemIndex,
        position = position,
        timelineOccurrences = mediaItems.map { item ->
            (identity as? YouTubePlaylistQueue)?.occurrenceOf(item) ?: item.finiteOccurrence()
        },
    ).copy(
        savedAtEpochMs = System.currentTimeMillis(),
        manualQueueKinds = mediaItems.map { it.userQueuedKind()?.ordinal },
        shuffleOrder = buildList {
            val timeline = player.currentTimeline
            var index = timeline.getFirstWindowIndex(true)
            while (index != C.INDEX_UNSET && size < player.mediaItemCount) {
                add(index)
                index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, true)
            }
        },
    )
}
