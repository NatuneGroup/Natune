package moe.rukamori.archivetune.playback

import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/** ExoPlayer stays idle after a source error; seeking alone cannot load the new item. */
@OptIn(UnstableApi::class)
internal class ErrorRecoveringPlayer(
    private val target: Player,
    private val offlineSongs: AutoOfflineSongs = AutoOfflineSongs(),
    private val onRecover: () -> Unit,
) : ForwardingPlayer(target) {
    private var navigating = false

    override fun getNextMediaItemIndex(): Int = playableIndex(target.nextMediaItemIndex, forward = true)

    override fun getPreviousMediaItemIndex(): Int = playableIndex(target.previousMediaItemIndex, forward = false)

    private fun playableIndex(start: Int, forward: Boolean): Int {
        if (!offlineSongs.isOffline()) return start
        val timeline = target.currentTimeline
        val repeatMode = if (target.repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_OFF else target.repeatMode
        return offlineNavigationIndex(start, target.mediaItemCount, { index ->
            if (index >= timeline.windowCount) C.INDEX_UNSET
            else if (forward) timeline.getNextWindowIndex(index, repeatMode, target.shuffleModeEnabled)
            else timeline.getPreviousWindowIndex(index, repeatMode, target.shuffleModeEnabled)
        }) { index -> offlineSongs.canPlayId(target.getMediaItemAt(index).mediaId) }
    }

    private fun snapshot(): List<MediaItem> = List(target.mediaItemCount) { target.getMediaItemAt(it) }

    private fun unchanged(items: List<MediaItem>): Boolean =
        target.mediaItemCount == items.size && items.indices.all { target.getMediaItemAt(it) == items[it] }

    private fun navigate(index: Int, positionMs: Long, seek: () -> Unit) {
        if (navigating) return
        val recover = target.playbackState == Player.STATE_IDLE && target.playerError != null
        if (!recover && !offlineSongs.isOffline()) { seek(); return }
        val items = snapshot()
        if (index !in items.indices) {
            if (!recover) seek()
            return
        }
        if (offlineSongs.selectItems(items, index, positionMs) == null) return
        navigating = true
        try {
            if (recover) onRecover()
            // A callback may change the queue or revoke a source/download. Never apply a stale index.
            if (!unchanged(items) || offlineSongs.isOffline() && !offlineSongs.canPlayId(items[index].mediaId)) return
            val selection = retainOfflineQueue(target, offlineSongs, index, positionMs) ?: return
            if (offlineSongs.isOffline()) {
                target.seekTo(selection.startIndex, positionMs)
            } else {
                seek()
            }
            prepareAfterError(recover, selection.items, selection.startIndex)
        } finally {
            navigating = false
        }
    }

    private fun prepareAfterError(recover: Boolean, items: List<MediaItem>, index: Int) {
        if (recover && target.playbackState == Player.STATE_IDLE && target.currentMediaItemIndex == index &&
            target.currentMediaItem == items.getOrNull(index) &&
            (!offlineSongs.isOffline() || offlineSongs.canPlayId(items[index].mediaId))
        ) target.prepare()
    }

    override fun seekToNext() = navigate(nextMediaItemIndex, C.TIME_UNSET) { target.seekToNext() }

    override fun seekToNextMediaItem() = navigate(nextMediaItemIndex, C.TIME_UNSET) { target.seekToNextMediaItem() }

    override fun seekToPrevious() {
        val previous = target.previousMediaItemIndex
        val restartCurrent = (previous == C.INDEX_UNSET ||
            target.isCurrentMediaItemSeekable && target.currentPosition > target.maxSeekToPreviousPosition) &&
            !(target.isCurrentMediaItemLive && !target.isCurrentMediaItemSeekable)
        val index = if (restartCurrent && (!offlineSongs.isOffline() ||
                target.currentMediaItem?.let { offlineSongs.canPlayId(it.mediaId) } == true)) {
            target.currentMediaItemIndex
        } else previousMediaItemIndex
        navigate(index, C.TIME_UNSET) { target.seekToPrevious() }
    }

    override fun seekToPreviousMediaItem() = navigate(previousMediaItemIndex, C.TIME_UNSET) { target.seekToPreviousMediaItem() }

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) = navigate(mediaItemIndex, positionMs) { target.seekTo(mediaItemIndex, positionMs) }

    override fun seekTo(positionMs: Long) = navigate(target.currentMediaItemIndex, positionMs) { target.seekTo(positionMs) }

    override fun seekToDefaultPosition() = navigate(target.currentMediaItemIndex, C.TIME_UNSET) { target.seekToDefaultPosition() }

    override fun seekToDefaultPosition(mediaItemIndex: Int) = navigate(mediaItemIndex, C.TIME_UNSET) { target.seekToDefaultPosition(mediaItemIndex) }

    private fun replaceItems(items: List<MediaItem>, index: Int, positionMs: Long, allowFirstPlayable: Boolean = false, replace: () -> Unit) {
        if (navigating) return
        val recover = target.playbackState == Player.STATE_IDLE && target.playerError != null
        if (!recover && !offlineSongs.isOffline()) { replace(); return }
        val safeIndex = if (index == C.INDEX_UNSET) 0 else index
        val selection = if (items.isEmpty()) null else offlineSongs.selectItems(items, safeIndex, positionMs,
            allowFirstPlayable = allowFirstPlayable || index == C.INDEX_UNSET) ?: return
        val original = snapshot()
        navigating = true
        try {
            if (recover) onRecover()
            if (!unchanged(original)) return
            if (selection != null && offlineSongs.isOffline()) {
                if (!offlineSongs.canPlayId(selection.items[selection.startIndex].mediaId)) return
                target.setMediaItems(selection.items, selection.startIndex, selection.startPositionMs)
            } else replace()
            if (selection != null) prepareAfterError(recover, selection.items, selection.startIndex)
        } finally {
            navigating = false
        }
    }

    override fun setMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long) =
        replaceItems(mediaItems, startIndex, startPositionMs) { target.setMediaItems(mediaItems, startIndex, startPositionMs) }

    override fun setMediaItems(mediaItems: List<MediaItem>) =
        replaceItems(mediaItems, 0, C.TIME_UNSET, allowFirstPlayable = true) { target.setMediaItems(mediaItems) }

    override fun setMediaItems(mediaItems: List<MediaItem>, resetPosition: Boolean) =
        replaceItems(mediaItems, if (resetPosition) 0 else target.currentMediaItemIndex.coerceAtLeast(0),
            if (resetPosition) C.TIME_UNSET else target.currentPosition, allowFirstPlayable = resetPosition) { target.setMediaItems(mediaItems, resetPosition) }

    override fun setMediaItem(mediaItem: MediaItem) = replaceItems(listOf(mediaItem), 0, C.TIME_UNSET) { target.setMediaItem(mediaItem) }

    override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) =
        replaceItems(listOf(mediaItem), 0, startPositionMs) { target.setMediaItem(mediaItem, startPositionMs) }

    override fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean) =
        replaceItems(listOf(mediaItem), 0, if (resetPosition) C.TIME_UNSET else target.currentPosition) { target.setMediaItem(mediaItem, resetPosition) }
}

