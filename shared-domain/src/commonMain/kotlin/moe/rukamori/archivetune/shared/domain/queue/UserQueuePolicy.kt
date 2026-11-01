package moe.rukamori.archivetune.shared.domain.queue

import moe.rukamori.archivetune.shared.domain.repository.QueueState

/**
 * The manual action that put a song in the queue. Hand-queued songs take priority over the radio or
 * autoplay tail, so the kind of each one decides where the next manual song goes.
 */
enum class UserQueuedKind {
    PLAY_NEXT,
    ADD_TO_QUEUE,
}

/**
 * How a manual insertion is ordered against the play order.
 *
 * [Timeline] means shuffle is off, so play order is timeline order. [AfterCurrent] means shuffle is on
 * and the songs go behind the current song and [skipUpcoming] hand-queued songs. [RandomPosition] means
 * shuffle is on and the songs are appended without manual order, so the shuffle places them at random.
 */
sealed interface UserQueuePlayOrder {
    data object Timeline : UserQueuePlayOrder

    data class AfterCurrent(val skipUpcoming: Int) : UserQueuePlayOrder

    data object RandomPosition : UserQueuePlayOrder
}

/** Where a manual insertion lands in timeline order, and how the play order places it. */
data class UserQueuePlacement(
    val timelineIndex: Int,
    val playOrder: UserQueuePlayOrder,
)

object UserQueuePolicy {
    /**
     * The kind of each hand-queued song right after the current one, in play order — the [upcoming]
     * list [skipCount] reads. The block ends at the first song nobody queued by hand.
     */
    fun upcomingKinds(queue: QueueState): List<UserQueuedKind> {
        val current = queue.currentOccurrenceId ?: return emptyList()
        val position = queue.playOrder.indexOf(current)
        if (position < 0) return emptyList()
        return queue.playOrder.drop(position + 1)
            .takeWhile { queue.manualKinds.containsKey(it) }
            .map { queue.manualKinds.getValue(it) }
    }

    /**
     * How many of the upcoming songs, in play order, a new [adding] song goes after.
     *
     * [upcoming] holds the kind of each hand-queued song right after the current one. It ends at the
     * first song nobody queued by hand, so the radio or autoplay tail is never skipped. The block reads
     * `[Play next][Add to queue]`: Play next lines up behind earlier Play next songs but stays ahead of
     * Add to queue songs, and Add to queue goes after the whole block.
     */
    fun skipCount(upcoming: List<UserQueuedKind>, adding: UserQueuedKind): Int =
        when (adding) {
            UserQueuedKind.PLAY_NEXT -> upcoming.takeWhile { it == UserQueuedKind.PLAY_NEXT }.size
            UserQueuedKind.ADD_TO_QUEUE -> upcoming.size
        }

    /**
     * Where [count] songs of [kind] go, or null when there is nothing to insert.
     *
     * With [keepInOrder] off, Add to queue always appends to the end of the timeline. Otherwise the songs
     * go directly after the current song, behind the hand-queued block. With no current song, or an empty
     * queue, the songs go to the end.
     *
     * [upcoming] is read only when it can change the answer, so callers do not walk the queue needlessly.
     */
    fun placement(
        kind: UserQueuedKind,
        count: Int,
        keepInOrder: Boolean,
        itemCount: Int,
        currentIndex: Int?,
        shuffleEnabled: Boolean,
        upcoming: () -> List<UserQueuedKind>,
    ): UserQueuePlacement? {
        if (count <= 0) return null
        if (!keepInOrder && kind == UserQueuedKind.ADD_TO_QUEUE) {
            val playOrder = if (shuffleEnabled) UserQueuePlayOrder.RandomPosition else UserQueuePlayOrder.Timeline
            return UserQueuePlacement(itemCount, playOrder)
        }
        val current = if (itemCount > 0) currentIndex else null
        val shuffled = shuffleEnabled && current != null
        val skip = if (keepInOrder && current != null) skipCount(upcoming(), kind) else 0
        val timelineIndex = when {
            current == null -> itemCount
            shuffled && kind == UserQueuedKind.ADD_TO_QUEUE -> itemCount
            shuffled -> current + 1
            else -> (current + 1 + skip).coerceAtMost(itemCount)
        }
        val playOrder = when {
            shuffled -> UserQueuePlayOrder.AfterCurrent(skip)
            shuffleEnabled -> UserQueuePlayOrder.RandomPosition
            else -> UserQueuePlayOrder.Timeline
        }
        return UserQueuePlacement(timelineIndex, playOrder)
    }
}
