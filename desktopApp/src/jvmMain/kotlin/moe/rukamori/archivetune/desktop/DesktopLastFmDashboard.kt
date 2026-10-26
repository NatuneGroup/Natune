package moe.rukamori.archivetune.desktop

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.shared.data.persistence.RoomLibraryRepository
import moe.rukamori.archivetune.shared.domain.queue.UserQueuedKind
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentQuery
import moe.rukamori.archivetune.shared.domain.repository.ContentRequest
import moe.rukamori.archivetune.shared.domain.repository.ContentSearchFilter
import moe.rukamori.archivetune.shared.domain.repository.ContentSurface
import moe.rukamori.archivetune.shared.domain.settings.LastFmPreferYtThumbnailsKey
import moe.rukamori.archivetune.shared.ui.navigation.ShellNavigation
import moe.rukamori.archivetune.shared.ui.settings.lastfmdashboard.LastFmArtworkRequest
import moe.rukamori.archivetune.shared.ui.settings.lastfmdashboard.LastFmDashboardBinding
import moe.rukamori.archivetune.shared.ui.settings.lastfmdashboard.LastFmDashboardHost
import moe.rukamori.archivetune.shared.ui.settings.lastfmdashboard.LastFmDashboardPage
import moe.rukamori.archivetune.shared.ui.settings.lastfmdashboard.LastFmDashboardTrack

/** The dashboard is a settings route on this host, so the settings screen mounts it without a new shared destination. */
internal const val DesktopLastFmDashboardRoute = "settings/lastfm/dashboard"

/**
 * The Last.fm dashboard over the graph's scrobbling account. The binding runs on the graph scope, so a load or a queue
 * action finishes even after the page closes.
 */
@Composable
internal fun DesktopLastFmDashboardPage(graph: DesktopSessionGraph, navigation: ShellNavigation, unavailable: () -> Unit) {
    val currentUnavailable by rememberUpdatedState(unavailable)
    val binding = remember(graph, navigation) {
        LastFmDashboardBinding(
            scope = graph.scope,
            reader = graph.lastFm.dashboardReader,
            service = graph.lastFm.dashboardService,
            host = DesktopLastFmDashboardHost(graph, openRoute = navigation::open, unavailable = { currentUnavailable() }),
        )
    }
    val snapshot by graph.settings.snapshots.collectAsState()
    LastFmDashboardPage(
        binding = binding,
        image = { url, modifier, circle ->
            DesktopRemoteArtwork(url, graph.artworkLoader, if (circle) modifier.clip(CircleShape) else modifier)
        },
        preferYouTubeArtwork = snapshot?.get(LastFmPreferYtThumbnailsKey) == true,
        back = { navigation.back() },
        onSignIn = { navigation.open("settings/lastfm") },
        // The settings screen already pads its box by the shell padding.
        contentPadding = PaddingValues(),
    )
}

/**
 * What the dashboard reaches on this host: the graph's YouTube catalogue for matches and thumbnails, the local library
 * for covers, the one queue, the clipboard and the browser.
 */
