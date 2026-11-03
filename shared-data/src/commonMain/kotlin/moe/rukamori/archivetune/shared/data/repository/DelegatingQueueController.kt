package moe.rukamori.archivetune.shared.data.repository

import moe.rukamori.archivetune.shared.domain.repository.*
import moe.rukamori.archivetune.shared.domain.queue.UserQueuedKind

/** No list, metadata cache, playback clock, revision generator or StateFlow of its own. */
class DelegatingQueueController(
    private val owner: QueueOwnerPort,
    private val checkpoints: LosslessQueueCheckpointStore,
) : ContinuationQueueController {
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

    override suspend fun checkpoint(): Boolean = owner.withQueueAccess {
        val old = checkpoints.read()
        val captured = owner.capture()
        check(captured.revision == state.value.revision) { "Queue capture was not from the current revision" }
        checkpoints.write(captured, old?.revision)
    }
    override suspend fun resume(): Boolean = owner.withQueueAccess {
        val saved = checkpoints.read() ?: return@withQueueAccess false
        owner.restore(saved)
        true
    }
}
