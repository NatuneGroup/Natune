package moe.rukamori.archivetune.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.ContentRequest
import moe.rukamori.archivetune.shared.domain.repository.ContentSearchFilter
import moe.rukamori.archivetune.shared.domain.settings.DefaultSearchSourceKey
import moe.rukamori.archivetune.shared.ui.host.CatalogPageOwners
import moe.rukamori.archivetune.shared.ui.host.hostSearchPage
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveDestination
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveGraphPage
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveRoute
import moe.rukamori.archivetune.shared.ui.screens.PagedContentState
import moe.rukamori.archivetune.shared.ui.screens.SearchScope
import moe.rukamori.archivetune.spotify.SpotifyMapper
import moe.rukamori.archivetune.spotify.models.SpotifyAlbum
import moe.rukamori.archivetune.spotify.models.SpotifyArtist
import moe.rukamori.archivetune.spotify.models.SpotifyPlaylist
import moe.rukamori.archivetune.spotify.models.SpotifySearchResult

/** Chooses YouTube or Spotify search the way Android's stored `DefaultSearchSourceKey` does. */
@Composable
internal fun desktopSearchPage(
    graph: DesktopSessionGraph,
    owners: CatalogPageOwners,
    spotify: DesktopSpotifyAccounts,
    route: ArchiveRoute,
    recognition: (() -> Unit)?,
): ArchiveGraphPage {
    val request = if (route.destination == ArchiveDestination.SEARCH_RESULTS) route.contentRequest() else null
    val snapshot by graph.settings.snapshots.collectAsState()
    val preferredSpotify = snapshot?.get(DefaultSearchSourceKey) == "SPOTIFY"
    return when {
        request?.provider == ContentProvider.SPOTIFY_CATALOG || (request == null && preferredSpotify) ->
            desktopSpotifySearchPage(graph, spotify, route, recognition)
        request?.provider == ContentProvider.LOCAL && preferredSpotify ->
            (hostSearchPage(owners, route, recognition) as ArchiveGraphPage.Search)
                .copy(provider = ContentProvider.SPOTIFY_CATALOG)
        else -> hostSearchPage(owners, route, recognition)
    }
}

/** Spotify search results, paged by the same returned-count rule as `SpotifySearchViewModel`. */
@Composable
private fun desktopSpotifySearchPage(
    graph: DesktopSessionGraph,
    spotify: DesktopSpotifyAccounts,
    route: ArchiveRoute,
    recognition: (() -> Unit)?,
): ArchiveGraphPage {
    val request = if (route.destination == ArchiveDestination.SEARCH_RESULTS) route.contentRequest() else null
    var query by remember(route.value) { mutableStateOf(route.searchQuery.orEmpty()) }
    var scope by remember(route.value) { mutableStateOf(SearchScope.Online) }
    var page = PagedContentState(request = request)
    var loadMore: () -> Unit = {}
    var retry: () -> Unit = {}
    if (request != null) {
        val presenter = remember(request) { DesktopSpotifySearchPresenter(spotify, request, graph.scope) }
        DisposableEffect(presenter) { onDispose { presenter.close() } }
        page = presenter.state.collectAsState().value
        loadMore = presenter::loadMore
        retry = presenter::retry
    }
    return ArchiveGraphPage.Search(
        query = query,
        scope = scope,
        provider = ContentProvider.SPOTIFY_CATALOG,
        page = page,
        queryChange = { query = it },
        scopeChange = { scope = it },
        voice = null,
        recognition = recognition,
        loadMore = loadMore,
        retry = retry,
        filter = request?.filter ?: ContentSearchFilter.ALL,
    )
}

