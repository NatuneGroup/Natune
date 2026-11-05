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

/**
 * Host-facing adapter for the recommendation policy. It exposes [planTail] and [rankCandidates] but deliberately does
 * not change `PortableQueueOwner`/`PortableInfiniteQueue` semantics: the wiring change is a separate step that can
 * call this adapter with the same candidate page the tail already fetched. Until then the current provider-order tail
 * is untouched.
 *
 * Reads happen once per call through [RecommendationSignals]; comparison and MMR run on [computeDispatcher]. There is
 * no network call anywhere in this path and no per-item scoring on the caller's thread.
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
    ): QueueTailPlan {
        val snapshot = signals.snapshot(recommendedRefs())
        val atMs = nowMs()
        return withContext(computeDispatcher) {
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
            plan.copy(rejections = plan.rejections.copy(blocked = candidates.size - visible.size))
        }
    }

    /** The P1 shelf entry point: affinity order, global block list, deterministic seed. */
    suspend fun rankCandidates(
        candidates: List<ContentOccurrence>,
        seed: Long,
        limit: Int = DEFAULT_SHELF_LIMIT,
    ): List<RankedCandidate> {
        val snapshot = signals.snapshot(recommendedRefs())
        val atMs = nowMs()
        return withContext(computeDispatcher) {
            val visible = candidates.filterNot { snapshot.isBlocked(it.item) }
            TasteProfile.build(snapshot.facts, atMs, offsetMsAt).rank(visible, limit, seed)
        }
    }

    companion object {
        const val DEFAULT_TAIL_BUDGET = 10
        const val DEFAULT_SHELF_LIMIT = 20
    }
}

/**
 * The bounded tail length policy. It lives next to the adapter rather than in `InfiniteQueuePolicy` because changing
 * the existing paging/backoff contract belongs to the wiring change, not to this API-only addition.
 */
object QueueTailLimits {
    const val MAX_TAIL_ITEMS = QueueTailPlanner.MAX_TAIL_ITEMS

    fun remaining(
        existingTailSize: Int,
        maxTailItems: Int = MAX_TAIL_ITEMS,
    ): Int = (maxTailItems - existingTailSize.coerceAtLeast(0)).coerceAtLeast(0)
}
