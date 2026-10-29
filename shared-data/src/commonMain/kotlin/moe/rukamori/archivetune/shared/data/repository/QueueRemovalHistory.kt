package moe.rukamori.archivetune.shared.data.repository

import moe.rukamori.archivetune.shared.domain.repository.QueueState

/** One snackbar's undo. Queue replacement invalidates it; intervening additions and playback remain authoritative. */
internal class QueueRemovalHistory {
    private var sequence = 0L
    private var pending: Removal? = null

    fun remember(queue: QueueState, ids: Set<String>): Long? {
        val removed = ids.intersect(queue.entries.mapTo(HashSet()) { it.occurrenceId })
        if (removed.isEmpty()) return null
        val ticket = ++sequence
        pending = Removal(ticket, queue, removed)
        return ticket
    }

    fun reset() {
        sequence++
        pending = null
    }

    fun restore(ticket: Long, current: QueueState): QueueState? {
        val removal = pending?.takeIf { it.ticket == ticket } ?: return null
        pending = null
        val original = removal.queue
        val byId = (original.entries + current.entries).associateBy { it.occurrenceId }
        val entries = merge(
            current.entries.map { it.occurrenceId },
            original.entries.map { it.occurrenceId },
            removal.ids,
        ).map { byId.getValue(it) }
        val order = if (current.shuffleEnabled) {
            merge(current.playOrder, original.playOrder, removal.ids)
        } else {
            entries.map { it.occurrenceId }
        }
        return current.copy(
            revision = current.revision + 1,
            entries = entries,
            playOrder = order,
            currentOccurrenceId = current.currentOccurrenceId ?: order.firstOrNull(),
            manualKinds = original.manualKinds.filterKeys { it in removal.ids } + current.manualKinds,
        )
    }

    /** Reinsert beside surviving original neighbours, not a stale numeric index in a possibly edited queue. */
    private fun merge(current: List<String>, original: List<String>, removed: Set<String>): List<String> {
        val result = current.toMutableList()
        val known = current.toHashSet()
        for (index in original.indices.reversed()) {
            val id = original[index]
            if (id !in removed || !known.add(id)) continue
            val next = original.drop(index + 1).firstOrNull { it in known }
            val previous = original.take(index).lastOrNull { it in known }
            val position = when {
                next != null -> result.indexOf(next)
                previous != null -> result.indexOf(previous) + 1
                else -> result.size
            }
            result.add(position, id)
        }
        return result
    }

    private data class Removal(val ticket: Long, val queue: QueueState, val ids: Set<String>)
}
