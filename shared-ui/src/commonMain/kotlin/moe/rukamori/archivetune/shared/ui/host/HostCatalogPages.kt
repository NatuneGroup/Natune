package moe.rukamori.archivetune.shared.ui.host

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.shared.domain.library.LibraryFilter
import moe.rukamori.archivetune.shared.domain.queue.FinitePlaylistCompletion
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.ContentRequest
import moe.rukamori.archivetune.shared.domain.repository.ContentSearchFilter
import moe.rukamori.archivetune.shared.domain.repository.ContentSurface
import moe.rukamori.archivetune.shared.domain.repository.QueueStart
import moe.rukamori.archivetune.shared.domain.repository.TypedContentRepository
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveDestination
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveGraphPage
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveRoute
import moe.rukamori.archivetune.shared.ui.screens.ContentActions
import moe.rukamori.archivetune.shared.ui.screens.DetailActions
import moe.rukamori.archivetune.shared.ui.screens.DetailKind
import moe.rukamori.archivetune.shared.ui.screens.PagedContentPresenter
import moe.rukamori.archivetune.shared.ui.screens.PagedContentState
import moe.rukamori.archivetune.shared.ui.screens.SearchScope

/**
 * The online catalogue pages a host binds over its one typed repository: search, and the albums and finite playlists
 * that repository pages. Each page records the rows it shows, so a tap plays the rows the listener saw.
 *
 * Main-thread only: pages record their rows during composition and taps read them before any work leaves the thread.
 */
class CatalogPageOwners(
    val catalog: TypedContentRepository,
    val library: LibraryPageOwners,
) {
    // Insertion order is the age order, so the oldest page's rows are dropped first once the bound is reached.
    private val shown = LinkedHashMap<ContentRequest, PagedContentState>()

    internal fun record(state: PagedContentState) {
        val request = state.request ?: return
        shown.remove(request)
        shown[request] = state
        while (shown.size > MAX_SHOWN_PAGES) shown.remove(shown.keys.first())
    }

    internal fun shown(request: ContentRequest): PagedContentState? = shown[request]

    private companion object {
        /** A few pages back in the navigation stack; an older page reloads its first rows when it plays. */
        const val MAX_SHOWN_PAGES = 8
    }
}

/**
 * The Search tab and its results. Online results come from the catalogue; Local results are the library songs whose
 * title, artists or album contain the query. [recognition] opens music recognition, or is null where the host has none.
 */
