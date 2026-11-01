package moe.rukamori.archivetune.shared.domain.queue

import kotlin.random.Random
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.QueueCollectionKind
import moe.rukamori.archivetune.shared.domain.repository.QueueState

/** Existing occurrence identities and the playing occurrence are never rewritten by queue editing. */
object QueueEditPolicy {
    fun moveInPlayOrder(queue: QueueState, occurrenceId: String, beforeOccurrenceId: String?): QueueState {
        require(occurrenceId in queue.playOrder && occurrenceId != beforeOccurrenceId)
        val order = queue.playOrder.toMutableList().apply { remove(occurrenceId) }
        val index = if (beforeOccurrenceId == null) order.size else order.indexOf(beforeOccurrenceId)
        require(index >= 0) { "Anchor occurrence is not in the queue" }
        order.add(index, occurrenceId)
        if (order == queue.playOrder) return queue
        val byId = queue.entries.associateBy { it.occurrenceId }
        return queue.copy(
            revision = queue.revision + 1,
            entries = if (queue.shuffleEnabled) queue.entries else order.map { byId.getValue(it) },
            playOrder = order,
        )
    }

    fun moveNext(queue: QueueState, occurrenceId: String): QueueState {
        val current = queue.currentOccurrenceId ?: return queue
        if (occurrenceId == current) return queue
        val order = queue.playOrder.filterNot { it == occurrenceId }
        return moveInPlayOrder(queue, occurrenceId, order.getOrNull(order.indexOf(current) + 1))
    }

    /** The retained timeline API is distinct from the queue screen's play-order move while shuffled. */
    fun moveInTimeline(queue: QueueState, occurrenceId: String, beforeOccurrenceId: String?): QueueState {
        if (!queue.shuffleEnabled) return moveInPlayOrder(queue, occurrenceId, beforeOccurrenceId)
        val timeline = queue.copy(shuffleEnabled = false, playOrder = queue.entries.map { it.occurrenceId })
        val moved = moveInPlayOrder(timeline, occurrenceId, beforeOccurrenceId)
        return queue.copy(revision = moved.revision, entries = moved.entries)
    }

    /** Local shuffle of the loaded radio queue is allowed, but incomplete finite membership is never shuffled. */
    fun shuffle(queue: QueueState, enabled: Boolean, random: Random): QueueState? {
        if (queue.shuffleEnabled == enabled) return queue
        if (enabled && queue.tail != null && queue.tail.kind != QueueCollectionKind.RADIO) return null
        val timeline = queue.entries.map { it.occurrenceId }
        val order = if (enabled) timeline.shuffled(random).toMutableList().apply {
            queue.currentOccurrenceId?.let { remove(it); add(0, it) }
        } else timeline
        return queue.copy(revision = queue.revision + 1, playOrder = order, shuffleEnabled = enabled)
    }

    fun requireUnique(entries: List<ContentOccurrence>) {
        require(entries.mapTo(HashSet()) { it.occurrenceId }.size == entries.size) {
            "Queue occurrence ids must be unique"
        }
    }

    fun requireNewOccurrences(existing: List<ContentOccurrence>, incoming: List<ContentOccurrence>) {
        requireUnique(incoming)
        val known = existing.mapTo(HashSet()) { it.occurrenceId }
        require(incoming.none { it.occurrenceId in known }) { "Occurrence is already queued" }
    }
}
