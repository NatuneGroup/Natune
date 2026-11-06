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
import moe.rukamori.archivetune.shared.data.recommend.QueueTailRecommendation
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

/**
 * Recommendation work for PortableQueueOwner, always committed under that owner's existing queue lock. Watchers
 * and playback snapshots reach the bookkeeping through [decide], so it never races an in-flight append.
 */
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
    /** Absent keeps the provider-order tail, which is what existing hosts and their fixtures expect. */
    private val recommendation: QueueTailRecommendation? = null,
) {
    private val gate = InfiniteQueueLoadGate()
    private var job: Job? = null
    private var knownStaticQueue = false
    private var radioLineage = false

    /**
     * Occurrence ids the planner appended, so the next page accounts only for the automatic tail still ahead of the
     * listener. Manual and already-played entries never consume the automatic budget.
     */
    private val automaticOccurrences = HashSet<String>()
    val loading = MutableStateFlow(false)

    /**
     * Runs [block] under the owner's queue lock. Watchers fire outside a queue command, so they decide here; the
     * bookkeeping below then never races [replenish]'s gate and lineage writes.
     */
    suspend fun decide(block: () -> Unit) = access { block() }

    fun reset(staticQueue: Boolean = false, radio: Boolean = false) {
        cancel(newQueue = true)
        knownStaticQueue = staticQueue
        radioLineage = radio
        // A restored checkpoint has no persisted recommendation origin, so it can never be guessed back into the count.
        automaticOccurrences.clear()
    }

    /** Assumes the owner's queue lock is held, like every other queue command and [decide]. */
    fun cancel(newQueue: Boolean = false) {
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
        // LAZY so `job` is published before the queued access starts; the started fetch waits for the same lock.
        val next = scope.launch(start = CoroutineStart.LAZY) { access { replenish() } }
        job = next
        next.start()
    }

    private fun eligible(queue: QueueState): Boolean {
        // A built-in collection queue never grows a recommendation tail; an explicit radio load clears the flag.
        if (queue.staysFinite) return false
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
                val planner = recommendation
                val planned =
                    if (planner != null && fresh.isNotEmpty()) {
                        pruneAutomaticOccurrences(queue)
                        val plan =
                            planner.planTail(
                                candidates = fresh,
                                queue = queue,
                                pendingAutomaticIds = automaticOccurrences.toSet(),
                            )
                        // The hide/block readers can move while scoring suspends; a structural edit cannot.
                        filter(plan.items)
                    } else {
                        null
                    }
                if (!gate.isCurrent(ticket) || !enabled() || !repeatOff()) return@withTimeoutOrNull
                val appended = planned ?: fresh
                // An empty plan is normal: keep the page's continuation so the next attempt can leave a rejected page,
                // and finish with `added = 0` so the existing backoff applies instead of retrying immediately.
                if (appended.isEmpty() && token == null && !continuation) return@withTimeoutOrNull
                commit(queue.copy(
                    revision = queue.revision + 1,
                    entries = queue.entries + appended,
                    playOrder = queue.playOrder + appended.map { it.occurrenceId },
                    phase = FinitePlaylistPhase.UNKNOWN,
                    // The existing nullable-token descriptor preserves proven radio lineage across checkpoints.
                    tail = QueueTail(collection, token, consumed, QueueCollectionKind.RADIO),
                ))
                if (planned != null) automaticOccurrences.addAll(appended.map { it.occurrenceId })
                radioLineage = true
                added = appended.size
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            report(failure)
        } finally {
            if (gate.finish(ticket, added, nowMs())) loading.value = false
        }
    }

    /**
     * Keeps the in-memory marker bounded to ids that can still consume this queue's automatic budget. Consumed,
     * removed and hand-queued ids can never affect a later page, so retaining them would only grow a long-lived
     * radio queue's memory. Assumes the owner's queue lock is held.
     */
    private fun pruneAutomaticOccurrences(queue: QueueState) {
        if (automaticOccurrences.isEmpty()) return
        val current = queue.playOrder.indexOf(queue.currentOccurrenceId)
        if (current < 0) {
            automaticOccurrences.clear()
            return
        }
        val pending = queue.playOrder.drop(current + 1).filterTo(HashSet()) { it !in queue.manualKinds }
        automaticOccurrences.retainAll { it in pending }
    }
}
