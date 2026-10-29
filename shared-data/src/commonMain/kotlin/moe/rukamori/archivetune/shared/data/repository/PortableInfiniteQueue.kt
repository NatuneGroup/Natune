package moe.rukamori.archivetune.shared.data.repository

import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.shared.domain.queue.FinitePlaylistPhase
import moe.rukamori.archivetune.shared.domain.queue.InfiniteQueueLoadGate
import moe.rukamori.archivetune.shared.domain.queue.InfiniteQueuePolicy
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.QueueCollectionKind
import moe.rukamori.archivetune.shared.domain.repository.QueueState
import moe.rukamori.archivetune.shared.domain.repository.QueueTail
import moe.rukamori.archivetune.shared.domain.repository.radioQueueRequest

internal fun queueMonotonicClock(): () -> Long {
    val origin = TimeSource.Monotonic.markNow()
    return { origin.elapsedNow().inWholeMilliseconds }
}

/** Recommendation work for PortableQueueOwner, always committed under that owner's existing queue lock. */
internal class PortableInfiniteQueue(
    private val scope: CoroutineScope,
    private val access: suspend (suspend () -> Unit) -> Unit,
    private val state: StateFlow<QueueState>,
    private val commit: (QueueState) -> Unit,
    private val content: ContentRepositoryRouter,
    private val filter: (List<ContentOccurrence>) -> List<ContentOccurrence>,
    private val enabled: () -> Boolean,
    private val repeatOff: () -> Boolean,
    private val nowMs: () -> Long,
    private val report: (Throwable) -> Unit,
) {
    private val gate = InfiniteQueueLoadGate()
    private var job: Job? = null
    private var generation = 0L
    private var knownStaticQueue = false
    private var radioLineage = false
    val loading = MutableStateFlow(false)

    fun reset(staticQueue: Boolean = false, radio: Boolean = false) {
        cancel(newQueue = true)
        knownStaticQueue = staticQueue
        radioLineage = radio
    }

    fun cancel(newQueue: Boolean = false) {
        generation++
        gate.invalidate(newQueue)
        job?.cancel()
        job = null
        loading.value = false
    }

    /** Playback and preference events prefetch, never a position-polling or unconditional retry loop. */
    fun consider() {
        if (!enabled() || !repeatOff()) {
            cancel()
            return
        }
        if (job?.isActive == true || !eligible(state.value)) return
        val epoch = generation
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                access { replenish() }
            } finally {
                if (epoch == generation) job = null
            }
        }.also { it.start() }
    }

    private fun eligible(queue: QueueState): Boolean {
        val radioTail = queue.tail?.kind == QueueCollectionKind.RADIO
        if (queue.tail != null && !radioTail) return false
        if (!radioTail && queue.phase != FinitePlaylistPhase.EXHAUSTED && !knownStaticQueue && !radioLineage) return false
        val current = queue.playOrder.indexOf(queue.currentOccurrenceId)
        return current >= 0 && InfiniteQueuePolicy.shouldAppend(
            enabled(), repeatOff(), queue.playOrder.size - current, radioTail && queue.tail?.continuation != null,
        )
    }

    /** Called inside the queue lock, including end-of-track fallback when a prefetch did not arrive in time. */
    suspend fun replenish() {
        val queue = state.value
        if (!eligible(queue)) return
        val current = queue.entries.firstOrNull { it.occurrenceId == queue.currentOccurrenceId } ?: return
        val tail = queue.tail?.takeIf { it.kind == QueueCollectionKind.RADIO }
        val continuation = tail?.continuation != null
        if (!continuation && (current.item.ref.provider != ContentProvider.YOUTUBE.id ||
                current.item.ref.kind != ContentKind.SONG)
        ) return
        val ticket = gate.begin(
            enabled(), repeatOff(), queue.playOrder.size - queue.playOrder.indexOf(queue.currentOccurrenceId),
            continuation, nowMs(),
        ) ?: return
        loading.value = true
        var added = 0
        try {
            withTimeoutOrNull(InfiniteQueuePolicy.REQUEST_TIMEOUT_MS) {
                val collection = if (continuation) checkNotNull(tail).collection else radioQueueRequest(current.item.ref)
                val consumed = if (continuation) checkNotNull(tail).consumedTokens.toMutableSet() else mutableSetOf()
                var token = if (continuation) tail?.continuation else null
                var fresh: List<ContentOccurrence> = emptyList()
                var pages = 0
                do {
                    if (!gate.isCurrent(ticket) || !enabled() || !repeatOff()) return@withTimeoutOrNull
                    if (token != null && !consumed.add(token)) error("Radio continuation repeated")
                    val page = content.page(collection, token)
                    val knownOccurrences = queue.entries.mapTo(HashSet()) { it.occurrenceId }
                    fresh = InfiniteQueuePolicy.fresh(filter(page.items), queue.entries.map { it.item.ref }) {
                        it.item.ref
                    }.filter { knownOccurrences.add(it.occurrenceId) }
                    token = page.continuation
                    require(token == null || (token.isNotBlank() && token !in consumed)) {
                        "Radio continuation repeated"
                    }
                    pages++
                } while (fresh.isEmpty() && token != null && pages < InfiniteQueuePolicy.MAX_BOOTSTRAP_PAGES)

                // Settings can change while IO suspends, even though structural queue edits share this lock.
                if (!gate.isCurrent(ticket) || !enabled() || !repeatOff()) return@withTimeoutOrNull
                if (fresh.isEmpty() && token == null && !continuation) return@withTimeoutOrNull
                commit(queue.copy(
                    revision = queue.revision + 1,
                    entries = queue.entries + fresh,
                    playOrder = queue.playOrder + fresh.map { it.occurrenceId },
                    phase = FinitePlaylistPhase.UNKNOWN,
                    // The existing nullable-token descriptor preserves proven radio lineage across checkpoints.
                    tail = QueueTail(collection, token, consumed, QueueCollectionKind.RADIO),
                ))
                radioLineage = true
                added = fresh.size
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            report(failure)
        } finally {
            if (gate.finish(ticket, added, nowMs())) loading.value = false
        }
    }
}
