package moe.rukamori.archivetune.shared.domain.recommend

import kotlin.math.pow
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentRef

/**
 * The four published contexts a listen can happen in. CoSeRNN found that the right long-term/context balance differs
 * by time of day, that rare contexts are the least predictable, and that keeping a long-term vector plus a
 * context-dependent offset beats one joint profile.
 * https://research.atspotify.com/2021/04/contextual-and-sequential-user-embeddings-for-music-recommendation
 */
enum class HourBucket {
    MORNING,
    DAY,
    EVENING,
    NIGHT;

    companion object {
        /** Morning 05-11, day 12-16, evening 17-21, night 22-04, the buckets CoSeRNN's sessions use. */
        fun of(hourOfDay: Int): HourBucket {
            require(hourOfDay in 0..23) { "An hour is 0..23, not $hourOfDay" }
            return when (hourOfDay) {
                in 5..11 -> MORNING
                in 12..16 -> DAY
                in 17..21 -> EVENING
                else -> NIGHT
            }
        }
    }
}

/**
 * The user's local time context. The host supplies the device zone offset, because common Kotlin has no zone database
 * and a stored UTC instant alone cannot say whether a listen happened on a weekend morning.
 */
data class ListeningContext(
    val hourOfDay: Int,
    val isWeekend: Boolean,
) {
    init {
        require(hourOfDay in 0..23) { "An hour is 0..23, not $hourOfDay" }
    }

    val hourBucket: HourBucket
        get() = HourBucket.of(hourOfDay)

    fun sameBucket(other: ListeningContext): Boolean =
        hourBucket == other.hourBucket && isWeekend == other.isWeekend

    companion object {
        private const val HOUR_MS = 3_600_000L
        private const val DAY_MS = 86_400_000L

        fun at(epochMs: Long, offsetMs: Long): ListeningContext =
            at(epochMs) { offsetMs }

        fun at(epochMs: Long, offsetMsAt: (Long) -> Long): ListeningContext {
            val local = epochMs + offsetMsAt(epochMs)
            val hour = local.floorDiv(HOUR_MS).mod(24L).toInt()
            // 1970-01-01 was a Thursday, ISO day 4. Sunday maps to 0, as the statistics buckets do.
            val isoDay = (local.floorDiv(DAY_MS) + 3L).mod(7L) + 1L
            val weekday = (isoDay % 7L).toInt()
            return ListeningContext(hour, weekday == 0 || weekday == 6)
        }
    }
}

/** One candidate and its taste affinity. The occurrence, not just the content ref, retains the provider occurrence. */
data class RankedCandidate(val occurrence: ContentOccurrence, val affinity: Double) {
    val item: ContentItem
        get() = occurrence.item
}

/**
 * Skip-aware, decayed taste affinities for songs, artists and albums.
 *
 * `affinity(entity) = sum(decay(age) * outcomeWeight * sourceWeight)`, clamped to [-0.8, 1.0] after aggregation.
 * The half-life is 14 days; outcome weights are [ListeningOutcome.affinityWeight]; a completed autoplay item counts
 * half as much as a deliberate play. Negative weights are what let a quick skip lower future preference instead of
 * merely failing to raise it.
 *
 * The long-term map is combined with a same-hour-bucket, same-weekday/weekend map over the last 28 days:
 * `(1 - contextWeight) * longTerm + contextWeight * context`. The weight is 0.3 normally and 0.5 when the current
 * bucket has fewer than 20 listens, because the global average is least reliable there.
 *
 * Everything is arithmetic and deterministic: no coroutines, no clock read, no network, and no `Random.Default` in the
 * scoring path. A fixed fact list, clock and seed produce the same order on every host.
 */
