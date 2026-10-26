/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.desktop.integration.DesktopStrings
import moe.rukamori.archivetune.shared.domain.library.DefaultLibraryFilterOrder
import moe.rukamori.archivetune.shared.domain.library.LibraryDefaults
import moe.rukamori.archivetune.shared.domain.library.LibraryFilter
import moe.rukamori.archivetune.shared.domain.library.LibrarySortState
import moe.rukamori.archivetune.shared.domain.library.librarySortState
import moe.rukamori.archivetune.shared.domain.library.toLibraryFilterOrder
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.ContentRequest
import moe.rukamori.archivetune.shared.domain.repository.ContentSurface
import moe.rukamori.archivetune.shared.domain.repository.MyTopPeriod as DomainMyTopPeriod
import moe.rukamori.archivetune.shared.domain.repository.QueueStart
import moe.rukamori.archivetune.shared.domain.settings.HideTop50CardKey
import moe.rukamori.archivetune.shared.domain.settings.LibraryChipOrderKey
import moe.rukamori.archivetune.shared.domain.settings.PlaylistEditLockKey
import moe.rukamori.archivetune.shared.domain.settings.PlaylistSortDescendingKey
import moe.rukamori.archivetune.shared.domain.settings.PlaylistSortTypeKey
import moe.rukamori.archivetune.shared.domain.settings.TopSize
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveDestination
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveGraphPage
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveRoute
import moe.rukamori.archivetune.shared.ui.screens.ContentActions
import moe.rukamori.archivetune.shared.ui.screens.DetailActions
import moe.rukamori.archivetune.shared.ui.screens.DetailKind
import moe.rukamori.archivetune.shared.ui.screens.PagedContentState
import moe.rukamori.archivetune.shared.ui.screens.PlaylistCollectionActions
import moe.rukamori.archivetune.shared.ui.screens.PlaylistCollectionUi
import moe.rukamori.archivetune.shared.ui.screens.MyTopPeriod as UiMyTopPeriod
import moe.rukamori.archivetune.shared.ui.screens.PlaylistDetailControl

/** The route id of the liked songs in `auto_playlist/liked`. The owner's pseudo id is [LIKED_PLAYLIST_ID]. */
private const val LIKED_ROUTE_ID = "liked"

/** The My Top size the baseline uses when no size is stored. */
private const val DEFAULT_MY_TOP_SIZE = 50

/**
 * The Library, Playlists and playlist routes, bound to the one desktop owner. Every page reads and writes through the
 * owner in the session graph, so a row on a page and the queue see the same complete list. A route that no owner on
 * this host can answer keeps the explicit host boundary. The key keeps each route's own page state apart.
 */
@Composable
internal fun desktopGraphPage(
    graph: DesktopSessionGraph,
    menu: DesktopItemMenuState,
    creation: DesktopPlaylistCreation,
    route: ArchiveRoute,
): ArchiveGraphPage = key(route.value) {
    when (route.destination) {
        ArchiveDestination.LIBRARY -> desktopLibraryPage(graph)
        ArchiveDestination.PLAYLISTS -> desktopPlaylistsPage(graph, creation)
        ArchiveDestination.PLAYLIST -> desktopPlaylistPage(graph, menu, route)
        else -> ArchiveGraphPage.Host
    }
}

