package moe.rukamori.archivetune.playback

import java.time.ZoneId
import moe.rukamori.archivetune.db.DatabaseDao
import moe.rukamori.archivetune.db.entities.EventWithSong
import moe.rukamori.archivetune.shared.data.recommend.PortableListenFacts
import moe.rukamori.archivetune.shared.data.recommend.PortableRecommendationSignals
import moe.rukamori.archivetune.shared.data.recommend.RecommendationSignalSnapshot
import moe.rukamori.archivetune.shared.data.recommend.RecommendationSignals
import moe.rukamori.archivetune.shared.domain.recommend.ListenFact
import moe.rukamori.archivetune.shared.domain.repository.ContentCredit
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.settings.BlockedSongRef

/**
 * The retained Android host's bounded recommendation history. It reads the same newest event window, artist block
 * table and per-song block table the rest of the app uses; there is no new query, migration or background writer.
 *
 * Android rows are YouTube song identities. Local media and Telegram rows are dropped rather than relabelled as
 * YouTube taste, and credits only carry the provider ids the saved artist rows actually have.
 */
internal class AndroidRecommendationSignals(
    private val events: suspend () -> List<EventWithSong>,
    private val blockedArtistIds: suspend () -> Set<String>,
    private val blockedSongIds: suspend () -> Set<String>,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) : RecommendationSignals {
    constructor(database: DatabaseDao, zoneId: ZoneId = ZoneId.systemDefault()) : this(
        events = { database.events(PortableRecommendationSignals.DEFAULT_LISTEN_LIMIT, 0) },
        blockedArtistIds = { database.getBlockedArtistIds().toSet() },
        blockedSongIds = { database.getBlockedSongIds().toSet() },
        zoneId = zoneId,
    )

    override suspend fun snapshot(recommendedRefs: Set<ContentRef>): RecommendationSignalSnapshot {
        // The Android schema has no recommendation-origin column; a saved listen is never marked autoplay here.
        val blockedArtistValues = blockedArtistIds()
        val blockedSongValues = blockedSongIds()
        val blockedArtists =
            blockedArtistValues.mapTo(LinkedHashSet()) { ContentRef(ContentProvider.YOUTUBE.id, ContentKind.ARTIST, it) }
        val blockedSongs =
            blockedSongValues.mapTo(LinkedHashSet()) { BlockedSongRef(ContentProvider.YOUTUBE.id, it) }
        val facts =
            events().mapNotNull(::fact).filterNot { fact ->
                BlockedSongRef(fact.ref.provider, fact.ref.id) in blockedSongs ||
                    fact.artistIds.any { it in blockedArtistValues }
            }
        return RecommendationSignalSnapshot(facts = facts, blockedArtists = blockedArtists, blockedSongs = blockedSongs)
    }

    private fun fact(row: EventWithSong): ListenFact? {
        val song = row.song.song
        if (song.isLocal || song.id.isNonYouTubeMediaId()) return null
        val artists = row.song.artists.filter { !it.isLocal && it.id.isNotBlank() }
        val item =
            ContentItem(
                ref = ContentRef(ContentProvider.YOUTUBE.id, ContentKind.SONG, song.id),
                title = row.song.title,
                durationMs = song.duration.takeIf { it > 0 }?.toLong()?.times(1000L),
                album = song.albumName,
                artists = artists.map { ContentCredit(it.name, it.id) },
                albumId = song.albumId?.takeIf(String::isNotBlank),
            )
        return PortableListenFacts.fromEvent(
            occurrence = ContentOccurrence(song.id, item),
            playedMs = row.event.playTime,
            durationMs = item.durationMs,
            atMs = row.event.timestamp.atZone(zoneId).toInstant().toEpochMilli(),
        )
    }
}

/** Local and Telegram media ids are URI-shaped, never YouTube video ids. */
private fun String.isNonYouTubeMediaId(): Boolean =
    startsWith("content://", ignoreCase = true) ||
        startsWith("file://", ignoreCase = true) ||
        startsWith("android.resource://", ignoreCase = true) ||
        startsWith("telegram://", ignoreCase = true)
