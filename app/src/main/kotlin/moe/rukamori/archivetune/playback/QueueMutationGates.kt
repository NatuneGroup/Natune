package moe.rukamori.archivetune.playback

import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.QueueLockedKey
import moe.rukamori.archivetune.extensions.getQueueWindows
import moe.rukamori.archivetune.extensions.move
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.get

private var queueLockedToast: Toast? = null

/**
 * Short notice that a queue mutation was refused by the lock. One shared toast, cancelled and
 * reshown, so a multi-select remove or a flurry of swipes cannot queue a parade of them.
 */
fun MusicService.showQueueLockedToast() {
    queueLockedToast?.cancel()
    queueLockedToast = Toast.makeText(this, R.string.queue_locked_toast, Toast.LENGTH_SHORT)
    queueLockedToast?.show()
}

/**
 * The QueueLocked gate the queue UIs route through (reached as PlayerConnection members, so no
 * import is needed at Compose call sites). Only user-initiated mutations come here — Android Auto,
 * MediaSession, the insert path's own evictions and recovery keep their direct player calls,
 * because a locked queue must still be drivable by the system and itself. Every gate returns
 * whether the mutation actually ran: on a rejection no timeline event ever arrives, so the caller
 * is the only place that can put optimistic drag and swipe state back.
 */
fun MusicService.removeQueueItemAt(mediaItemIndex: Int): Boolean {
    if (dataStore.get(QueueLockedKey, false)) {
        showQueueLockedToast()
        return false
    }
    if (mediaItemIndex !in 0 until player.mediaItemCount) return false
    player.removeMediaItem(mediaItemIndex)
    return true
}

fun MusicService.moveQueueItem(fromIndex: Int, toIndex: Int): Boolean {
    if (dataStore.get(QueueLockedKey, false)) {
        showQueueLockedToast()
        return false
    }
    if (fromIndex !in 0 until player.mediaItemCount || toIndex !in 0 until player.mediaItemCount) return false
    player.moveMediaItem(fromIndex, toIndex)
    return true
}

/** Drag-reorder under shuffle is a move too: the dragged window order replaces the shuffle order. */
fun MusicService.reorderShuffledQueue(fromIndex: Int, toIndex: Int): Boolean {
    if (dataStore.get(QueueLockedKey, false)) {
        showQueueLockedToast()
        return false
    }
    val shuffled = player.getQueueWindows().map { it.firstPeriodIndex }.toMutableList()
    if (fromIndex !in shuffled.indices || toIndex !in shuffled.indices) return false
    shuffled.move(fromIndex, toIndex)
    localPlayer.setShuffleOrder(DefaultShuffleOrder(shuffled.toIntArray(), System.currentTimeMillis()))
    return true
}

fun MusicService.moveQueueItemToNext(mediaItemIndex: Int): Boolean {
    if (dataStore.get(QueueLockedKey, false)) {
        showQueueLockedToast()
        return false
    }
    val currentIndex = player.currentMediaItemIndex
    if (
        player.mediaItemCount < 2 ||
        currentIndex == C.INDEX_UNSET ||
        mediaItemIndex !in 0 until player.mediaItemCount ||
        mediaItemIndex == currentIndex
    ) {
        return false
    }

    if (player.shuffleModeEnabled) {
        val shuffledIndices = player.getQueueWindows().map { it.firstPeriodIndex }.toMutableList()
        val sourcePosition = shuffledIndices.indexOf(mediaItemIndex)
        val currentPosition = shuffledIndices.indexOf(currentIndex)
        if (sourcePosition == -1 || currentPosition == -1) return false

        val destinationPosition = (currentPosition + if (sourcePosition < currentPosition) 0 else 1)
            .coerceAtMost(shuffledIndices.lastIndex)
        if (sourcePosition == destinationPosition) return false

        shuffledIndices.move(sourcePosition, destinationPosition)
        localPlayer.setShuffleOrder(
            DefaultShuffleOrder(
                shuffledIndices.toIntArray(),
                System.currentTimeMillis(),
            ),
        )
        return true
    }

    val destinationIndex = (currentIndex + if (mediaItemIndex < currentIndex) 0 else 1)
        .coerceAtMost(player.mediaItemCount - 1)
    if (mediaItemIndex == destinationIndex) return false
    player.moveMediaItem(mediaItemIndex, destinationIndex)
    return true
}
