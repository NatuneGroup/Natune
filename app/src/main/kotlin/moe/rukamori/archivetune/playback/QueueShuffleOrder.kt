package moe.rukamori.archivetune.playback

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder

internal fun Player.currentShuffleIndices(): List<Int> = buildList {
    val timeline = currentTimeline
    var index = timeline.getFirstWindowIndex(true)
    while (index != C.INDEX_UNSET && size < mediaItemCount) {
        add(index)
        index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, true)
    }
}

internal fun Player.buildPlayNextShuffleOrder(
    currentIndex: Int,
    insertionIndex: Int,
    insertionCount: Int,
    // How many of the upcoming (shuffled) items the insertion goes after. 0 = straight after the
    // current song ("Play next"); >0 keeps earlier user-added songs ahead of it (#172).
    skipUpcoming: Int = 0,
): DefaultShuffleOrder? {
    if (insertionCount <= 0 || currentTimeline.isEmpty) return null

    fun adjustedIndex(index: Int): Int =
        if (index >= insertionIndex) {
            index + insertionCount
        } else {
            index
        }

    val timeline = currentTimeline
    val previousIndices = ArrayDeque<Int>()
    var traversalIndex = currentIndex
    while (true) {
        traversalIndex = timeline.getPreviousWindowIndex(traversalIndex, Player.REPEAT_MODE_OFF, true)
        if (traversalIndex == C.INDEX_UNSET) {
            break
        }
        previousIndices.addFirst(adjustedIndex(traversalIndex))
    }

    val nextIndices = mutableListOf<Int>()
    traversalIndex = currentIndex
    while (true) {
        traversalIndex = timeline.getNextWindowIndex(traversalIndex, Player.REPEAT_MODE_OFF, true)
        if (traversalIndex == C.INDEX_UNSET) {
            break
        }
        nextIndices += adjustedIndex(traversalIndex)
    }

    val skip = skipUpcoming.coerceIn(0, nextIndices.size)
    val shuffledIndices =
        buildList(mediaItemCount + insertionCount) {
            addAll(previousIndices)
            add(adjustedIndex(currentIndex).takeIf { currentIndex >= insertionIndex } ?: currentIndex)
            addAll(nextIndices.subList(0, skip))
            repeat(insertionCount) { offset ->
                add(insertionIndex + offset)
            }
            addAll(nextIndices.subList(skip, nextIndices.size))
        }.toIntArray()

    return DefaultShuffleOrder(shuffledIndices, System.currentTimeMillis())
}