private class DesktopSpotifySearchPresenter(
    private val spotify: DesktopSpotifyAccounts,
    private val request: ContentRequest,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(PagedContentState(request = request))
    val state = mutableState.asStateFlow()
    private var pageJob: Job? = null
    private var nextOffset = 0

    init {
        loadPage(reset = true)
    }

    fun loadMore() {
        if (mutableState.value.continuation != null) loadPage(reset = false)
    }

    fun retry() {
        loadPage(reset = mutableState.value.items.isEmpty())
    }

    fun close() {
        pageJob?.cancel()
        pageJob = null
        mutableState.value = mutableState.value.copy(loading = false)
    }

    private fun loadPage(reset: Boolean) {
        val query = request.text?.trim().orEmpty()
        if (pageJob != null || query.isEmpty()) return
        if (reset) {
            nextOffset = 0
            mutableState.value = PagedContentState(request = request, loading = true)
        } else {
            mutableState.value = mutableState.value.copy(loading = true, failed = false)
        }
        pageJob = scope.launch {
            try {
                val page = spotify.search(query = query, limit = SPOTIFY_SEARCH_PAGE_SIZE, offset = nextOffset)
                val pageItems = page.toOccurrences(spotify, request.filter)
                val merged = if (reset) pageItems else (mutableState.value.items + pageItems).distinctBy(ContentOccurrence::occurrenceId)
                val returnedCount = listOfNotNull(page.tracks, page.albums, page.artists, page.playlists)
                    .maxOfOrNull { it.items.size } ?: 0
                val total = listOfNotNull(page.tracks, page.albums, page.artists, page.playlists)
                    .maxOfOrNull { it.total } ?: 0
                nextOffset += returnedCount
                val hasMore = returnedCount > 0 && nextOffset < total
                mutableState.value = mutableState.value.copy(
                    items = merged,
                    continuation = nextOffset.takeIf { hasMore }?.toString(),
                    initialized = true,
                    loading = false,
                    failed = false,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(
                    continuation = null,
                    initialized = true,
                    loading = false,
                    failed = true,
                )
            } finally {
                pageJob = null
            }
        }
    }
}

private fun SpotifySearchResult.toOccurrences(
    spotify: DesktopSpotifyAccounts,
    filter: ContentSearchFilter,
): List<ContentOccurrence> = buildList {
    if (filter == ContentSearchFilter.ALL || filter == ContentSearchFilter.SONGS) {
        tracks?.items.orEmpty().forEach { if (it.id.isNotBlank()) add(spotify.searchTrackRow(it)) }
    }
    if (filter == ContentSearchFilter.ALL || filter == ContentSearchFilter.ALBUMS) {
        albums?.items.orEmpty().forEach { if (it.id.isNotBlank()) add(it.searchOccurrence()) }
    }
    if (filter == ContentSearchFilter.ALL || filter == ContentSearchFilter.ARTISTS) {
        artists?.items.orEmpty().forEach { if (it.id.isNotBlank()) add(it.searchOccurrence()) }
    }
    if (filter == ContentSearchFilter.ALL || filter == ContentSearchFilter.PLAYLISTS ||
        filter == ContentSearchFilter.FEATURED_PLAYLISTS || filter == ContentSearchFilter.COMMUNITY_PLAYLISTS
    ) {
        playlists?.items.orEmpty().forEach { if (it.id.isNotBlank()) add(it.searchOccurrence()) }
    }
}.distinctBy(ContentOccurrence::occurrenceId)

private fun SpotifyAlbum.searchOccurrence(): ContentOccurrence = ContentOccurrence(
    "spotify:search:album:$id",
    ContentItem(
        ref = ContentRef(ContentProvider.SPOTIFY_CATALOG.id, ContentKind.ALBUM, id),
        title = name,
        subtitle = artists.joinToString(", ") { it.name },
        artworkUri = images.maxByOrNull { it.width ?: 0 }?.url,
    ),
)

private fun SpotifyArtist.searchOccurrence(): ContentOccurrence = ContentOccurrence(
    "spotify:search:artist:$id",
    ContentItem(
        ref = ContentRef(ContentProvider.SPOTIFY_CATALOG.id, ContentKind.ARTIST, id),
        title = name,
        subtitle = genres.take(3).joinToString(", "),
        artworkUri = SpotifyMapper.getArtistThumbnail(this),
    ),
)

private fun SpotifyPlaylist.searchOccurrence(): ContentOccurrence = ContentOccurrence(
    "spotify:search:playlist:$id",
    ContentItem(
        ref = ContentRef(ContentProvider.SPOTIFY_CATALOG.id, ContentKind.PLAYLIST, id),
        title = name,
        subtitle = owner?.displayName.orEmpty(),
        artworkUri = SpotifyMapper.getPlaylistThumbnail(this),
    ),
)

private const val SPOTIFY_SEARCH_PAGE_SIZE = 20