class TasteProfile internal constructor(
    private val longTermSongs: Map<ContentRef, Double>,
    private val longTermArtists: Map<ContentRef, Double>,
    private val longTermAlbums: Map<ContentRef, Double>,
    private val contextSongs: Map<ContentRef, Double>,
    private val contextArtists: Map<ContentRef, Double>,
    private val contextAlbums: Map<ContentRef, Double>,
    val context: ListeningContext,
    val contextPlays: Int,
) {
    val contextWeight: Double
        get() = if (contextPlays < RARE_CONTEXT_PLAYS) RARE_CONTEXT_WEIGHT else NORMAL_CONTEXT_WEIGHT

    fun longTermSongAffinity(ref: ContentRef): Double = longTermSongs[ref] ?: 0.0

    fun longTermArtistAffinity(ref: ContentRef): Double = longTermArtists[ref] ?: 0.0

    fun longTermAlbumAffinity(ref: ContentRef): Double = longTermAlbums[ref] ?: 0.0

    fun songAffinity(ref: ContentRef): Double =
        combine(longTermSongs[ref] ?: 0.0, contextSongs[ref] ?: 0.0)

    fun artistAffinity(ref: ContentRef): Double =
        combine(longTermArtists[ref] ?: 0.0, contextArtists[ref] ?: 0.0)

    fun albumAffinity(ref: ContentRef): Double =
        combine(longTermAlbums[ref] ?: 0.0, contextAlbums[ref] ?: 0.0)

    /**
     * A candidate's score is 50% its own song affinity, 30% its strongest credited artist and 20% its album. Only the
     * components the item actually has are weighted, so a provider result without credits is not punished for a gap.
     */
    fun itemAffinity(item: ContentItem): Double {
        val provider = item.ref.provider
        val song = songAffinity(item.ref)
        val artists =
            item.artists.mapNotNull { credit ->
                credit.id?.let { artistAffinity(ContentRef(provider, ContentKind.ARTIST, it)) }
            }
        val artist = artists.maxOrNull() ?: 0.0
        val album = item.albumId?.let { albumAffinity(ContentRef(provider, ContentKind.ALBUM, it)) } ?: 0.0
        var total = SONG_WEIGHT
        var weight = SONG_WEIGHT * song
        if (artists.isNotEmpty()) {
            total += ARTIST_WEIGHT
            weight += ARTIST_WEIGHT * artist
        }
        if (item.albumId != null) {
            total += ALBUM_WEIGHT
            weight += ALBUM_WEIGHT * album
        }
        return (weight / total).coerceIn(MIN_AFFINITY, MAX_AFFINITY)
    }

    /**
     * Affinity order with a seed-derived tie-break. A score tie is common right after a fresh install, so the seed is
     * what makes "stable for this install" true without reusing `Random.Default`'s process-dependent state.
     */
    fun rank(
        candidates: List<ContentOccurrence>,
        limit: Int,
        seed: Long,
    ): List<RankedCandidate> {
        if (limit <= 0) return emptyList()
        return candidates
            .map { RankedCandidate(it, itemAffinity(it.item)) }
            .sortedWith(
                compareByDescending<RankedCandidate> { it.affinity }
                    .thenBy { RecommendationOrdering.tieBreak(seed, it.occurrence) }
                    .thenBy { it.occurrence.item.ref.provider }
                    .thenBy { it.occurrence.item.ref.kind.name }
                    .thenBy { it.occurrence.item.ref.id }
                    .thenBy { it.occurrence.occurrenceId },
            ).take(limit)
    }

    private fun combine(longTerm: Double, context: Double): Double =
        ((1.0 - contextWeight) * longTerm + contextWeight * context).coerceIn(MIN_AFFINITY, MAX_AFFINITY)

    companion object {
        const val HALF_LIFE_MS = 1_209_600_000L
        const val CONTEXT_WINDOW_MS = 2_419_200_000L
        const val RARE_CONTEXT_PLAYS = 20
        const val NORMAL_CONTEXT_WEIGHT = 0.3
        const val RARE_CONTEXT_WEIGHT = 0.5
        const val MIN_AFFINITY = -0.8
        const val MAX_AFFINITY = 1.0
        const val SONG_WEIGHT = 0.5
        const val ARTIST_WEIGHT = 0.3
        const val ALBUM_WEIGHT = 0.2

        /**
         * Builds both maps from the same fact list. [contextOf] classifies each historical listen, so a test can pin
         * buckets without a device clock and a host can supply the device's per-instant zone offset.
         */
        fun build(
            facts: List<ListenFact>,
            nowMs: Long,
            currentContext: ListeningContext,
            contextOf: (ListenFact) -> ListeningContext,
        ): TasteProfile {
            val fromMs = if (nowMs < Long.MIN_VALUE + CONTEXT_WINDOW_MS) Long.MIN_VALUE else nowMs - CONTEXT_WINDOW_MS
            val contextFacts =
                facts.filter { fact ->
                    fact.atMs in fromMs..nowMs && contextOf(fact).sameBucket(currentContext)
                }
            return TasteProfile(
                longTermSongs = aggregateSongs(facts, nowMs),
                longTermArtists = aggregateArtists(facts, nowMs),
                longTermAlbums = aggregateAlbums(facts, nowMs),
                contextSongs = aggregateSongs(contextFacts, nowMs),
                contextArtists = aggregateArtists(contextFacts, nowMs),
                contextAlbums = aggregateAlbums(contextFacts, nowMs),
                context = currentContext,
                contextPlays = contextFacts.size,
            )
        }

        /** Convenience for hosts that already expose the baseline's per-instant zone offset (statistics does). */
        fun build(
            facts: List<ListenFact>,
            nowMs: Long,
            offsetMsAt: (Long) -> Long,
        ): TasteProfile =
            build(
                facts,
                nowMs,
                ListeningContext.at(nowMs, offsetMsAt),
            ) { fact -> ListeningContext.at(fact.atMs, offsetMsAt) }
    }

}

private class WeightedSum {
    private val values = mutableMapOf<ContentRef, Double>()

    fun add(ref: ContentRef, weight: Double) {
        values[ref] = (values[ref] ?: 0.0) + weight
    }

    fun clamped(): Map<ContentRef, Double> =
        values.mapValues { it.value.coerceIn(TasteProfile.MIN_AFFINITY, TasteProfile.MAX_AFFINITY) }
}

private fun aggregateSongs(facts: List<ListenFact>, nowMs: Long): Map<ContentRef, Double> {
    val sum = WeightedSum()
    for (fact in facts) sum.add(fact.ref, factWeight(fact, nowMs))
    return sum.clamped()
}

private fun aggregateArtists(facts: List<ListenFact>, nowMs: Long): Map<ContentRef, Double> {
    val sum = WeightedSum()
    for (fact in facts) {
        val weight = factWeight(fact, nowMs)
        for (artistId in fact.artistIds.distinct()) {
            sum.add(ContentRef(fact.ref.provider, ContentKind.ARTIST, artistId), weight)
        }
    }
    return sum.clamped()
}

private fun aggregateAlbums(facts: List<ListenFact>, nowMs: Long): Map<ContentRef, Double> {
    val sum = WeightedSum()
    for (fact in facts) {
        val albumId = fact.albumId ?: continue
        sum.add(ContentRef(fact.ref.provider, ContentKind.ALBUM, albumId), factWeight(fact, nowMs))
    }
    return sum.clamped()
}

private fun factWeight(fact: ListenFact, nowMs: Long): Double {
    val ageMs = (nowMs - fact.atMs).coerceAtLeast(0L)
    val decay = 0.5.pow(ageMs.toDouble() / TasteProfile.HALF_LIFE_MS)
    return decay * fact.outcome.affinityWeight * fact.sourceWeight
}