/** Remove unavailable ranges from the tail so Media3 retains window identities and shuffle order. */
internal fun retainOfflineQueue(
    player: Player,
    songs: AutoOfflineSongs,
    startIndex: Int = player.currentMediaItemIndex,
    positionMs: Long = player.currentPosition,
): AutoOfflineSongs.Selection<MediaItem>? {
    val original = List(player.mediaItemCount) { player.getMediaItemAt(it) }
    val selection = songs.selectItems(original, startIndex, positionMs) ?: return null
    if (selection.items.size == original.size) return selection
    val retained = selection.items.toHashSet()
    val expected = original.toMutableList()
    fun unchanged(): Boolean = player.mediaItemCount == expected.size && expected.indices.all { player.getMediaItemAt(it) == expected[it] }
    var end = original.size
    while (end > 0) {
        if (original[end - 1] in retained) { end--; continue }
        var start = end - 1
        while (start > 0 && original[start - 1] !in retained) start--
        if (!unchanged()) return null
        player.removeMediaItems(start, end)
        expected.subList(start, end).clear()
        if (!unchanged()) return null
        end = start
    }
    return selection
}

/** Repeat/shuffle navigation is supplied by the live timeline; revisiting an index terminates the scan. */
internal fun offlineNavigationIndex(start: Int, itemCount: Int, next: (Int) -> Int, canPlay: (Int) -> Boolean): Int {
    var index = start
    val visited = HashSet<Int>()
    while (index in 0 until itemCount && visited.add(index)) {
        if (canPlay(index)) return index
        index = next(index)
    }
    return C.INDEX_UNSET
}
