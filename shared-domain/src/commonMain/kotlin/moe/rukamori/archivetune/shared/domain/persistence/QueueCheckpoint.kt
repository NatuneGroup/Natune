package moe.rukamori.archivetune.shared.domain.persistence

import moe.rukamori.archivetune.shared.domain.repository.QueueState

/**
 * Captured by the queue owner only when stable. Position belongs to this exact queue revision.
 * [savedAtEpochMs] is the wall-clock time of the capture; checkpoints written before it existed
 * carry null, which restore treats as "too old to trust" under [PositionRestorePolicy].
 */
data class QueueCheckpoint(val queue: QueueState, val positionMs: Long, val savedAtEpochMs: Long? = null) {
    init {
        require(queue.revision >= 0 && positionMs >= 0)
        val ids = queue.entries.map { it.occurrenceId }
        val idSet = ids.toSet()
        require(ids.all(String::isNotBlank) && ids.size == idSet.size)
        require(queue.playOrder.size == ids.size && queue.playOrder.toSet() == idSet)
        require(queue.currentOccurrenceId == null || queue.currentOccurrenceId in idSet)
        // Without shuffle the play order is exactly the timeline; manual kinds may only describe queued songs.
        require(queue.shuffleEnabled || queue.playOrder == ids)
        require(queue.manualKinds.keys.all { it in idSet })
    }
}

interface QueueCheckpointStore {
    suspend fun read(): QueueCheckpoint?
    /**
     * The checkpoint a restore applies. A store may fill in details the capture lacked here; [read] stays the cheap
     * form because every checkpoint write calls it only to compare revisions.
     */
    suspend fun readForRestore(): QueueCheckpoint? = read()
    /** Compare-and-swap. Null expectedRevision means no checkpoint may exist. */
    suspend fun write(checkpoint: QueueCheckpoint, expectedRevision: Long?): Boolean
    /** A stale position tick must not attach itself to another queue or occurrence. */
    suspend fun updatePosition(expectedRevision: Long, occurrenceId: String, positionMs: Long): Boolean
}
