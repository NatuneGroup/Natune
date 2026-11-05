package moe.rukamori.archivetune.shared.data.recommend

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.shared.domain.recommend.DiversityConstraints
import moe.rukamori.archivetune.shared.domain.recommend.DiversityPolicy
import moe.rukamori.archivetune.shared.domain.recommend.QueueTailPlan
import moe.rukamori.archivetune.shared.domain.recommend.QueueTailPlanner
import moe.rukamori.archivetune.shared.domain.recommend.RankedCandidate
import moe.rukamori.archivetune.shared.domain.recommend.TailCandidate
import moe.rukamori.archivetune.shared.domain.recommend.TasteProfile
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.QueueState

/**
 * Host-facing adapter for the recommendation policy. `PortableInfiniteQueue` calls the [QueueState] overload with the
 * provider page it already fetched, so choosing the automatic tail never adds a network request and no queue
 * projection runs on the queue-command thread. A host that does not inject the adapter keeps the provider-order tail.
 *
 * Reads happen once per call through [RecommendationSignals]; the read and the comparison/MMR both run on
 * [computeDispatcher]. There is no network call anywhere in this path and no per-item scoring on the caller's thread.
 */
class QueueTailRecommendation(
    private val signals: RecommendationSignals,
    private val nowMs: () -> Long,
    private val offsetMsAt: (Long) -> Long = { 0L },
    private val recommendedRefs: () -> Set<ContentRef> = { emptySet() },
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val constraints: DiversityConstraints = DiversityConstraints.Default,
) {
    /**
     * Plans at most [budget] songs, further capped by [QueueTailLimits.MAX_TAIL_ITEMS] minus [existingTailSize]. An
     * empty result means the caller should keep the tail empty and let the existing load gate back off; no exception
     * stands in for "nothing passed the constraints".
     */
    suspend fun planTail(
        candidates: List<ContentOccurrence>,
        queue: List<ContentOccurrence>,
        seed: Long,
        budget: Int = DEFAULT_TAIL_BUDGET,
        existingTailSize: Int = 0,
    ): QueueTailPlan =
        withContext(computeDispatcher) {
            planTailNow(candidates, queue, seed, budget, existingTailSize)
        }

    /**
     * The owner's entry point. Candidate capping, play-order projection, seed mixing and pending-tail accounting all
     * run on [computeDispatcher]; [pendingAutomaticIds] is a value snapshot the owner took under its queue lock.
     * Passing the state, rather than a pre-mapped list, keeps that preparation off the queue-command thread.
     */
    suspend fun planTail(
        candidates: List<ContentOccurrence>,
        queue: QueueState,
        pendingAutomaticIds: Set<String>,
        budget: Int = DEFAULT_TAIL_BUDGET,
    ): QueueTailPlan =
        withContext(computeDispatcher) {
            planTailNow(
                candidates = candidates.take(MAX_SCORED_CANDIDATES),
                // Play order, not the stored timeline, decides the artist run the tail must respect.
                queue = queueInPlayOrder(queue),
                seed = queueTailSeed(queue.entries.firstOrNull()?.item?.ref),
                budget = budget,
                existingTailSize = automaticTailSize(queue, pendingAutomaticIds),
            )
        }

    private suspend fun planTailNow(
        candidates: List<ContentOccurrence>,
        queue: List<ContentOccurrence>,
        seed: Long,
        budget: Int,
        existingTailSize: Int,
    ): QueueTailPlan {
        val snapshot = signals.snapshot(recommendedRefs())
        val atMs = nowMs()
        val visible = candidates.filterNot { snapshot.isBlocked(it.item) }
        val plan =
            QueueTailPlanner.plan(
                candidates = visible.map { TailCandidate(it) },
                queue = queue,
                recentPlays = DiversityPolicy.recentRefs(snapshot.facts, constraints.recencyWindowPlays),
                profile = TasteProfile.build(snapshot.facts, atMs, offsetMsAt),
                budget = minOf(budget, QueueTailLimits.remaining(existingTailSize, constraints.maxTailItems)),
                seed = seed,
                constraints = constraints,
            )
        return plan.copy(rejections = plan.rejections.copy(blocked = candidates.size - visible.size))
    }

    /** The P1 shelf entry point: affinity order, global block list, deterministic seed. */
    suspend fun rankCandidates(
        candidates: List<ContentOccurrence>,
        seed: Long,
        limit: Int = DEFAULT_SHELF_LIMIT,
    ): List<RankedCandidate> =
        withContext(computeDispatcher) {
            val snapshot = signals.snapshot(recommendedRefs())
            val atMs = nowMs()
            val visible = candidates.filterNot { snapshot.isBlocked(it.item) }
            TasteProfile.build(snapshot.facts, atMs, offsetMsAt).rank(visible, limit, seed)
        }

    companion object {
        const val DEFAULT_TAIL_BUDGET = 10
        const val DEFAULT_SHELF_LIMIT = 20

        /** One provider page can be long; the planner's cost stays independent of that size. */
        const val MAX_SCORED_CANDIDATES = 100
    }
}

/**
 * The planner's tie-break seed for one queue. A fixed FNV-1a hash of the queue's opening song keeps an equal-score
 * choice stable without `Random.Default`, `String.hashCode`, the revision or the backoff's monotonic clock, none of
 * which is contractual across a restart or between hosts. A missing song seeds the neutral zero.
 */
fun queueTailSeed(ref: ContentRef?): Long {
    if (ref == null) return 0L
    var hash = SEED_FNV_OFFSET
    for (part in listOf(ref.provider, ref.kind.name, ref.id)) {
        for (character in part) hash = (hash xor character.code.toLong()) * SEED_FNV_PRIME
        hash = (hash xor SEED_PART_SEPARATOR) * SEED_FNV_PRIME
    }
    return hash
}

private const val SEED_FNV_OFFSET = -3750763034362895579L
private const val SEED_FNV_PRIME = 1_099_511_628_211L
private const val SEED_PART_SEPARATOR = 0x1fL

/** The queue in actual play order; a legacy order that omitted an entry still exposes it to the duplicate filter. */
private fun queueInPlayOrder(queue: QueueState): List<ContentOccurrence> {
    val byId = queue.entries.associateBy { it.occurrenceId }
    val ordered = queue.playOrder.mapNotNull { byId[it] }
    if (ordered.size == queue.entries.size) return ordered
    val seen = ordered.mapTo(HashSet()) { it.occurrenceId }
    return ordered + queue.entries.filterNot { it.occurrenceId in seen }
}

/** Only automatic ids still ahead of the listener and not hand-queued consume the tail budget. */
private fun automaticTailSize(queue: QueueState, pendingAutomaticIds: Set<String>): Int {
    if (pendingAutomaticIds.isEmpty()) return 0
    val current = queue.playOrder.indexOf(queue.currentOccurrenceId)
    if (current < 0) return 0
    return queue.playOrder.drop(current + 1)
        .count { it in pendingAutomaticIds && it !in queue.manualKinds }
}

/**
 * The bounded tail length policy. It lives next to the adapter rather than in `InfiniteQueuePolicy`: paging and
 * backoff stay owner-owned, while the appended tail may never exceed [MAX_TAIL_ITEMS] however large the provider page
 * or the manual queue is.
 */
object QueueTailLimits {
    const val MAX_TAIL_ITEMS = QueueTailPlanner.MAX_TAIL_ITEMS

    fun remaining(
        existingTailSize: Int,
        maxTailItems: Int = MAX_TAIL_ITEMS,
    ): Int = (maxTailItems - existingTailSize.coerceAtLeast(0)).coerceAtLeast(0)
}
