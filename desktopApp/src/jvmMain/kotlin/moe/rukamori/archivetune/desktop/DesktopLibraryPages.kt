package moe.rukamori.archivetune.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.text.DateFormat
import java.util.Date
import java.util.TimeZone
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.desktop.integration.DesktopStrings
import moe.rukamori.archivetune.shared.domain.library.LibraryDefaults
import moe.rukamori.archivetune.shared.domain.library.LibrarySortState
import moe.rukamori.archivetune.shared.domain.library.librarySortState
import moe.rukamori.archivetune.shared.domain.repository.ContentRequest
import moe.rukamori.archivetune.shared.domain.repository.QueueStart
import moe.rukamori.archivetune.shared.domain.settings.PlaylistEditLockKey
import moe.rukamori.archivetune.shared.domain.settings.PlaylistSortDescendingKey
import moe.rukamori.archivetune.shared.domain.settings.PlaylistSortTypeKey
import moe.rukamori.archivetune.shared.ui.host.hostCatalogDetailPage
import moe.rukamori.archivetune.shared.ui.host.hostHistoryPage
import moe.rukamori.archivetune.shared.ui.host.hostLibraryPage
import moe.rukamori.archivetune.shared.ui.host.hostPlayCatalogOccurrence
import moe.rukamori.archivetune.shared.ui.host.hostPlayOccurrence
import moe.rukamori.archivetune.shared.ui.host.hostPlaylistPage
import moe.rukamori.archivetune.shared.ui.host.hostSearchPage
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveDestination
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveGraphPage
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveRoute
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveRoutes
import moe.rukamori.archivetune.shared.ui.screens.PlaylistCollectionActions
import moe.rukamori.archivetune.shared.ui.screens.PlaylistCollectionUi
import moe.rukamori.archivetune.shared.ui.screens.PlaylistTagUi

/**
 * The Library, Playlists and playlist routes, bound to the one desktop owner. Every page reads and writes through the
 * owner in the session graph, so a row on a page and the queue see the same complete list. Search, YouTube albums and
 * finite YouTube playlists read the graph's catalogue. History reads the local listening history. The recognition routes read the window's recognition owner. A route that no owner on this host can answer keeps the explicit host boundary. The
 * key keeps each route's own page state apart.
 */
@Composable
internal fun desktopGraphPage(
    graph: DesktopSessionGraph,
    menu: DesktopItemMenuState,
    creation: DesktopPlaylistCreation,
    recognition: DesktopRecognitionMount,
    playlistMenus: DesktopPlaylistMenuState,
    route: ArchiveRoute,
    openRoute: (String) -> Unit,
): ArchiveGraphPage = key(route.value) {
    when (route.destination) {
        ArchiveDestination.LIBRARY -> desktopLibraryPage(graph, openRoute)
        ArchiveDestination.SEARCH, ArchiveDestination.SEARCH_RESULTS ->
            hostSearchPage(graph.catalogPages, route) { openRoute(ArchiveRoutes.musicRecognition()) }
        ArchiveDestination.ALBUM -> hostCatalogDetailPage(graph.catalogPages, route) { occurrence -> menu.open(occurrence) }
            ?: ArchiveGraphPage.Host
        ArchiveDestination.PLAYLISTS -> desktopPlaylistsPage(graph, creation, playlistMenus)
        ArchiveDestination.TAGS -> desktopTagsPage(graph, playlistMenus)
        ArchiveDestination.PLAYLIST -> desktopPlaylistPage(graph, menu, route)
        ArchiveDestination.MUSIC_RECOGNITION -> desktopRecognitionPage(recognition, route)
        ArchiveDestination.MUSIC_RECOGNITION_DETAILS -> desktopRecognitionDetailsPage(recognition, route)
        ArchiveDestination.LISTEN_TOGETHER -> desktopListenTogetherPage(graph, openRoute)
        ArchiveDestination.HISTORY -> desktopHistoryPage(graph, menu)
        ArchiveDestination.NEWS -> desktopNewsPage()
        ArchiveDestination.ARTICLE -> desktopArticlePage(route.arguments.getValue("newsId"))
        ArchiveDestination.PODCASTS -> desktopPodcastsPage()
        ArchiveDestination.PODCAST -> desktopPodcastPage(route.arguments.getValue("browseId"))
        ArchiveDestination.LOCAL_SONGS -> desktopLocalSongsPage(graph, menu)
        ArchiveDestination.DOWNLOADS -> desktopDownloadsPage(graph)
        ArchiveDestination.STATISTICS -> desktopStatisticsPage(graph, menu, openRoute)
        ArchiveDestination.RECAP -> desktopRecapPage(graph, menu)
        ArchiveDestination.TRACK_INFO -> desktopTrackInfoPage(graph, route.arguments.getValue("trackId"))
        ArchiveDestination.MEDIA_INFO -> desktopMediaInfoPage(graph, route.arguments.getValue("mediaId"))
        else -> ArchiveGraphPage.Host
    }
}

