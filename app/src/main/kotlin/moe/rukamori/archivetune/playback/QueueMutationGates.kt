package moe.rukamori.archivetune.playback

import androidx.media3.common.C
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import moe.rukamori.archivetune.constants.QueueLockedKey
import moe.rukamori.archivetune.extensions.getQueueWindows
import moe.rukamori.archivetune.extensions.move
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.get

/**
 * The QueueLocked gate the queue UIs route through (reached as PlayerConnection members, so no
 * import is needed at Compose call sites). Only user-initiated mutations come here — Android Auto,
 * MediaSession, the insert path's own evictions and recovery keep their direct player calls,
 * because a locked queue must still be drivable by the system and itself. Service extensions keep
 * the gate in one place without growing MusicService.
 */
fun MusicService.removeQueueItemAt(mediaItemIndex: Int) {
    if (dataStore.get(QueueLockedKey, false)) return
    player.removeMediaItem(mediaItemIndex)
}

fun MusicService.moveQueueItem(fromIndex: Int, toIndex: Int) {
    if (dataStore.get(QueueLockedKey, false)) return
    player.moveMediaItem(fromIndex, toIndex)
}

/** Drag-reorder under shuffle is a move too: the shuffled window order replaces the shuffle order. */
fun MusicService.reorderShuffledQueue(firstPeriodIndices: IntArray) {
    if (dataStore.get(QueueLockedKey, false)) return
    localPlayer.setShuffleOrder(DefaultShuffleOrder(firstPeriodIndices, System.currentTimeMillis()))
}

fun MusicService.moveQueueItemToNext(mediaItemIndex: Int) {
    if (dataStore.get(QueueLockedKey, false)) return
    val currentIndex = player.currentMediaItemIndex
    if (
        player.mediaItemCount < 2 ||
        currentIndex == C.INDEX_UNSET ||
        mediaItemIndex !in 0 until player.mediaItemCount ||
        mediaItemIndex == currentIndex
    ) {
        return
    }

    if (player.shuffleModeEnabled) {
        val shuffledIndices = player.getQueueWindows().map { it.firstPeriodIndex }.toMutableList()
        val sourcePosition = shuffledIndices.indexOf(mediaItemIndex)
        val currentPosition = shuffledIndices.indexOf(currentIndex)
        if (sourcePosition == -1 || currentPosition == -1) return

        val destinationPosition = (currentPosition + if (sourcePosition < currentPosition) 0 else 1)
            .coerceAtMost(shuffledIndices.lastIndex)
        if (sourcePosition == destinationPosition) return

        shuffledIndices.move(sourcePosition, destinationPosition)
        localPlayer.setShuffleOrder(
            DefaultShuffleOrder(
                shuffledIndices.toIntArray(),
                System.currentTimeMillis(),
            ),
        )
        return
    }

    val destinationIndex = (currentIndex + if (mediaItemIndex < currentIndex) 0 else 1)
        .coerceAtMost(player.mediaItemCount - 1)
    if (mediaItemIndex != destinationIndex) {
        player.moveMediaItem(mediaItemIndex, destinationIndex)
    }
}