/** One Library section. The first section that has an owner opens first, so the page never starts on an unavailable one. */
@Composable
private fun desktopLibraryPage(graph: DesktopSessionGraph): ArchiveGraphPage {
    val content = graph.libraryContent
    val snapshot by graph.settings.snapshots.collectAsState()
    var chosen by remember { mutableStateOf<LibraryFilter?>(null) }
    var attempt by remember { mutableStateOf(0) }
    val order = snapshot?.get(LibraryChipOrderKey)?.toLibraryFilterOrder() ?: DefaultLibraryFilterOrder
    val filter = chosen ?: order.firstOrNull { it != LibraryFilter.LIBRARY } ?: LibraryFilter.SONGS
    val page by remember(filter, attempt) { content.chipPage(filter) }.collectAsState(PagedContentState(loading = true))
    // The baseline card shows the stored My Top size, which defaults to 50. The card's hide preference removes it, so the entry goes too.
    val myTopSize = snapshot?.takeUnless { it.get(HideTop50CardKey) == true }
        ?.let { current -> current.get(TopSize)?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: DEFAULT_MY_TOP_SIZE }
    return ArchiveGraphPage.Library(
        filter = filter,
        order = order,
        filterChange = { chosen = it },
        page = page,
        // A section's list is complete when it loads, so there is no further page to request.
        loadMore = {},
        retry = { attempt += 1 },
        myTopSize = myTopSize,
    )
}

/** The local playlists with their real counts, sorted by the one playlist-list order the Library section also reads. */
@Composable
private fun desktopPlaylistsPage(graph: DesktopSessionGraph, creation: DesktopPlaylistCreation): ArchiveGraphPage {
    val content = graph.libraryContent
    val snapshot by graph.settings.snapshots.collectAsState()
    var attempt by remember { mutableStateOf(0) }
    val rows by remember(attempt) { content.playlistRows() }.collectAsState(null)
    var query by remember { mutableStateOf("") }
    var grid by remember { mutableStateOf(false) }
    var showHidden by remember { mutableStateOf(false) }
    val sort = snapshot?.librarySortState(PlaylistSortTypeKey, PlaylistSortDescendingKey, LibraryDefaults.playlistSort)
        ?: LibraryDefaults.playlistSort
    val locked = snapshot?.get(PlaylistEditLockKey) ?: true
    val state = PlaylistCollectionUi(
        rows = rows.orEmpty(),
        query = query,
        sort = sort.type,
        descending = sort.descending,
        showHidden = showHidden,
        locked = locked,
        grid = grid,
        loading = rows == null || snapshot == null,
    )
    val actions = PlaylistCollectionActions(
        // The host supplies back, the tags route and the playlist route for this page, so these are not used here.
        back = {},
        query = { query = it },
        sort = { type, descending -> graph.scope.launch { content.setPlaylistListSort(LibrarySortState(type, descending)) } },
        showHidden = { showHidden = !showHidden },
        // No tag rows exist on this host, so no tag filter can be chosen.
        filterTag = {},
        grid = { grid = !grid },
        lock = { graph.scope.launch { content.setPlaylistLocked(!locked) } },
        create = { creation.show() },
        manageTags = {},
        open = {},
        more = { graph.report("playlist menus are not connected on the desktop host") },
        assignTags = { graph.report("playlist tags are not connected on the desktop host") },
        exportCsv = { graph.report("playlist export is not connected on the desktop host") },
        move = { _, _, _ -> graph.report("the custom playlist order is not stored on this host") },
        retry = { attempt += 1 },
    )
    return ArchiveGraphPage.Playlists(state, actions)
}

/** The playlist routes the owner answers: a local playlist and the liked songs. Every other playlist stays unbound. */
@Composable
private fun desktopPlaylistPage(graph: DesktopSessionGraph, menu: DesktopItemMenuState, route: ArchiveRoute): ArchiveGraphPage {
    val id = route.arguments["playlistId"] ?: return ArchiveGraphPage.Host
    return when (route.arguments["playlistType"]) {
        "local_playlist" -> desktopLocalPlaylistPage(graph, menu, id)
        "auto_playlist" -> if (id == LIKED_ROUTE_ID) desktopLikedPage(graph, menu) else ArchiveGraphPage.Host
        "top_playlist" -> {
            val size = id.toIntOrNull()?.takeIf { it > 0 }
            if (size == null) ArchiveGraphPage.Host else desktopTopPage(graph, menu, size)
        }
        else -> ArchiveGraphPage.Host
    }
}

