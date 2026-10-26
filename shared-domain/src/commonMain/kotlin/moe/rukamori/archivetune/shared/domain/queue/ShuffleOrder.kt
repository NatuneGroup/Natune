package moe.rukamori.archivetune.shared.domain.queue

import kotlin.random.Random

/**
 * Maps between the two index spaces a shuffled queue uses.
 *
 * A timeline index is the order songs were added in. A play position is the order they play in.
 * [playOrder] holds the timeline index for each play position, and the reverse map is derived from it
 * in the constructor, so the two can never describe different queues. Duplicate songs are separate
 * timeline entries, so each occurrence maps to its own position.
 *
 * Every edit returns a new instance. A caller may keep the old one while it rebuilds the timeline.
 */
class ShuffleOrder private constructor(private val playOrder: IntArray) {
    private val playPositionByTimeline = IntArray(playOrder.size).also { reverse ->
        for (position in playOrder.indices) reverse[playOrder[position]] = position
    }

    val size: Int get() = playOrder.size

    /** The timeline index that plays at [playPosition]. */
    fun timelineIndexAt(playPosition: Int): Int {
        require(playPosition in playOrder.indices) { "play position $playPosition is outside 0 until $size" }
        return playOrder[playPosition]
    }

    /** The play position of [timelineIndex]. */
    fun playPositionOf(timelineIndex: Int): Int {
        require(timelineIndex in playOrder.indices) { "timeline index $timelineIndex is outside 0 until $size" }
        return playPositionByTimeline[timelineIndex]
    }

    /** The timeline index that plays after [timelineIndex], or [INDEX_UNSET] at the end. */
    fun nextTimelineIndex(timelineIndex: Int): Int {
        if (timelineIndex !in playOrder.indices) return INDEX_UNSET
        return playOrder.getOrElse(playPositionByTimeline[timelineIndex] + 1) { INDEX_UNSET }
    }

    /** The timeline index that plays before [timelineIndex], or [INDEX_UNSET] at the start. */
    fun previousTimelineIndex(timelineIndex: Int): Int {
        if (timelineIndex !in playOrder.indices) return INDEX_UNSET
        val position = playPositionByTimeline[timelineIndex] - 1
        return if (position >= 0) playOrder[position] else INDEX_UNSET
    }

    /** The first timeline index in play order, or [INDEX_UNSET] for an empty order. */
    fun firstTimelineIndex(): Int = playOrder.firstOrNull() ?: INDEX_UNSET

    /** The last timeline index in play order, or [INDEX_UNSET] for an empty order. */
    fun lastTimelineIndex(): Int = playOrder.lastOrNull() ?: INDEX_UNSET

    /**
     * The play position directly after [timelineIndex], once [skipUpcoming] songs that already play
     * next are passed. Manual "play next" uses this to keep its place behind earlier manual songs.
     */
    fun playPositionAfter(timelineIndex: Int, skipUpcoming: Int = 0): Int =
        (playPositionOf(timelineIndex) + 1 + skipUpcoming.coerceAtLeast(0)).coerceAtMost(size)

    /**
     * Inserts [insertionCount] timeline entries at [insertionIndex], which is Media3's convention:
     * every existing timeline index at or after it shifts up. The new entries play starting at
     * [playPosition], and everything at or after that play position moves back.
     *
     * Passing [size] as the play position appends to the end of play order. The explicit position keeps
     * the placement decision with the caller, who knows whether the entries are manual or radio.
     */
    fun cloneAndInsert(insertionIndex: Int, insertionCount: Int, playPosition: Int): ShuffleOrder {
        require(insertionCount >= 0) { "insertionCount must not be negative" }
        require(insertionIndex in 0..size) { "insertion index $insertionIndex is outside 0..$size" }
        require(playPosition in 0..size) { "play position $playPosition is outside 0..$size" }
        val shift = { timeline: Int -> if (timeline >= insertionIndex) timeline + insertionCount else timeline }
        val out = IntArray(size + insertionCount)
        var write = 0
        for (position in 0 until playPosition) out[write++] = shift(playOrder[position])
        for (offset in 0 until insertionCount) out[write++] = insertionIndex + offset
        for (position in playPosition until size) out[write++] = shift(playOrder[position])
        return ShuffleOrder(out)
    }

    /**
     * Removes the [count] timeline entries starting at [fromIndex]. Survivors above the removed range
     * are renumbered down by [count], so the order never points past the end of a shrinking queue.
     */
    fun cloneAndRemove(fromIndex: Int, count: Int): ShuffleOrder {
        require(count >= 0) { "count must not be negative" }
        require(fromIndex >= 0 && fromIndex + count <= size) { "range $fromIndex+$count is outside 0..$size" }
        val end = fromIndex + count
        val out = IntArray(size - count)
        var write = 0
        for (timeline in playOrder) {
            when {
                timeline < fromIndex -> out[write++] = timeline
                timeline >= end -> out[write++] = timeline - count
            }
        }
        return ShuffleOrder(out)
    }

    /** An empty order. Every entry point stays safe on it. */
    fun cloneAndClear(): ShuffleOrder = empty()

    /** The play order as timeline indices. */
    fun playOrder(): List<Int> = playOrder.toList()

    override fun equals(other: Any?): Boolean =
        other is ShuffleOrder && playOrder.contentEquals(other.playOrder)

    override fun hashCode(): Int = playOrder.contentHashCode()

    override fun toString(): String = "ShuffleOrder${playOrder.toList()}"

    companion object {
        /** Returned where there is no entry to point at, such as past the end of play order. */
        const val INDEX_UNSET = -1

        fun empty(): ShuffleOrder = ShuffleOrder(IntArray(0))

        /** Wraps an explicit play order. Anything other than a permutation of 0 until its size is refused. */
        fun ofPlayOrder(order: List<Int>): ShuffleOrder {
            val values = order.toIntArray()
            val seen = BooleanArray(values.size)
            for (value in values) {
                require(value in values.indices && !seen[value]) { "play order must be a permutation, was $order" }
                seen[value] = true
            }
            return ShuffleOrder(values)
        }

        /**
         * A random permutation of [size] entries, built with the inside-out Fisher-Yates in one pass. If
         * [startIndex] is given, it is moved to the front so the song already playing stays first.
         */
        fun shuffled(size: Int, random: Random = Random.Default, startIndex: Int? = null): ShuffleOrder {
            require(size >= 0) { "size must not be negative" }
            val order = IntArray(size)
            for (i in 0 until size) {
                val swap = random.nextInt(i + 1)
                order[i] = order[swap]
                order[swap] = i
            }
            if (startIndex != null) {
                require(startIndex in 0 until size) { "start index $startIndex is outside 0 until $size" }
                val position = order.indexOf(startIndex)
                val front = order[0]
                order[0] = startIndex
                order[position] = front
            }
            return ShuffleOrder(order)
        }
    }
}

/**
 * Appends the timeline indices [oldCount] until [newCount] to an existing play order, in random order.
 * The songs already in play order keep their places, so a continuation page never reshuffles the queue.
 */
fun appendFiniteShuffleOrder(
    previous: List<Int>,
    oldCount: Int,
    newCount: Int,
    random: Random = Random.Default,
): List<Int> = previous + (oldCount until newCount).shuffled(random)
