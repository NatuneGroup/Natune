package moe.rukamori.archivetune.shared.domain.recommend

import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentRef

/**
 * One provider result offered to the tail. [exploratory] marks results from a deliberately unfamiliar source; the
 * planner gives one output slot in five to the best of them. [sourceArm] is retained for the outcome store that will
 * feed the source bandit later, without making this policy depend on it.
 */
data class TailCandidate(
    val occurrence: ContentOccurrence,
    val exploratory: Boolean = false,
    val sourceArm: String? = null,
)

/** Why candidates were dropped. The counts mirror Spotify's `afterFilteringSize` audit fields. */
data class TailRejections(
    val duplicate: Int = 0,
    val recency: Int = 0,
    val consecutiveArtist: Int = 0,
    val artistWindow: Int = 0,
    val albumWindow: Int = 0,
    val negativeAffinity: Int = 0,
    val blocked: Int = 0,
)

/** The ordered tail plus its audit counters. An empty [items] is a normal result, never an exception. */
data class QueueTailPlan(
    val items: List<ContentOccurrence> = emptyList(),
    val rejections: TailRejections = TailRejections(),
    val exploreSlotsFilled: Int = 0,
    val affinityFloorRelaxed: Boolean = false,
) {
    val isEmpty: Boolean
        get() = items.isEmpty()
}

/**
 * Pure, deterministic autoplay-tail planner.
 *
 * 1. Hard filters: no ref already in the queue or already offered, no ref among the last 40 played occurrences, no
 *    two tracks in a row by one artist, at most two tracks per artist and one per album in a rolling ten, and nothing
 *    below the -0.5 affinity floor unless the stricter pool would produce no tail at all.
 * 2. Soft order: greedy maximal marginal relevance, `lambda * normalisedAffinity - (1 - lambda) * maxSimilarity`,
 *    with lambda 0.7 over the metadata Jaccard in [DiversityPolicy].
 * 3. Exploration: one slot in five takes a candidate marked [TailCandidate.exploratory] when one fits, selected
 *    deterministically from the top three by affinity. The Smart Shuffle ratio (one in three) is too aggressive for
 *    a tail that is already unfamiliar.
 *
 * The planner never adds network calls, coroutines or a clock read. Its output is stable for a fixed seed, and it does
 * not mutate its input lists. Bounded by [MAX_TAIL_ITEMS] independently of how many pages a provider can offer.
 */
object QueueTailPlanner {
    const val MAX_TAIL_ITEMS = 50

    fun plan(
        candidates: List<TailCandidate>,
        queue: List<ContentOccurrence>,
        recentPlays: List<ContentRef>,
        profile: TasteProfile,
        budget: Int,
        seed: Long,
        constraints: DiversityConstraints = DiversityConstraints.Default,
    ): QueueTailPlan {
        if (budget <= 0 || constraints.maxTailItems <= 0) return QueueTailPlan()
        val prepared = prepareCandidates(candidates, queue, recentPlays, constraints)
        val scores = prepared.candidates.associateWith { profile.itemAffinity(it.occurrence.item) }
        val belowFloor = prepared.candidates.filter { (scores[it] ?: 0.0) < constraints.affinityFloor }
        val strictPool = prepared.candidates.filter { (scores[it] ?: 0.0) >= constraints.affinityFloor }
        val strict = planPool(strictPool, scores, queue, budget, seed, constraints)
        val baseRejections =
            TailRejections(
                duplicate = prepared.duplicateRejected,
                recency = prepared.recencyRejected,
                consecutiveArtist = strict.consecutiveArtist,
                artistWindow = strict.artistWindow,
                albumWindow = strict.albumWindow,
            )
        if (strict.items.isNotEmpty() || belowFloor.isEmpty()) {
            return QueueTailPlan(
                items = strict.items,
                rejections = baseRejections.copy(negativeAffinity = belowFloor.size),
                exploreSlotsFilled = strict.exploreSlotsFilled,
            )
        }
        // The documented escape hatch: a negative tail is better than no tail, but only when nothing else fits.
        val relaxed = planPool(prepared.candidates, scores, queue, budget, seed, constraints)
        return QueueTailPlan(
            items = relaxed.items,
            rejections =
                baseRejections.copy(
                    consecutiveArtist = relaxed.consecutiveArtist,
                    artistWindow = relaxed.artistWindow,
                    albumWindow = relaxed.albumWindow,
                ),
            exploreSlotsFilled = relaxed.exploreSlotsFilled,
            affinityFloorRelaxed = true,
        )
    }

    private data class PreparedCandidates(
        val candidates: List<TailCandidate>,
        val duplicateRejected: Int,
        val recencyRejected: Int,
    )

    private fun prepareCandidates(
        candidates: List<TailCandidate>,
        queue: List<ContentOccurrence>,
        recentPlays: List<ContentRef>,
        constraints: DiversityConstraints,
    ): PreparedCandidates {
        val queuedRefs = queue.mapTo(HashSet()) { it.item.ref }
        val queuedOccurrences = queue.mapTo(HashSet()) { it.occurrenceId }
        val seenRefs = HashSet<ContentRef>()
        val seenOccurrences = HashSet<String>()
        var duplicateRejected = 0
        val unique = ArrayList<TailCandidate>(candidates.size)
        for (candidate in candidates) {
            val ref = candidate.occurrence.item.ref
            val occurrenceId = candidate.occurrence.occurrenceId
            val newRef = seenRefs.add(ref)
            val newOccurrence = seenOccurrences.add(occurrenceId)
            if (!newRef || !newOccurrence || ref in queuedRefs || occurrenceId in queuedOccurrences) {
                duplicateRejected++
                continue
            }
            unique += candidate
        }
        val recency = recentPlays.take(constraints.recencyWindowPlays).toHashSet()
        val recencyRejected = HashSet<ContentRef>()
        val fresh =
            unique.filter { candidate ->
                if (candidate.occurrence.item.ref in recency) {
                    recencyRejected += candidate.occurrence.item.ref
                    false
                } else {
                    true
                }
            }
        return PreparedCandidates(fresh, duplicateRejected, recencyRejected.size)
    }