/** One Library section, from the shared page over the graph's owners. */
@Composable
private fun desktopLibraryPage(graph: DesktopSessionGraph, openRoute: (String) -> Unit): ArchiveGraphPage =
    hostLibraryPage(graph.pageOwners, DesktopStrings["library_top_mixes_unavailable"], openRoute)

/** The local listening history, on the user's calendar days and clock. */
@Composable
private fun desktopHistoryPage(graph: DesktopSessionGraph, menu: DesktopItemMenuState): ArchiveGraphPage {
    // DateFormat is not thread-safe. It formats only while the page composes, on the window's UI thread.
    val format = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    return hostHistoryPage(
        owners = graph.pageOwners,
        offsetMsAt = { epochMs -> TimeZone.getDefault().getOffset(epochMs).toLong() },
        nowMs = System::currentTimeMillis,
        formatTime = { epochMs -> format.format(Date(epochMs)) },
        more = { occurrence -> menu.open(occurrence) },
    )
}

/** The local playlists with their real counts, sorted by the one playlist-list order the Library section also reads. */
@Composable
private fun desktopPlaylistsPage(
    graph: DesktopSessionGraph,
    creation: DesktopPlaylistCreation,
    menus: DesktopPlaylistMenuState,
): ArchiveGraphPage {
    val content = graph.libraryContent
    val snapshot by graph.settings.snapshots.collectAsState()
    var attempt by remember { mutableStateOf(0) }
    val rows by remember(attempt) { graph.taggedPlaylistRows() }.collectAsState(null)
    val tags by remember(attempt) { graph.playlistTags.observeTags() }.collectAsState(emptyList())
    var filterTagIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var query by remember { mutableStateOf("") }
    var grid by remember { mutableStateOf(false) }
    var showHidden by remember { mutableStateOf(false) }
    val sort = snapshot?.librarySortState(PlaylistSortTypeKey, PlaylistSortDescendingKey, LibraryDefaults.playlistSort)
        ?: LibraryDefaults.playlistSort
    val locked = snapshot?.get(PlaylistEditLockKey) ?: true
    val tagIds = tags.mapTo(HashSet()) { it.id }
    val state = PlaylistCollectionUi(
        rows = rows.orEmpty(),
        tags = tags.map { PlaylistTagUi(it.id, it.name, desktopTagColor(it.color)) },
        // A deleted tag drops out of the filter, so it can never hide every row.
        filterTagIds = filterTagIds intersect tagIds,
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
        filterTag = { id -> filterTagIds = if (id in filterTagIds) filterTagIds - id else filterTagIds + id },
        grid = { grid = !grid },
        lock = { graph.scope.launch { content.setPlaylistLocked(!locked) } },
        create = { creation.show() },
        manageTags = {},
        open = {},
        more = menus::open,
        assignTags = menus::openTags,
        exportCsv = { ref ->
            rows?.firstOrNull { it.item.ref == ref }?.let { row -> menus.exportFromRow(ref, row.item.title) }
        },
        // The portable file keeps no custom order for the playlist list, so a drag says so instead of moving nothing.
        move = { _, _, _ -> menus.refuseMove() },
        retry = { attempt += 1 },
    )
    return ArchiveGraphPage.Playlists(state, actions)
}

/** The playlist routes the owner answers: a local playlist, the liked songs and a My Top list. */
@Composable
private fun desktopPlaylistPage(graph: DesktopSessionGraph, menu: DesktopItemMenuState, route: ArchiveRoute): ArchiveGraphPage =
    hostCatalogDetailPage(graph.catalogPages, route) { occurrence -> menu.open(occurrence) }
        ?: hostPlaylistPage(graph.pageOwners, route) { occurrence -> menu.open(occurrence) }

/** Plays [start] inside the complete list [collection] names, in the order the owner shows it. */
internal fun desktopPlayOccurrence(graph: DesktopSessionGraph, collection: ContentRequest?, start: QueueStart) {
    if (collection != null && hostPlayCatalogOccurrence(graph.catalogPages, collection, start)) return
    hostPlayOccurrence(graph.pageOwners, collection, start)
}
