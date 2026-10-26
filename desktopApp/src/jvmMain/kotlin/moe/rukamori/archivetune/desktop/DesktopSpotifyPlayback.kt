package moe.rukamori.archivetune.desktop

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.desktop.integration.DesktopStrings
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.shared.domain.repository.ContentCredit
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.ContentRequest
import moe.rukamori.archivetune.shared.domain.repository.ContentSurface
import moe.rukamori.archivetune.shared.domain.repository.QueueCollectionKind
import moe.rukamori.archivetune.shared.domain.repository.QueueLoadRequest
import moe.rukamori.archivetune.shared.domain.repository.QueueStart
import moe.rukamori.archivetune.shared.domain.settings.DefaultMetadataSourceKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.spotify.SpotifyMapper
import moe.rukamori.archivetune.spotify.models.SpotifySimpleArtist
import moe.rukamori.archivetune.spotify.models.SpotifyTrack

/** Spotify catalog rows and their Android-identical YouTube resolution into the host queue. */
internal class DesktopSpotifyPlayback(
    private val graph: DesktopSessionGraph,
) {
    private val historyTracks = ConcurrentHashMap<String, SpotifyTrack>()
    private val mapperLock = Mutex()
    private val resolutionCache = object : LinkedHashMap<String, ContentItem>(SPOTIFY_RESOLUTION_CACHE_MAX, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ContentItem>?): Boolean =
            size > SPOTIFY_RESOLUTION_CACHE_MAX
    }

    fun spotifyTrackItem(track: SpotifyTrack): ContentItem = ContentItem(
        ref = ContentRef(ContentProvider.SPOTIFY_CATALOG.id, ContentKind.SONG, track.id),
        title = track.name,
        subtitle = track.artists.joinToString(", ") { it.name },
        artworkUri = SpotifyMapper.getTrackThumbnail(track),
        durationMs = track.durationMs.toLong(),
        explicit = track.explicit,
        album = track.album?.name,
        artists = track.artists.mapNotNull(::spotifyArtistCredit),
        albumId = track.album?.id?.takeIf { it.isNotBlank() },
    )

    /** Converts one fetched play into the row the shared history host shows. */
    fun historyTrackRow(track: SpotifyTrack, playedAtMs: Long): ContentOccurrence {
        val occurrenceId = "spotify:history:${track.id}:$playedAtMs"
        historyTracks[occurrenceId] = track
        return ContentOccurrence(occurrenceId, spotifyTrackItem(track))
    }

    /** Resolves the selected Spotify play through the Android anonymous-then-account YouTube search/score path. */
    suspend fun playHistoryTrack(start: ContentOccurrence) {
        val track = historyTracks[start.occurrenceId] ?: throw IllegalStateException("Spotify history row is no longer loaded")
        val resolved = resolveSpotifyTrack(track) ?: throw IllegalStateException(DesktopStrings["spotify_track_unavailable"])
        graph.queue.load(
            QueueLoadRequest(
                collection = ContentRequest(ContentProvider.YOUTUBE, ContentSurface.RADIO, parent = resolved.ref),
                kind = QueueCollectionKind.RADIO,
                start = QueueStart(resolved.ref),
                playWhenReady = true,
            ),
        )
    }

    /** Resolves every selected track with Android's four-at-a-time bounded resolver, then queues the matches. */
    suspend fun playTrackList(playlistId: String, tracks: List<SpotifyTrack>, startIndex: Int = 0, shuffled: Boolean = false) {
        if (tracks.isEmpty()) return
        val queueTracks = if (shuffled) tracks.shuffled() else tracks
        val boundedStart = startIndex.coerceIn(queueTracks.indices)
        // Android resolves the tapped track before the queue starts, then drains the rest in bounded chunks.
        val preload = resolveSpotifyTrack(queueTracks[boundedStart])
            ?: throw IllegalStateException(DesktopStrings["spotify_track_unavailable"])
        val resolved = resolveSpotifyQueueEntries(
            queueTracks,
            preloadTrackId = queueTracks[boundedStart].id,
            preloadItem = preload,
        )
        if (resolved.isEmpty()) throw IllegalStateException(DesktopStrings["spotify_track_unavailable"])
        val entries = resolved.map { (originalIndex, item) ->
            ContentOccurrence("spotify:playlist:resolved:$playlistId:$originalIndex:${queueTracks[originalIndex].id}", item)
        }
        val targetIndex = resolved.indexOfFirst { it.first >= boundedStart }.takeIf { it >= 0 } ?: resolved.lastIndex
        graph.queue.replace(entries, entries[targetIndex].occurrenceId, playWhenReady = true)
    }

    /**
     * The Android queue's whole-list preload: bounded chunks, one timeout per track, unresolved matches dropped.
     * Indexes stay in the submitted list so the starting target is the first resolved row at or after the tap.
     */
    private suspend fun resolveSpotifyQueueEntries(
        tracks: List<SpotifyTrack>,
        preloadTrackId: String? = null,
        preloadItem: ContentItem? = null,
    ): List<Pair<Int, ContentItem>> =
        tracks.mapIndexed { index, track -> index to track }
            .chunked(SPOTIFY_RESOLVE_BATCH_SIZE)
            .flatMap { chunk ->
                coroutineScope {
                    chunk.map { (index, track) ->
                        async(Dispatchers.IO) {
                            val item = if (preloadItem != null && track.id.isNotBlank() && track.id == preloadTrackId) {
                                preloadItem
                            } else {
                                try {
                                    withTimeoutOrNull(SPOTIFY_TRACK_RESOLVE_TIMEOUT_MS) { resolveSpotifyTrack(track) }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    null
                                }
                            }
                            item?.let { index to it }
                        }
                    }.awaitAll().filterNotNull()
                }
            }

    private suspend fun resolveSpotifyTrack(track: SpotifyTrack): ContentItem? =
        withContext(Dispatchers.IO) {
            val key = "${track.id}:${snapshot()[DefaultMetadataSourceKey] ?: "YOUTUBE"}"
            synchronized(resolutionCache) { resolutionCache[key] }?.let { return@withContext it }
            val query = SpotifyMapper.buildSearchQuery(track)
            if (query.isBlank()) return@withContext null
            val anonymousResult = YouTube.search(query, YouTube.SearchFilter.FILTER_SONG, useAccountContext = false)
            currentCoroutineContext().ensureActive()
            val anonymousCandidates = anonymousResult.getOrNull()?.items.orEmpty().filterIsInstance<SongItem>()
            val candidates = anonymousCandidates.ifEmpty {
                val fallback = YouTube.search(query, YouTube.SearchFilter.FILTER_SONG)
                currentCoroutineContext().ensureActive()
                fallback.getOrNull()?.items.orEmpty().filterIsInstance<SongItem>()
            }.distinctBy { it.id }
            if (candidates.isEmpty()) return@withContext null
            val best = mapperLock.withLock {
                val precomputed = SpotifyMapper.precompute(
                    title = track.name,
                    artist = track.artists.joinToString(" ") { it.name },
                    durationMs = track.durationMs,
                )
                candidates.map { song ->
                    song to SpotifyMapper.matchScorePrecomputed(
                        precomputed = precomputed,
                        candidateTitle = song.title,
                        candidateArtist = song.artists.joinToString(" ") { it.name },
                        candidateDurationSec = song.duration,
                    )
                }.maxByOrNull { it.second }
            }?.takeIf { it.second >= SPOTIFY_MATCH_THRESHOLD }?.first ?: return@withContext null
            val useSpotifyMetadata = snapshot()[DefaultMetadataSourceKey] == "SPOTIFY"
            val fallbackArtists = best.artists.mapNotNull { artist ->
                artist.name.takeIf(String::isNotBlank)?.let { ContentCredit(it, artist.id) }
            }
            val item = ContentItem(
                ref = ContentRef(ContentProvider.YOUTUBE.id, ContentKind.SONG, best.id),
                title = if (useSpotifyMetadata) track.name.ifBlank { best.title } else best.title,
                subtitle = if (useSpotifyMetadata) {
                    track.artists.joinToString(", ") { it.name }.ifBlank { best.artists.joinToString(", ") { it.name } }
                } else {
                    best.artists.joinToString(", ") { it.name }
                },
                artworkUri = if (useSpotifyMetadata) SpotifyMapper.getTrackThumbnail(track) ?: best.thumbnail else best.thumbnail,
                durationMs = if (useSpotifyMetadata && track.durationMs > 0) {
                    track.durationMs.toLong()
                } else {
                    best.duration?.let { it * 1000L }
                },
                explicit = track.explicit || best.explicit,
                album = if (useSpotifyMetadata) track.album?.name ?: best.album?.name else best.album?.name,
                artists = if (useSpotifyMetadata) {
                    track.artists.mapNotNull(::spotifyArtistCredit).ifEmpty { fallbackArtists }
                } else {
                    fallbackArtists
                },
                albumId = (if (useSpotifyMetadata) track.album?.id ?: best.album?.id else best.album?.id)
                    ?.takeIf { it.isNotBlank() },
            )
            synchronized(resolutionCache) { resolutionCache[key] = item }
            item
        }

    fun clearHistoryTracks() {
        historyTracks.clear()
    }

    private fun spotifyArtistCredit(artist: SpotifySimpleArtist): ContentCredit? =
        artist.name.takeIf(String::isNotBlank)?.let {
            ContentCredit(it, artist.id?.takeIf(String::isNotBlank))
        }

    private fun snapshot(): PreferenceSnapshot = graph.settings.snapshots.value ?: PreferenceSnapshot.EMPTY
}

private const val SPOTIFY_MATCH_THRESHOLD = 0.6
private const val SPOTIFY_RESOLUTION_CACHE_MAX = 512
private const val SPOTIFY_RESOLVE_BATCH_SIZE = 4
private const val SPOTIFY_TRACK_RESOLVE_TIMEOUT_MS = 15_000L