    private data class PoolPlan(
        val items: List<ContentOccurrence> = emptyList(),
        val consecutiveArtist: Int = 0,
        val artistWindow: Int = 0,
        val albumWindow: Int = 0,
        val exploreSlotsFilled: Int = 0,
    )

    private fun planPool(
        pool: List<TailCandidate>,
        scores: Map<TailCandidate, Double>,
        queue: List<ContentOccurrence>,
        budget: Int,
        seed: Long,
        constraints: DiversityConstraints,
    ): PoolPlan {
        val limit = minOf(budget, constraints.maxTailItems)
        if (pool.isEmpty() || limit <= 0) return PoolPlan()
        val minScore = pool.minOf { scores.getValue(it) }
        val maxScore = pool.maxOf { scores.getValue(it) }
        val span = maxScore - minScore
        fun normalised(candidate: TailCandidate): Double =
            if (span <= 0.0) 0.5 else ((scores.getValue(candidate) - minScore) / span).coerceIn(0.0, 1.0)

        val state = TailConstraintState(queue.lastOrNull()?.item, constraints)
        val remaining = pool.toMutableList()
        val items = ArrayList<ContentOccurrence>(limit)
        val consecutiveRejected = HashSet<ContentRef>()
        val artistWindowRejected = HashSet<ContentRef>()
        val albumWindowRejected = HashSet<ContentRef>()
        var exploreSlotsFilled = 0

        while (items.size < limit && remaining.isNotEmpty()) {
            val eligible = ArrayList<TailCandidate>(remaining.size)
            for (candidate in remaining) {
                when (state.violation(candidate.occurrence.item)) {
                    null -> eligible += candidate
                    TailConstraintViolation.CONSECUTIVE_ARTIST -> consecutiveRejected += candidate.occurrence.item.ref
                    TailConstraintViolation.ARTIST_WINDOW -> artistWindowRejected += candidate.occurrence.item.ref
                    TailConstraintViolation.ALBUM_WINDOW -> albumWindowRejected += candidate.occurrence.item.ref
                }
            }
            if (eligible.isEmpty()) break
            val slot = items.size + 1
            val wantsExploration =
                constraints.exploreEverySlots > 0 && slot % constraints.exploreEverySlots == 0
            val explore = if (wantsExploration) chooseExploratory(eligible, scores, seed, slot) else null
            val chosen =
                explore ?: chooseByMmr(eligible, scores, items, seed, constraints) { normalised(it) }
            if (explore != null) exploreSlotsFilled++
            remaining.remove(chosen)
            items += chosen.occurrence
            state.accept(chosen.occurrence.item)
        }
        return PoolPlan(
            items = items,
            consecutiveArtist = consecutiveRejected.size,
            artistWindow = artistWindowRejected.size,
            albumWindow = albumWindowRejected.size,
            exploreSlotsFilled = exploreSlotsFilled,
        )
    }

    private fun chooseExploratory(
        eligible: List<TailCandidate>,
        scores: Map<TailCandidate, Double>,
        seed: Long,
        slot: Int,
    ): TailCandidate? {
        val exploratory = eligible.filter { it.exploratory }
        if (exploratory.isEmpty()) return null
        val ranked =
            exploratory.sortedWith(
                compareByDescending<TailCandidate> { scores.getValue(it) }
                    .thenBy { RecommendationOrdering.tieBreak(seed, it.occurrence) }
                    .thenBy { it.occurrence.item.ref.provider }
                    .thenBy { it.occurrence.item.ref.kind.name }
                    .thenBy { it.occurrence.item.ref.id }
                    .thenBy { it.occurrence.occurrenceId },
            )
        val window = ranked.take(minOf(EXPLORATION_POOL_SIZE, ranked.size))
        val index =
            (RecommendationOrdering.mix(seed, "explore", slot.toString()).ushr(1) % window.size.toLong()).toInt()
        return window[index]
    }

    private fun chooseByMmr(
        eligible: List<TailCandidate>,
        scores: Map<TailCandidate, Double>,
        picked: List<ContentOccurrence>,
        seed: Long,
        constraints: DiversityConstraints,
        normalised: (TailCandidate) -> Double,
    ): TailCandidate {
        val mmr =
            eligible.associateWith { candidate ->
                val similarity =
                    picked.maxOfOrNull { previous ->
                        DiversityPolicy.similarity(previous.item, candidate.occurrence.item)
                    } ?: 0.0
                constraints.mmrLambda * normalised(candidate) -
                    (1.0 - constraints.mmrLambda) * similarity
            }
        return checkNotNull(
            eligible.maxWithOrNull(
                compareBy<TailCandidate> { mmr.getValue(it) }
                    .thenBy { RecommendationOrdering.tieBreak(seed, it.occurrence) }
                    .thenBy { it.occurrence.item.ref.provider }
                    .thenBy { it.occurrence.item.ref.kind.name }
                    .thenBy { it.occurrence.item.ref.id }
                    .thenBy { it.occurrence.occurrenceId },
            ),
        )
    }

    private const val EXPLORATION_POOL_SIZE = 3
}