@Composable
private fun desktopLocalPlaylistPage(graph: DesktopSessionGraph, menu: DesktopItemMenuState, playlistId: String): ArchiveGraphPage {
    val content = graph.libraryContent
    var attempt by remember { mutableStateOf(0) }
    val view by remember(playlistId, attempt) { content.localPlaylist(playlistId) }.collectAsState(null)
    val request = ContentRequest(
        provider = ContentProvider.LOCAL,
        surface = ContentSurface.LIBRARY_PLAYLIST_ITEMS,
        parent = ContentRef(ContentProvider.LOCAL.id, ContentKind.PLAYLIST, playlistId),
    )
    val loaded = view
    val page = when {
        loaded == null -> PagedContentState(request = request, loading = true)
        // A playlist the library does not hold is a failed page, never an empty one.
        loaded.header == null -> PagedContentState(request = request, failed = true)
        else -> loaded.page.toPagedState(request)
    }
    // A playlist the library does not hold has no order to show, so it offers no sort, lock or move control.
    val control = loaded?.takeIf { it.header != null }?.let { current ->
        PlaylistDetailControl.LocalSongs(
            sortType = current.sort.type,
            descending = current.sort.descending,
            editable = true,
            locked = current.locked,
            reorderEnabled = true,
            onSortChange = { type, descending ->
                graph.scope.launch { content.setPlaylistSort(playlistId, LibrarySortState(type, descending)) }
            },
            onLockedChange = { next -> graph.scope.launch { content.setPlaylistLocked(next) } },
            move = { row, neighbour, after ->
                graph.scope.launch { content.movePlaylistItem(playlistId, row.occurrenceId, neighbour.occurrenceId, after) }
            },
        )
    }
    return ArchiveGraphPage.Detail(
        kind = DetailKind.Playlist,
        header = loaded?.header,
        page = page,
        actions = playlistActions(graph, menu, request) { attempt += 1 },
        control = control,
    )
}

/**
 * One My Top list of [size] songs. The period control is the page's only state: the list is read again when the period
 * changes, and the same request feeds Play, so playback follows the list the page shows.
 */
@Composable
private fun desktopTopPage(graph: DesktopSessionGraph, menu: DesktopItemMenuState, size: Int): ArchiveGraphPage {
    val content = graph.libraryContent
    var period by remember { mutableStateOf(UiMyTopPeriod.ALL_TIME) }
    var attempt by remember { mutableStateOf(0) }
    val request = content.topRequest(period.domain(), size)
    val page by remember(request, attempt) { content.topPage(request) }
        .collectAsState(PagedContentState(request = request, loading = true))
    return ArchiveGraphPage.Detail(
        kind = DetailKind.Playlist,
        header = ContentItem(
            ContentRef(ContentProvider.LOCAL.id, ContentKind.PLAYLIST, "top_playlist/$size"),
            "${DesktopStrings["my_top"]} $size",
        ),
        page = page,
        actions = playlistActions(graph, menu, request) { attempt += 1 },
        control = PlaylistDetailControl.TopPeriod(period = period, onPeriodChange = { period = it }),
    )
}

/** The owner's period for a control period. Both enums keep the same names, and this mapping keeps that explicit. */
private fun UiMyTopPeriod.domain(): DomainMyTopPeriod = when (this) {
    UiMyTopPeriod.ALL_TIME -> DomainMyTopPeriod.ALL_TIME
    UiMyTopPeriod.DAY -> DomainMyTopPeriod.DAY
    UiMyTopPeriod.WEEK -> DomainMyTopPeriod.WEEK
    UiMyTopPeriod.MONTH -> DomainMyTopPeriod.MONTH
    UiMyTopPeriod.YEAR -> DomainMyTopPeriod.YEAR
}

