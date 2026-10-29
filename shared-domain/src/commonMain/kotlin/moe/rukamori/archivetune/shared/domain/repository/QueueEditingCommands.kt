package moe.rukamori.archivetune.shared.domain.repository

import kotlinx.coroutines.flow.StateFlow

/** Additive commands on the existing queue owner. Undo tickets are memory-only, never a persistence format. */
interface QueueEditingCommands {
    val infiniteQueueLoading: StateFlow<Boolean>
    /** Runtime source metadata only; no change to saved queue documents or legacy readers. */
    val queueSource: StateFlow<ContentRequest?>
    suspend fun setShuffle(enabled: Boolean): Boolean
    suspend fun moveInPlayOrder(occurrenceId: String, beforeOccurrenceId: String?)
    suspend fun moveNext(occurrenceId: String)
    suspend fun removeUndoable(occurrenceIds: Set<String>): Long?
    suspend fun undoRemoval(ticket: Long): Boolean
    /** Caller first persists infinity off. Keeps the playing occurrence and detaches any owed source pages. */
    suspend fun clearUpcoming(): Long?
    suspend fun refreshInfiniteQueue()
}
