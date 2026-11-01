package moe.rukamori.archivetune.shared.domain.queue

import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.QueueState

/**
 * The duplicate rules of the queue's add path behind the `preventDuplicateTracksInQueue` setting:
 * an inserted batch keeps one occurrence per media reference, a copy of the song already playing is
 * dropped, and every other queued occurrence of an incoming media reference is evicted first, so a
 * re-added song lands at its new position instead of appearing twice. On an empty queue the same
 * rules still apply: the batch dedupes itself and the single surviving copy becomes current.
 *
 * Identity is the full provider-scoped reference rather than the bare media id: two providers may
 * issue the same track id, and their occurrences are different queue entries with different locators.
 */
object QueueDedupePolicy {
    /** The occurrences of [incoming] worth inserting: first per media reference, never the current song. */
    fun incoming(queue: QueueState, incoming: List<ContentOccurrence>): List<ContentOccurrence> {
        val current = currentRef(queue)
        return incoming.distinctBy { it.item.ref }.filterNot { it.item.ref == current }
    }

    /** Occurrence ids an insert of [incoming] evicts first. The current occurrence always survives. */
    fun evictable(queue: QueueState, incoming: List<ContentOccurrence>): Set<String> {
        val refs = incoming.mapTo(HashSet()) { it.item.ref }
        return queue.entries.mapNotNullTo(LinkedHashSet<String>()) { entry ->
            entry.occurrenceId.takeIf {
                entry.occurrenceId != queue.currentOccurrenceId && entry.item.ref in refs
            }
        }
    }

    /** [queue] without the evicted occurrences, from timeline, play order and manual kinds alike. */
    fun evict(queue: QueueState, evicted: Set<String>): QueueState =
        queue.copy(
            entries = queue.entries.filterNot { it.occurrenceId in evicted },
            playOrder = queue.playOrder.filterNot { it in evicted },
            manualKinds = queue.manualKinds.filterKeys { it !in evicted },
        )

    /** The media reference of the occurrence marked current, or null on an empty or headless queue. */
    fun currentRef(queue: QueueState) =
        queue.currentOccurrenceId
            ?.let { id -> queue.entries.firstOrNull { it.occurrenceId == id } }
            ?.item?.ref
}
