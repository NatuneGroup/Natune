package moe.rukamori.archivetune.shared.data.repository

import moe.rukamori.archivetune.shared.domain.persistence.QueueCheckpointStore
import moe.rukamori.archivetune.shared.domain.queue.UserQueuedKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.ContinuationQueueController
import moe.rukamori.archivetune.shared.domain.repository.QueueLoadRequest
import moe.rukamori.archivetune.shared.domain.repository.QueueEditingCommands
import moe.rukamori.archivetune.shared.domain.repository.TypedQueueOwnerPort

/**
 * Host facade over [TypedQueueOwnerPort] that persists the typed [QueueCheckpointStore] (Room on desktop).
 * It holds no list, metadata, revision or player of its own: every call runs inside the owner's lock.
 *
 * Checkpoint writes follow the store's compare-and-swap rule. A structural change writes a new revision; a
 * position-only tick at an unchanged revision uses [QueueCheckpointStore.updatePosition], because a strictly
 * increasing write would be refused for an equal revision.
 */
class TypedDelegatingQueueController(
    private val owner: TypedQueueOwnerPort,
    private val checkpoints: QueueCheckpointStore,
) : ContinuationQueueController, QueueEditingCommands {
    override val player get() = owner.player
    override val state get() = owner.state

    override suspend fun load(request: QueueLoadRequest) = owner.withQueueAccess { owner.loadCollection(request) }
    override suspend fun replace(
        entries: List<ContentOccurrence>,
        startOccurrenceId: String,
        playWhenReady: Boolean,
        staysFinite: Boolean,
    ) = owner.withQueueAccess { owner.replace(entries, startOccurrenceId, playWhenReady, staysFinite) }
    override suspend fun insert(entries: List<ContentOccurrence>, kind: UserQueuedKind) =
        owner.withQueueAccess { owner.insert(entries, kind) }
    override suspend fun remove(occurrenceIds: Set<String>) = owner.withQueueAccess { owner.remove(occurrenceIds) }
    override suspend fun move(occurrenceId: String, beforeOccurrenceId: String?) =
        owner.withQueueAccess { owner.move(occurrenceId, beforeOccurrenceId) }
    override suspend fun select(occurrenceId: String, positionMs: Long) =
        owner.withQueueAccess { owner.select(occurrenceId, positionMs) }

    /** Shuffle is not a [ContinuationQueueController] command, so the host reaches it here, inside the owner's lock. */
    override suspend fun setShuffle(enabled: Boolean): Boolean = owner.withQueueAccess { owner.setShuffle(enabled) }

    private val edits: QueueEditingCommands
        get() = owner as? QueueEditingCommands ?: error("Queue owner does not support queue editing")

    override val infiniteQueueLoading get() = edits.infiniteQueueLoading
    override val queueSource get() = edits.queueSource
    override suspend fun moveInPlayOrder(occurrenceId: String, beforeOccurrenceId: String?) =
        owner.withQueueAccess { edits.moveInPlayOrder(occurrenceId, beforeOccurrenceId) }
    override suspend fun moveNext(occurrenceId: String) = owner.withQueueAccess { edits.moveNext(occurrenceId) }
    override suspend fun removeUndoable(occurrenceIds: Set<String>): Long? =
        owner.withQueueAccess { edits.removeUndoable(occurrenceIds) }
    override suspend fun undoRemoval(ticket: Long): Boolean = owner.withQueueAccess { edits.undoRemoval(ticket) }
    override suspend fun clearUpcoming(): Long? = owner.withQueueAccess { edits.clearUpcoming() }
    override suspend fun refreshInfiniteQueue() = owner.withQueueAccess { edits.refreshInfiniteQueue() }

    /** "Start radio" on the song playing now. False when it cannot seed a watch-next queue. */
    suspend fun startRadioSeamlessly(): Boolean = owner.withQueueAccess { owner.startRadioSeamlessly() }

    /** "Start radio" on [seed], with the playing-song check inside the owner's lock; see [startRadioOnSeed]. */
    suspend fun startRadio(seed: ContentRef, solo: suspend () -> ContentOccurrence): Boolean =
        owner.startRadioOnSeed(seed, solo)

    override suspend fun checkpoint(): Boolean = owner.withQueueAccess {
        val savedRevision = checkpoints.read()?.queue?.revision
        if (savedRevision != null) {
            val ownerRevision = owner.state.value.revision
            // Nothing was loaded or resumed in this process, so its empty state must not replace the saved queue.
            if (ownerRevision == 0L) return@withQueueAccess false
            // A fresh lineage writes above the saved revision, or the store refuses it as stale.
            if (ownerRevision < savedRevision) owner.raiseRevisionTo(savedRevision + 1)
        }
        val captured = owner.capture()
        if (savedRevision != null && captured.queue.revision == savedRevision) {
            val current = captured.queue.currentOccurrenceId ?: return@withQueueAccess false
            checkpoints.updatePosition(savedRevision, current, captured.positionMs)
        } else {
            checkpoints.write(captured, savedRevision)
        }
    }

    override suspend fun resume(): Boolean = owner.withQueueAccess {
        val saved = checkpoints.read() ?: return@withQueueAccess false
        owner.restore(saved)
        true
    }
}