@Composable
fun hostSearchPage(owners: CatalogPageOwners, route: ArchiveRoute, recognition: (() -> Unit)?): ArchiveGraphPage {
    val request = if (route.destination == ArchiveDestination.SEARCH_RESULTS) route.contentRequest() else null
    var query by remember(route.value) { mutableStateOf(route.searchQuery.orEmpty()) }
    var scope by remember(route.value) {
        mutableStateOf(if (request?.provider == ContentProvider.LOCAL) SearchScope.Local else SearchScope.Online)
    }
    var page = PagedContentState()
    var loadMore: () -> Unit = {}
    var retry: () -> Unit = {}
    when {
        request == null -> Unit
        request.provider == ContentProvider.LOCAL -> {
            var attempt by remember(request) { mutableStateOf(0) }
            page = localSearchPage(owners, request, attempt)
            retry = { attempt += 1 }
        }
        request.provider == owners.catalog.provider -> {
            val presenter = catalogPresenter(owners, request)
            page = presenter.state.collectAsState().value
            loadMore = presenter::loadMore
            retry = presenter::retry
        }
        // A provider this host has no catalogue for, such as a restored Spotify search, cannot be answered here.
        else -> page = PagedContentState(request = request, failed = true)
    }
    SideEffect { owners.record(page) }
    return ArchiveGraphPage.Search(
        query = query,
        scope = scope,
        provider = request?.provider?.takeIf { it != ContentProvider.LOCAL } ?: owners.catalog.provider,
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

/**
 * An album or finite playlist of the catalogue's provider, or null when the route names something else, so the host
 * keeps its boundary. Endless radio and mix ids fail the page: they need a watch-next transport, not a playlist browse.
 */
@Composable
fun hostCatalogDetailPage(
    owners: CatalogPageOwners,
    route: ArchiveRoute,
    more: (ContentOccurrence) -> Unit,
): ArchiveGraphPage? {
    val provider = route.arguments["provider"]?.let { id -> ContentProvider.entries.firstOrNull { it.id == id } }
        ?: ContentProvider.YOUTUBE
    if (provider != owners.catalog.provider) return null
    val kind: DetailKind
    val request: ContentRequest
    when (route.destination) {
        ArchiveDestination.ALBUM -> {
            val id = route.arguments["albumId"]?.takeIf { it.isNotBlank() } ?: return null
            kind = DetailKind.Album
            request = ContentRequest(provider, ContentSurface.ALBUM, parent = ContentRef(provider.id, ContentKind.ALBUM, id))
        }
        ArchiveDestination.PLAYLIST -> {
            if (route.arguments["playlistType"] != "online_playlist") return null
            val id = route.arguments["playlistId"]?.takeIf { it.isNotBlank() } ?: return null
            kind = DetailKind.Playlist
            request = ContentRequest(provider, ContentSurface.PLAYLIST, parent = ContentRef(provider.id, ContentKind.PLAYLIST, id))
        }
        else -> return null
    }
    val presenter = catalogPresenter(owners, request)
    val page by presenter.state.collectAsState()
    SideEffect { owners.record(page) }
    return ArchiveGraphPage.Detail(
        kind = kind,
        header = if (kind == DetailKind.Album) albumHeader(request, page) else null,
        page = page,
        actions = DetailActions(
            content = ContentActions(
                open = { row ->
                    hostPlayCatalogOccurrence(owners, request, QueueStart(row.item.ref, row.occurrenceId, row.setVideoId))
                },
                more = more,
                loadMore = presenter::loadMore,
                retry = presenter::retry,
            ),
            back = {},
            playAll = { playCatalogList(owners, request, shuffled = false, startOccurrenceId = null) },
            shuffle = { playCatalogList(owners, request, shuffled = true, startOccurrenceId = null) },
            toggleLibrary = null,
            more = null,
        ),
    )
}

/**
 * Plays a row of a catalogue page or a search result. Returns false when [collection] is not one of these pages, so the
 * host plays it through its library owner instead. A search result plays on its own, because this queue has no radio
 * to follow it with; an album or playlist row plays inside that complete list, read to its end first.
 */
fun hostPlayCatalogOccurrence(owners: CatalogPageOwners, collection: ContentRequest, start: QueueStart): Boolean {
    val library = owners.library
    return when {
        collection.surface == ContentSurface.SEARCH -> {
            val rows = owners.shown(collection)?.items.orEmpty()
            val row = rows.firstOrNull { it.occurrenceId == start.occurrenceId } ?: rows.firstOrNull { it.item.ref == start.item }
            if (row == null) {
                library.report("the search result is no longer on its page")
            } else {
                val entry = row.queuedEntry()
                library.scope.launch {
                    try {
                        library.queue.replace(listOf(entry), entry.occurrenceId, playWhenReady = true)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        library.report("the search result could not be played")
                    }
                }
            }
            true
        }
        collection.provider == owners.catalog.provider &&
            (collection.surface == ContentSurface.ALBUM || collection.surface == ContentSurface.PLAYLIST) -> {
            playCatalogList(owners, collection, shuffled = false, startOccurrenceId = start.occurrenceId, startRef = start.item)
            true
        }
        else -> false
    }
}

@Composable
private fun catalogPresenter(owners: CatalogPageOwners, request: ContentRequest): PagedContentPresenter {
    val presenter = remember(request) { PagedContentPresenter(owners.catalog, owners.library.scope).also { it.open(request) } }
    DisposableEffect(presenter) { onDispose { presenter.close() } }
    return presenter
}

@Composable
private fun localSearchPage(owners: CatalogPageOwners, request: ContentRequest, attempt: Int): PagedContentState {
    val songs by remember(attempt) { owners.library.content.chipPage(LibraryFilter.SONGS) }
        .collectAsState(PagedContentState(loading = true))
    val needle = request.text?.trim()?.lowercase().orEmpty()
    val matches = if (needle.isEmpty()) emptyList() else songs.items.filter { row -> row.item.matches(needle) }
    return songs.copy(items = matches, request = request, continuation = null)
}

private fun ContentItem.matches(needle: String): Boolean =
    title.lowercase().contains(needle) ||
        subtitle.lowercase().contains(needle) ||
        album?.lowercase()?.contains(needle) == true ||
        artists.any { it.name.lowercase().contains(needle) }

/** The album's own name and credits as its tracks carry them, or none while no track names the album. */
private fun albumHeader(request: ContentRequest, page: PagedContentState): ContentItem? {
    val parent = request.parent ?: return null
    val first = page.items.firstOrNull()?.item ?: return null
    val title = first.album?.takeIf { it.isNotBlank() } ?: return null
    return ContentItem(
        ref = parent,
        title = title,
        subtitle = first.artists.joinToString(", ") { it.name },
        artworkUri = first.artworkUri,
        artists = first.artists,
    )
}

/**
 * Reads the whole list, from the rows the page already holds, within the shared finite-completion bounds, then hands
 * it to the queue. A list that repeats a page or outgrows the bounds is incomplete and is not played at all.
 */
private fun playCatalogList(
    owners: CatalogPageOwners,
    request: ContentRequest,
    shuffled: Boolean,
    startOccurrenceId: String?,
    startRef: ContentRef? = null,
) {
    val library = owners.library
    val held = owners.shown(request)?.takeIf { it.initialized && !it.failed }
    library.scope.launch {
        val entries = try {
            withTimeoutOrNull(FinitePlaylistCompletion.TIMEOUT_MS) { completeList(owners.catalog, request, held) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (entries.isNullOrEmpty()) return@launch library.report("the list could not be read to its end, so it was not played")
        val order = if (shuffled) entries.shuffled() else entries
        val startId = startOccurrenceId?.takeIf { id -> order.any { it.occurrenceId == id } }
            ?: startRef?.let { ref -> order.firstOrNull { it.item.ref == ref }?.occurrenceId }
            ?: order.first().occurrenceId
        try {
            library.queue.replace(order, startId, playWhenReady = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            library.report("the list could not be played")
        }
    }
}

private suspend fun completeList(
    catalog: TypedContentRepository,
    request: ContentRequest,
    held: PagedContentState?,
): List<ContentOccurrence>? {
    val rows = ArrayList<ContentOccurrence>()
    var next: String?
    if (held != null) {
        rows += held.items
        next = held.continuation
    } else {
        val first = catalog.page(request, null)
        rows += first.items
        next = first.continuation
    }
    val consumed = HashSet<String>()
    var requests = 0
    while (next != null) {
        requests += 1
        if (requests > FinitePlaylistCompletion.MAX_REQUESTS || !consumed.add(next)) return null
        val page = catalog.page(request, next)
        rows += page.items
        next = page.continuation
    }
    return rows.takeIf { list -> list.map { it.occurrenceId }.toSet().size == list.size }
}
