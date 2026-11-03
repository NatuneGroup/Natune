package moe.rukamori.archivetune.shared.domain.repository

import kotlinx.coroutines.flow.StateFlow
import moe.rukamori.archivetune.shared.domain.playback.MusicPlayerController
import moe.rukamori.archivetune.shared.domain.queue.FinitePlaylistPhase
import moe.rukamori.archivetune.shared.domain.queue.UserQueuedKind

data class QueueState(
    val revision: Long,
    val entries: List<ContentOccurrence>,
    val currentOccurrenceId: String?,
    /** Occurrence ids in play order; timeline order remains entries. */
    val playOrder: List<String>,
    val phase: FinitePlaylistPhase = FinitePlaylistPhase.UNKNOWN,
    /** False means play order is exactly timeline order; true means [playOrder] is a shuffle of it. */
    val shuffleEnabled: Boolean = false,
    /** Hand-queued kind per occurrence. It decides where the next manual song is placed. */
    val manualKinds: Map<String, UserQueuedKind> = emptyMap(),
    /**
     * A built-in collection queue stays finite: the recommendation tail never seeds on it, so a library
     * list ends where the user sees it end. Loading an explicit radio clears it, as does any queue that
     * did not come from a built-in collection. Checkpoints persist it so a restored list stays finite.
     */
    val staysFinite: Boolean = false,
    /** Paging still owed by the collection this queue came from. Null when nothing more can be loaded. */
    val tail: QueueTail? = null,
)

/**
 * The request and continuation a queue keeps for pages it has not loaded yet. A provider continuation is
 * only valid for the exact [collection] that issued it, so both travel together through checkpoints.
 * [kind] keeps a radio tail from being judged by finite-list completion rules after a restart.
 */
data class QueueTail(
    val collection: ContentRequest,
    val continuation: String?,
    val consumedTokens: Set<String> = emptySet(),
    val kind: QueueCollectionKind = QueueCollectionKind.FINITE,
) {
    init { require(continuation == null || continuation.isNotBlank()) { "continuation must not be blank" } }
}

/** Host facade over the ONE queue owner. Android delegates to MusicService, not a second list. */
interface QueueController {
    val player: MusicPlayerController
    val state: StateFlow<QueueState>
    suspend fun replace(entries: List<ContentOccurrence>, startOccurrenceId: String, playWhenReady: Boolean)

    /** The occurrences actually queued; duplicate prevention can refuse some or all of the batch. */
    suspend fun insert(entries: List<ContentOccurrence>, kind: UserQueuedKind): Int
    suspend fun remove(occurrenceIds: Set<String>)
    suspend fun move(occurrenceId: String, beforeOccurrenceId: String?)
    suspend fun select(occurrenceId: String, positionMs: Long = 0L)
}