internal class DesktopLastFmDashboardHost(
    private val graph: DesktopSessionGraph,
    private val openRoute: (String) -> Unit,
    private val unavailable: () -> Unit,
) : LastFmDashboardHost {
    override suspend fun findYouTubeSong(artist: String?, title: String): ContentOccurrence? =
        firstYouTubeResult(searchTerm(title, artist), ContentSearchFilter.SONGS, ContentKind.SONG)

    override fun isPlayerAvailable(): Boolean = true

    /**
     * The desktop YouTube catalogue has no watch-next radio, so the matched song plays on its own and no mix follows it.
     * The missing tail is logged rather than filled with unrelated songs.
     */
    override fun startRadio(song: ContentOccurrence) {
        val entry = song.queuedCopy()
        queueCommand("the song was not played") {
            graph.queue.replace(listOf(entry), entry.occurrenceId, playWhenReady = true)
            graph.report("Last.fm dashboard: the song plays without a radio mix, which this host cannot load")
        }
    }

    override fun playNext(song: ContentOccurrence) {
        queueCommand("the song was not queued") { graph.queue.insert(listOf(song.queuedCopy()), UserQueuedKind.PLAY_NEXT) }
    }

    override fun addToQueue(song: ContentOccurrence) {
        queueCommand("the song was not queued") { graph.queue.insert(listOf(song.queuedCopy()), UserQueuedKind.ADD_TO_QUEUE) }
    }

    /** This host has no add-to-playlist picker yet, so the request is announced as unavailable. */
    override fun addToPlaylist(track: LastFmDashboardTrack) {
        unavailable()
    }

    override fun copyText(text: String) = copyToDesktopClipboard(text)

    override fun openUrl(url: String) {
        openDesktopUri(url, graph::report)
    }

    override fun openGenres() = openRoute("mood_and_genres")

    override suspend fun resolveArtwork(request: LastFmArtworkRequest): String? = when (request) {
        is LastFmArtworkRequest.Track ->
            if (request.preferYouTube) {
                youTubeThumbnail(request.title, request.artist) ?: libraryCover(request.title, request.artist)
            } else {
                libraryCover(request.title, request.artist) ?: youTubeThumbnail(request.title, request.artist)
            }
        is LastFmArtworkRequest.Album ->
            libraryCover(request.title, request.artist) ?: youTubeThumbnail(request.title, request.artist)
        is LastFmArtworkRequest.Artist ->
            request.name.trim().takeIf { it.isNotEmpty() }?.let { name ->
                firstYouTubeResult(name, ContentSearchFilter.ARTISTS, ContentKind.ARTIST)?.item?.artworkUri
                    ?.takeIf { it.isNotBlank() }
            }
    }

    /**
     * A saved song with the same title, preferring one whose stored artist the scrobble credits. A scrobble's artist
     * joins every credited artist, so a stored artist is matched by containment and only breaks ties between titles.
     */
    private suspend fun libraryCover(title: String, artist: String?): String? {
        val wanted = title.trim()
        if (wanted.isEmpty()) return null
        val page = withContext(Dispatchers.IO) {
            graph.library.page(ContentQuery(RoomLibraryRepository.SONGS, text = wanted), null)
        }
        val credited = artist.orEmpty().lowercase()
        return page.items.map { it.item }
            .filter { it.title.trim().equals(wanted, ignoreCase = true) && !it.artworkUri.isNullOrBlank() }
            .minByOrNull { item -> if (item.artists.any { credited.contains(it.name.lowercase()) }) 0 else 1 }
            ?.artworkUri
    }

    /** The first song match as a 16:9 hq720 frame, which carries no letterbox bars, or its own thumbnail otherwise. */
    private suspend fun youTubeThumbnail(title: String, artist: String?): String? {
        if (title.isBlank()) return null
        val song = firstYouTubeResult(searchTerm(title, artist), ContentSearchFilter.SONGS, ContentKind.SONG)?.item
            ?: return null
        return if (song.ref.id.length == YOUTUBE_VIDEO_ID_LENGTH) {
            "https://i.ytimg.com/vi/${song.ref.id}/hq720.jpg"
        } else {
            song.artworkUri?.takeIf { it.isNotBlank() }
        }
    }

    private suspend fun firstYouTubeResult(query: String, filter: ContentSearchFilter, kind: ContentKind): ContentOccurrence? {
        if (query.isBlank()) return null
        val page = withContext(Dispatchers.IO) {
            graph.youtubeContent.page(ContentRequest(ContentProvider.YOUTUBE, ContentSurface.SEARCH, query, filter = filter), null)
        }
        return page.items.firstOrNull { it.item.ref.kind == kind }
    }

    private fun queueCommand(refusal: String, command: suspend () -> Unit) {
        graph.scope.launch {
            try {
                command()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                graph.report("Last.fm dashboard: $refusal: ${failure.javaClass.simpleName}")
            }
        }
    }

    private companion object {
        const val YOUTUBE_VIDEO_ID_LENGTH = 11
    }
}

private fun searchTerm(title: String, artist: String?): String =
    listOfNotNull(artist?.trim()?.takeIf { it.isNotEmpty() }, title.trim().takeIf { it.isNotEmpty() }).joinToString(" ")

/**
 * A search match keeps the same occurrence id each time the binding hands it out, and the queue refuses an id it
 * already holds, so every queue action gets its own occurrence.
 */
private fun ContentOccurrence.queuedCopy(): ContentOccurrence =
    ContentOccurrence("lastfm:${UUID.randomUUID()}", item, setVideoId)
