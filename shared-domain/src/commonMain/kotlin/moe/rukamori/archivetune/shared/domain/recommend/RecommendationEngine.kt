package moe.rukamori.archivetune.shared.domain.recommend

import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentRef

/**
 * The one facade hosts call. It is pure: callers build a [TasteProfile] from their signal port, then rank a shelf or
 * plan an autoplay tail. There is no I/O here, so a host can run it on a background dispatcher once per refresh and
 * never per item.
 *
 * Sources: skip-aware outcome thresholds (https://musicmachinery.com/2014/05/02/the-skip/), CoSeRNN's long-term plus
 * context offset
 * (https://research.atspotify.com/2021/04/contextual-and-sequential-user-embeddings-for-music-recommendation), and
 * constrained playlist generation with MMR-style diversity
 * (https://research.atspotify.com/2023/10/exploiting-sequential-music-preferences-via-optimisation-based-playlist-generation).
 */
object RecommendationEngine {
    fun rank(
        candidates: List<ContentOccurrence>,
        profile: TasteProfile,
        limit: Int,
        seed: Long,
    ): List<RankedCandidate> = profile.rank(candidates, limit, seed)

    fun planTail(
        candidates: List<TailCandidate>,
        queue: List<ContentOccurrence>,
        recentPlays: List<ContentRef>,
        profile: TasteProfile,
        budget: Int,
        seed: Long,
        constraints: DiversityConstraints = DiversityConstraints.Default,
    ): QueueTailPlan =
        QueueTailPlanner.plan(candidates, queue, recentPlays, profile, budget, seed, constraints)
}
