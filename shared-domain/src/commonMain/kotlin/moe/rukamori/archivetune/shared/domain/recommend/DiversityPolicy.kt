package moe.rukamori.archivetune.shared.domain.recommend

import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentRef

/**
 * The tail's hard and soft constraints. Values are the published starting points from the audit: 40 played
 * occurrences of recency, no artist twice in a row, at most two tracks per artist and one per album in any rolling
 * ten, an MMR lambda of 0.7, and a negative-affinity floor of -0.5.
 *
 * https://engineering.atspotify.com/2025/11/shuffle-making-random-feel-more-human
 * https://research.atspotify.com/2021/03/shifting-consumption-towards-diverse-content-via-reinforcement-learning
 * https://newsroom.spotify.com/2023-03-08/smart-shuffle-new-life-spotify-playlists/
 */
data class DiversityConstraints(
    val recencyWindowPlays: Int = 40,
    val maxConsecutiveSameArtist: Int = 1,
    val artistCap: Int = 2,
    val artistWindow: Int = 10,
    val albumCap: Int = 1,
    val albumWindow: Int = 10,
    val mmrLambda: Double = 0.7,
    val affinityFloor: Double = -0.5,
    val exploreEverySlots: Int = 5,
    val maxTailItems: Int = 50,
) {
    init {
        require(recencyWindowPlays >= 0) { "The recency window cannot be negative" }
        require(maxConsecutiveSameArtist >= 1) { "At least one track per artist run is always allowed" }
        require(artistCap >= 1) { "At least one track per artist window is always allowed" }
        require(artistWindow >= 2) { "A rolling window needs at least two items" }
        require(albumCap >= 1) { "At least one track per album window is always allowed" }
        require(albumWindow >= 2) { "A rolling window needs at least two items" }
        require(mmrLambda in 0.0..1.0) { "MMR lambda is a probability-like weight" }
        require(exploreEverySlots >= 0) { "Zero disables the exploration slot" }
        require(maxTailItems >= 0) { "A tail length cap cannot be negative" }
    }

    companion object {
        val Default = DiversityConstraints()
    }
}

/**
 * Cheap song identity for diversity. No embeddings or provider calls: two tracks are similar when they share artists,
 * an album, or both. Jaccard is deliberately metadata-only because the optional automix analyser is absent from
 * standard packages; a build-flavour-specific BPM term would make ranking depend on the flavour.
 */
object DiversityPolicy {
    fun artistIds(item: ContentItem): Set<String> =
        item.artists.mapNotNullTo(LinkedHashSet()) { credit -> credit.id }

    fun features(item: ContentItem): Set<String> {
        val features = LinkedHashSet<String>()
        for (artistId in artistIds(item)) features += "artist:$artistId"
        item.albumId?.let { features += "album:$it" }
        return features
    }

    fun similarity(left: ContentItem, right: ContentItem): Double {
        val leftFeatures = features(left)
        val rightFeatures = features(right)
        if (leftFeatures.isEmpty() || rightFeatures.isEmpty()) return 0.0
        val intersection = leftFeatures.count { it in rightFeatures }
        if (intersection == 0) return 0.0
        return intersection.toDouble() / (leftFeatures.size + rightFeatures.size - intersection)
    }

    /**
     * The newest played refs, newest first. The caller passes the same fact list to the profile and to the planner, so
     * "don't replay what was just heard" is one window rather than a second, differently-clocked history read.
     */
    fun recentRefs(
        facts: List<ListenFact>,
        limit: Int = DiversityConstraints.Default.recencyWindowPlays,
    ): List<ContentRef> {
        if (limit <= 0) return emptyList()
        // sortedByDescending is stable, so equal timestamps retain the caller's store order.
        return facts.sortedByDescending { it.atMs }.take(limit).map { it.ref }
    }
}

/** Why one candidate did not fit at a given slot. The first failing hard constraint is the reported one. */
internal enum class TailConstraintViolation {
    CONSECUTIVE_ARTIST,
    ARTIST_WINDOW,
    ALBUM_WINDOW,
}

/**
 * Rolling hard-constraint state for one planned tail. The item already at the end of the queue seeds the artist run,
 * so the planner cannot append the same artist immediately after the playing song. Window caps apply to the planned
 * tail only, because the queue before it is the listener's own order and must not be rewritten.
 */
internal class TailConstraintState(
    previousItem: ContentItem?,
    private val constraints: DiversityConstraints,
) {
    private val artistHistory = ArrayDeque<Set<String>>()
    private val albumHistory = ArrayDeque<String>()
    private var runArtists: Set<String> = previousItem?.let(DiversityPolicy::artistIds).orEmpty()
    private var runLength: Int = if (previousItem == null) 0 else 1

    fun violation(item: ContentItem): TailConstraintViolation? {
        val artists = DiversityPolicy.artistIds(item)
        if (artists.isNotEmpty() && runLength >= constraints.maxConsecutiveSameArtist &&
            artists.any { it in runArtists }
        ) {
            return TailConstraintViolation.CONSECUTIVE_ARTIST
        }
        val artistHits = artistHistory.count { previous -> artists.any { it in previous } }
        if (artists.isNotEmpty() && artistHits >= constraints.artistCap) {
            return TailConstraintViolation.ARTIST_WINDOW
        }
        val albumId = item.albumId
        if (albumId != null && albumHistory.count { it == albumId } >= constraints.albumCap) {
            return TailConstraintViolation.ALBUM_WINDOW
        }
        return null
    }

    fun accept(item: ContentItem) {
        val artists = DiversityPolicy.artistIds(item)
        artistHistory.addLast(artists)
        while (artistHistory.size > constraints.artistWindow - 1) artistHistory.removeFirst()
        item.albumId?.let { albumId ->
            albumHistory.addLast(albumId)
            while (albumHistory.size > constraints.albumWindow - 1) albumHistory.removeFirst()
        }
        val sharesRun = artists.isNotEmpty() && artists.any { it in runArtists }
        if (sharesRun) {
            runLength++
            val intersection = runArtists intersect artists
            runArtists = if (intersection.isEmpty()) artists else intersection
        } else {
            runArtists = artists
            runLength = if (artists.isEmpty()) 0 else 1
        }
    }
}
