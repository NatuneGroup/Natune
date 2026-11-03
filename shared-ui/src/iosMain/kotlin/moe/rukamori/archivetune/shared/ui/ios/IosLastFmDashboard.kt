package moe.rukamori.archivetune.shared.ui.ios

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
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
import moe.rukamori.archivetune.shared.ui.menu.LocalPlaylistPickerContext
import moe.rukamori.archivetune.shared.ui.menu.PlaylistPickerDialog
import moe.rukamori.archivetune.shared.ui.navigation.ShellNavigation
import moe.rukamori.archivetune.shared.ui.settings.lastfmdashboard.LastFmArtworkRequest
import moe.rukamori.archivetune.shared.ui.settings.lastfmdashboard.LastFmDashboardBinding
import moe.rukamori.archivetune.shared.ui.settings.lastfmdashboard.LastFmDashboardHost
import moe.rukamori.archivetune.shared.ui.settings.lastfmdashboard.LastFmDashboardPage
import moe.rukamori.archivetune.shared.ui.settings.lastfmdashboard.LastFmDashboardTrack

/** The dashboard is a settings route on this host, so the settings screen mounts it without a new shared destination. */
internal const val IosLastFmDashboardRoute = "settings/lastfm/dashboard"

/**
 * The Last.fm dashboard over the graph's scrobbling account. The binding runs on the graph scope, so a load or a queue
 * action finishes even after the page closes.
 */
@Composable
internal fun IosLastFmDashboardPage(graph: IosSessionGraph, navigation: ShellNavigation, unavailable: () -> Unit) {
    val currentUnavailable by rememberUpdatedState(unavailable)
    val host = remember(graph) {
        IosLastFmDashboardHost(graph, openRoute = navigation::open, unavailable = { currentUnavailable() })
    }
    val binding = remember(graph, host) {
        LastFmDashboardBinding(
            scope = graph.scope,
            reader = graph.lastFm.dashboardReader,
            service = graph.lastFm.dashboardService,
            host = host,
        )
    }
    val snapshot by graph.settings.snapshots.collectAsState()
    LastFmDashboardPage(
        binding = binding,
        image = { url, modifier, circle ->
            IosRemoteArtwork(url, graph.artworkLoader, if (circle) modifier.clip(CircleShape) else modifier)
        },
        preferYouTubeArtwork = snapshot?.get(LastFmPreferYtThumbnailsKey) == true,
        back = { navigation.back() },
        onSignIn = { navigation.open("settings/lastfm") },
        // The settings screen already pads its box by the shell padding.
        contentPadding = PaddingValues(),
    )
    val playlistPick by host.playlistPick.collectAsState()
    val picker = LocalPlaylistPickerContext.current
    val picked = playlistPick
    if (picked != null) {
        if (picker != null) {
            PlaylistPickerDialog(
                occurrences = listOf(picked),
                context = picker,
                library = graph.library,
                onFinished = { host.playlistPick.value = null },
            )
        } else {
            host.playlistPick.value = null
            currentUnavailable()
        }
    }
}

/**
 * What the dashboard reaches on this host: the graph's YouTube catalogue for matches and thumbnails, the local library
 * for covers, the one queue, the clipboard and the browser.
 */
internal class IosLastFmDashboardHost(
    private val graph: IosSessionGraph,
    private val openRoute: (String) -> Unit,
    private val unavailable: () -> Unit,
) : LastFmDashboardHost {
    /** The YouTube match the add-to-playlist picker is open on; the page clears it when the dialog closes. */
    val playlistPick = MutableStateFlow<ContentOccurrence?>(null)

    override suspend fun findYouTubeSong(artist: String?, title: String): ContentOccurrence? =
        firstYouTubeResult(searchTerm(title, artist), ContentSearchFilter.SONGS, ContentKind.SONG)

    override fun isPlayerAvailable(): Boolean = true

    /**
     * A YouTube song loads alone and the watch-next tail is appended behind it, the same pair of calls the song
     * menu's radio row makes. A queue that could not seed the tail is a failure, not a silent solo play.
     */
    override fun startRadio(song: ContentOccurrence) {
        if (song.item.ref.provider != ContentProvider.YOUTUBE.id || song.item.ref.kind != ContentKind.SONG) {
            unavailable()
            return
        }
        queueCommand("the radio could not be started") {
            val entry = song.queuedEntry()
            graph.queue.replace(listOf(entry), entry.occurrenceId, playWhenReady = true)
            check(graph.queue.startRadioSeamlessly()) { "The radio tail could not be seeded" }
        }
    }

    override fun playNext(song: ContentOccurrence) {
        queueCommand("the song was not queued") { graph.queue.insert(listOf(song.queuedEntry()), UserQueuedKind.PLAY_NEXT) }
    }

    override fun addToQueue(song: ContentOccurrence) {
        queueCommand("the song was not queued") { graph.queue.insert(listOf(song.queuedEntry()), UserQueuedKind.ADD_TO_QUEUE) }
    }

    /** The track's YouTube match opens the picker; no match and a failed lookup both report unavailable. */
    override fun addToPlaylist(track: LastFmDashboardTrack) {
        graph.scope.launch {
            try {
                val song = findYouTubeSong(track.artist, track.title)
                if (song != null) playlistPick.value = song else unavailable()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                unavailable()
            }
        }
    }

    override fun copyText(text: String) = copyToIosClipboard(text)

    override fun openUrl(url: String) {
        openIosUri(url, graph::report)
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
        val page = withContext(Dispatchers.Default) {
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
        val page = withContext(Dispatchers.Default) {
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
                graph.report("Last.fm dashboard: $refusal: ${failure::class.simpleName}")
            }
        }
    }

    private companion object {
        const val YOUTUBE_VIDEO_ID_LENGTH = 11
    }
}

private fun searchTerm(title: String, artist: String?): String =
    listOfNotNull(artist?.trim()?.takeIf { it.isNotEmpty() }, title.trim().takeIf { it.isNotEmpty() }).joinToString(" ")