/** The liked songs, which the baseline auto playlist shows with the shared auto-playlist order. */
@Composable
private fun desktopLikedPage(graph: DesktopSessionGraph, menu: DesktopItemMenuState): ArchiveGraphPage {
    val content = graph.libraryContent
    var attempt by remember { mutableStateOf(0) }
    val liked by remember(attempt) { content.likedSongs() }.collectAsState(null)
    val request = ContentRequest(
        provider = ContentProvider.LOCAL,
        surface = ContentSurface.LIBRARY_PLAYLIST_ITEMS,
        parent = ContentRef(ContentProvider.LOCAL.id, ContentKind.PLAYLIST, LIKED_PLAYLIST_ID),
    )
    val loaded = liked
    val page = if (loaded == null) PagedContentState(request = request, loading = true) else loaded.page.toPagedState(request)
    val control = loaded?.let { current ->
        PlaylistDetailControl.AutoSongs(
            sortType = current.sort.type,
            descending = current.sort.descending,
            onSortChange = { type, descending ->
                graph.scope.launch { content.setAutoSongSort(LibrarySortState(type, descending)) }
            },
        )
    }
    return ArchiveGraphPage.Detail(
        kind = DetailKind.Playlist,
        header = ContentItem(ContentRef(ContentProvider.LOCAL.id, ContentKind.PLAYLIST, LIKED_PLAYLIST_ID), DesktopStrings["liked_songs"]),
        page = page,
        actions = playlistActions(graph, menu, request) { attempt += 1 },
        control = control,
    )
}

/**
 * The row and list actions of a playlist page. A row plays from the owner's complete list in its owner order, starting
 * at the chosen row, and its overflow opens the one row menu, which queues the row. The page's own back action and
 * playlist menus belong to the host, so they are not offered here.
 */
private fun playlistActions(
    graph: DesktopSessionGraph,
    menu: DesktopItemMenuState,
    request: ContentRequest,
    retry: () -> Unit,
): DetailActions =
    DetailActions(
        content = ContentActions(
            open = { row ->
                desktopPlayOccurrence(
                    graph,
                    request,
                    QueueStart(item = row.item.ref, occurrenceId = row.occurrenceId, setVideoId = row.setVideoId),
                )
            },
            more = { occurrence -> menu.open(occurrence) },
            loadMore = {},
            retry = retry,
        ),
        back = {},
        playAll = { desktopPlayList(graph, request, shuffled = false) },
        shuffle = { desktopPlayList(graph, request, shuffled = true) },
        toggleLibrary = null,
        more = null,
    )

/**
 * Plays [start] inside the complete list [collection] names, in the order the owner shows it, so Next follows that
 * order. Refused with one line when there is no list, the owner cannot answer, or the item is not in the list.
 */
internal fun desktopPlayOccurrence(graph: DesktopSessionGraph, collection: ContentRequest?, start: QueueStart) {
    graph.scope.launch {
        val request = collection ?: return@launch graph.report("this item has no list to play from on the desktop host")
        val entries = graph.libraryContent.currentList(request)?.items
        val startId = entries?.let { list ->
            start.occurrenceId?.takeIf { id -> list.any { it.occurrenceId == id } }
                ?: list.firstOrNull { it.item.ref == start.item }?.occurrenceId
        }
        if (entries == null || startId == null) return@launch graph.report("the list does not hold this item")
        graph.queue.replace(entries, startId, playWhenReady = true)
    }
}

/** Plays a whole list from its first row, or from a shuffled order when [shuffled] is set. Refused when no rows are held. */
internal fun desktopPlayList(graph: DesktopSessionGraph, collection: ContentRequest, shuffled: Boolean) {
    graph.scope.launch {
        val entries = graph.libraryContent.currentList(collection)?.items.orEmpty()
        if (entries.isEmpty()) return@launch graph.report("nothing to play: the list is unavailable or empty")
        val order = if (shuffled) entries.shuffled() else entries
        graph.queue.replace(order, order.first().occurrenceId, playWhenReady = true)
    }
}
