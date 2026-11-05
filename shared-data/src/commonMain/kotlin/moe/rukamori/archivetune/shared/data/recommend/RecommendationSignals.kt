package moe.rukamori.archivetune.shared.data.recommend

import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import moe.rukamori.archivetune.shared.data.persistence.ArtistBlockDao
import moe.rukamori.archivetune.shared.data.persistence.ContentJson
import moe.rukamori.archivetune.shared.data.persistence.ListeningEventDao
import moe.rukamori.archivetune.shared.data.persistence.PortableListenEventRow
import moe.rukamori.archivetune.shared.domain.recommend.ListenFact
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.settings.BlockedSongRef

/**
 * The recommendation view of the local listening history plus the two existing block lists. The host owns when this is
 * read; nothing here performs network work or runs on the scoring path.
 */
data class RecommendationSignalSnapshot(
    val facts: List<ListenFact>,
    val blockedArtists: Set<ContentRef> = emptySet(),
    val blockedSongs: Set<BlockedSongRef> = emptySet(),
) {
    /** A candidate is blocked when its song is on the preference list or any identified credit is a blocked artist. */
    fun isBlocked(item: ContentItem): Boolean {
        if (item.ref.kind == ContentKind.SONG &&
            BlockedSongRef(item.ref.provider, item.ref.id) in blockedSongs
        ) {
            return true
        }
        return item.artists.any { credit ->
            credit.id?.let { artistId ->
                ContentRef(item.ref.provider, ContentKind.ARTIST, artistId) in blockedArtists
            } == true
        }
    }
}

/**
 * Port from the hosts' stores to typed [ListenFact]s. An implementation reads existing `Event.playTime`/duration and
 * block lists; it never downloads, and it never invents a recommendation origin that was not persisted.
 */
interface RecommendationSignals {
    /**
     * [recommendedRefs] is the in-memory set of tail/mix refs the host marked this session. The portable schema has no
     * origin columns yet (no migration in this change), so the host supplies what it already tracks rather than the
     * port pretending every listen was deliberate.
     */
    suspend fun snapshot(recommendedRefs: Set<ContentRef> = emptySet()): RecommendationSignalSnapshot
}

/**
 * Pure projection from the portable listen rows already written by `RoomListeningHistory`. A malformed occurrence is
 * skipped like any other unreadable legacy row; a non-song event is not a taste signal for a song recommender.
 */
object PortableListenFacts {
    /**
     * The Android host's `Event.playTime` plus `SongEntity.duration` pair, in the shared shape. A duration of -1 is
     * normalised to null before it reaches [ListenFact], so no completion ratio can be invented for an unknown track.
     */
    fun fromEvent(
        occurrence: ContentOccurrence,
        playedMs: Long,
        durationMs: Long?,
        atMs: Long,
        wasRecommended: Boolean = false,
        sourceArm: String? = null,
    ): ListenFact? {
        if (playedMs < 0L) return null
        val item = occurrence.item
        if (item.ref.kind != ContentKind.SONG) return null
        return ListenFact(
            ref = item.ref,
            artistIds = item.artists.mapNotNull { it.id }.distinct(),
            albumId = item.albumId,
            playedMs = playedMs,
            durationMs = durationMs?.takeIf { it > 0L },
            atMs = atMs,
            wasRecommended = wasRecommended,
            sourceArm = sourceArm,
        )
    }

    fun fromRow(row: PortableListenEventRow, recommendedRefs: Set<ContentRef> = emptySet()): ListenFact? {
        if (row.playTimeMs < 0L) return null
        val occurrence =
            runCatching {
                ContentJson.decode(Json.parseToJsonElement(row.occurrenceJson).jsonObject)
            }.getOrNull() ?: return null
        return fromEvent(
            occurrence = occurrence,
            playedMs = row.playTimeMs,
            // -1 is the Android SongEntity default, not a real duration. No duration means no completion ratio.
            durationMs = occurrence.item.durationMs,
            atMs = row.timestampMs,
            wasRecommended = occurrence.item.ref in recommendedRefs,
        )
    }

    fun snapshot(
        rows: List<PortableListenEventRow>,
        blockedArtists: Set<ContentRef> = emptySet(),
        blockedSongs: Set<BlockedSongRef> = emptySet(),
        recommendedRefs: Set<ContentRef> = emptySet(),
    ): RecommendationSignalSnapshot {
        val blockedArtistIds =
            blockedArtists.filter { it.kind == ContentKind.ARTIST }.groupBy({ it.provider }, { it.id })
        val facts =
            rows.mapNotNull { fromRow(it, recommendedRefs) }.filter { fact ->
                BlockedSongRef(fact.ref.provider, fact.ref.id) !in blockedSongs &&
                    fact.artistIds.none { artistId -> artistId in blockedArtistIds[fact.ref.provider].orEmpty() }
            }
        return RecommendationSignalSnapshot(facts, blockedArtists, blockedSongs)
    }
}

/**
 * The portable (Room) implementation. It reads the newest bounded window of listens, because a recommendation refresh
 * must not scan an unbounded history; the 14-day half-life makes older rows negligible long before that bound. The
 * block lists come from the existing artist table and the host's blocked-song preference.
 */
class PortableRecommendationSignals(
    private val listens: ListeningEventDao,
    private val artistBlocks: ArtistBlockDao,
    private val blockedSongRefs: () -> Set<BlockedSongRef> = { emptySet() },
    private val limit: Int = DEFAULT_LISTEN_LIMIT,
) : RecommendationSignals {
    init {
        require(limit > 0) { "A signal window needs at least one row" }
    }

    override suspend fun snapshot(recommendedRefs: Set<ContentRef>): RecommendationSignalSnapshot {
        val blockedArtists =
            artistBlocks.observeAll().first().mapTo(LinkedHashSet()) { row ->
                ContentRef(row.provider, ContentKind.ARTIST, row.artistId)
            }
        return PortableListenFacts.snapshot(
            rows = listens.newest(limit),
            blockedArtists = blockedArtists,
            blockedSongs = blockedSongRefs(),
            recommendedRefs = recommendedRefs,
        )
    }

    companion object {
        const val DEFAULT_LISTEN_LIMIT = 500
    }
}
