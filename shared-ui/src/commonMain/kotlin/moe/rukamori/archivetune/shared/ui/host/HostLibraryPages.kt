package moe.rukamori.archivetune.shared.ui.host

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.library.DefaultLibraryFilterOrder
import moe.rukamori.archivetune.shared.domain.library.LibraryFilter
import moe.rukamori.archivetune.shared.domain.library.LibrarySortState
import moe.rukamori.archivetune.shared.domain.library.toLibraryFilterOrder
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.ContentRequest
import moe.rukamori.archivetune.shared.domain.repository.ContentSurface
import moe.rukamori.archivetune.shared.domain.repository.HistoryOrigin
import moe.rukamori.archivetune.shared.domain.repository.HistoryRepository
import moe.rukamori.archivetune.shared.domain.repository.MyTopPeriod as DomainMyTopPeriod
import moe.rukamori.archivetune.shared.domain.repository.QueueController
import moe.rukamori.archivetune.shared.domain.repository.QueueStart
import moe.rukamori.archivetune.shared.domain.repository.hasBlockedArtist
import moe.rukamori.archivetune.shared.domain.settings.HideAiMixKey
import moe.rukamori.archivetune.shared.domain.settings.HideCachedCardKey
import moe.rukamori.archivetune.shared.domain.settings.HideLikedSongsCardKey
import moe.rukamori.archivetune.shared.domain.settings.HideLocalFilesCardKey
import moe.rukamori.archivetune.shared.domain.settings.HideOfflineCardKey
import moe.rukamori.archivetune.shared.domain.settings.HideTop50CardKey
import moe.rukamori.archivetune.shared.domain.settings.LibraryChipOrderKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.SettingsStore
import moe.rukamori.archivetune.shared.domain.settings.TopSize
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveGraphPage
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveRoute
import moe.rukamori.archivetune.shared.ui.resources.Res
import moe.rukamori.archivetune.shared.ui.resources.m4_my_top
import moe.rukamori.archivetune.shared.ui.resources.m9_library_liked_songs
import moe.rukamori.archivetune.shared.ui.screens.ContentActions
import moe.rukamori.archivetune.shared.ui.screens.DetailActions
import moe.rukamori.archivetune.shared.ui.screens.DetailKind
import moe.rukamori.archivetune.shared.ui.screens.LibraryMixHeaderActions
import moe.rukamori.archivetune.shared.ui.screens.LibraryMixHeaderBinding
import moe.rukamori.archivetune.shared.ui.screens.LibraryMixHeaderUi
import moe.rukamori.archivetune.shared.ui.screens.LibraryShortcutsUi
import moe.rukamori.archivetune.shared.ui.screens.LibraryTopMixesUi
import moe.rukamori.archivetune.shared.ui.screens.MyTopPeriod as UiMyTopPeriod
import moe.rukamori.archivetune.shared.ui.screens.PagedContentState
import moe.rukamori.archivetune.shared.ui.screens.PlaylistDetailControl
import org.jetbrains.compose.resources.stringResource

/**
 * The owners the shared Library and playlist pages read and write. A host passes its one instance of each, so a row on
 * a page and the queue always see the same complete list.
 */
class LibraryPageOwners(
    val content: LibraryContentOwner,
    val settings: SettingsStore,
    val queue: QueueController,
    val history: HistoryRepository,
    val blocked: Flow<Set<ContentRef>>,
    val scope: CoroutineScope,
    /** Writes one sanitized diagnostic line. */
    val report: (String) -> Unit,
)

/** The route id of the liked songs in `auto_playlist/liked`. The owner's pseudo id is [LIKED_PLAYLIST_ID]. */
private const val LIKED_ROUTE_ID = "liked"

/** The My Top size the baseline uses when no size is stored. */
private const val DEFAULT_MY_TOP_SIZE = 50

/** The newest distinct songs of the listening history the recently played rail shows, as the baseline's recentSongs(15). */
private const val RECENT_SONG_LIMIT = 15

/**
 * One Library section. The first section that has an owner opens first, so the page never starts on an unavailable one.
 * [topMixesUnavailable] is the host's line for the Top mixes section, which needs the AI integration.
 */
@Composable
fun hostLibraryPage(owners: LibraryPageOwners, topMixesUnavailable: String, openRoute: (String) -> Unit): ArchiveGraphPage {
    val content = owners.content
    val snapshot by owners.settings.snapshots.collectAsState()
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
        mixHeader = if (filter == LibraryFilter.LIBRARY) {
            libraryMixHeader(owners, snapshot, myTopSize, attempt, topMixesUnavailable, openRoute)
        } else {
            null
        },
    )
}

/**
 * The mixed section's header: the baseline's shortcut cards under their hide preferences, the recently played rail
 * from the local listening history, and the Top mixes section, which stays in its place and says why it is empty.
 */
@Composable
private fun libraryMixHeader(
    owners: LibraryPageOwners,
    snapshot: PreferenceSnapshot?,
    myTopSize: Int?,
    attempt: Int,
    topMixesUnavailable: String,
    openRoute: (String) -> Unit,
): LibraryMixHeaderBinding {
    val likedCount by remember { owners.content.likedSongs().map { it?.page?.items?.size } }.collectAsState(null)
    val blocked by remember { owners.blocked }.collectAsState(emptySet())
    var recent by remember { mutableStateOf<List<ContentOccurrence>>(emptyList()) }
    LaunchedEffect(attempt, blocked) {
        recent = try {
            owners.history.page(HistoryOrigin.LOCAL).events
                .map { it.item }
                .filter { it.item.ref.kind == ContentKind.SONG && !it.item.hasBlockedArtist(blocked) }
                .distinctBy { it.item.ref }
                .take(RECENT_SONG_LIMIT)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            owners.report("recently played not read: ${failure::class.simpleName}")
            emptyList()
        }
    }
    val state = LibraryMixHeaderUi(
        shortcuts = LibraryShortcutsUi(
            liked = snapshot?.get(HideLikedSongsCardKey) != true,
            likedCount = likedCount,
            offline = snapshot?.get(HideOfflineCardKey) != true,
            cached = snapshot?.get(HideCachedCardKey) != true,
            localFiles = snapshot?.get(HideLocalFilesCardKey) != true,
            myTopSize = myTopSize,
        ),
        recent = recent,
        topMixes = if (snapshot?.get(HideAiMixKey) == true) null else LibraryTopMixesUi.Message(topMixesUnavailable),
    )
    val actions = remember(owners, openRoute) {
        LibraryMixHeaderActions(
            openRoute = openRoute,
            // The baseline plays a recently played song on its own.
            play = { song ->
                owners.scope.launch {
                    val entry = song.queuedEntry()
                    owners.queue.replace(listOf(entry), entry.occurrenceId, playWhenReady = true)
                }
            },
        )
    }
    return LibraryMixHeaderBinding(state, actions)
}

/**
 * The playlist routes the owner answers: a local playlist, the liked songs and a My Top list. Every other playlist stays
 * on the host boundary. [more] opens the host's row menu.
 */
@Composable
fun hostPlaylistPage(owners: LibraryPageOwners, route: ArchiveRoute, more: (ContentOccurrence) -> Unit): ArchiveGraphPage {
    val id = route.arguments["playlistId"] ?: return ArchiveGraphPage.Host
    return when (route.arguments["playlistType"]) {
        "local_playlist" -> localPlaylistPage(owners, more, id)
        "auto_playlist" -> if (id == LIKED_ROUTE_ID) likedPage(owners, more) else ArchiveGraphPage.Host
        "top_playlist" -> {
            val size = id.toIntOrNull()?.takeIf { it > 0 }
            if (size == null) ArchiveGraphPage.Host else topPage(owners, more, size)
        }
        else -> ArchiveGraphPage.Host
    }
}

@Composable
private fun localPlaylistPage(owners: LibraryPageOwners, more: (ContentOccurrence) -> Unit, playlistId: String): ArchiveGraphPage {
    val content = owners.content
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
                owners.scope.launch { content.setPlaylistSort(playlistId, LibrarySortState(type, descending)) }
            },
            onLockedChange = { next -> owners.scope.launch { content.setPlaylistLocked(next) } },
            move = { row, neighbour, after ->
                owners.scope.launch { content.movePlaylistItem(playlistId, row.occurrenceId, neighbour.occurrenceId, after) }
            },
        )
    }
    return ArchiveGraphPage.Detail(
        kind = DetailKind.Playlist,
        header = loaded?.header,
        page = page,
        actions = playlistActions(owners, more, request) { attempt += 1 },
        control = control,
    )
}

/**
 * One My Top list of [size] songs. The period control is the page's only state: the list is read again when the period
 * changes, and the same request feeds Play, so playback follows the list the page shows.
 */
@Composable
private fun topPage(owners: LibraryPageOwners, more: (ContentOccurrence) -> Unit, size: Int): ArchiveGraphPage {
    val content = owners.content
    var period by remember { mutableStateOf(UiMyTopPeriod.ALL_TIME) }
    var attempt by remember { mutableStateOf(0) }
    val request = content.topRequest(period.domain(), size)
    val page by remember(request, attempt) { content.topPage(request) }
        .collectAsState(PagedContentState(request = request, loading = true))
    return ArchiveGraphPage.Detail(
        kind = DetailKind.Playlist,
        header = ContentItem(
            ContentRef(ContentProvider.LOCAL.id, ContentKind.PLAYLIST, "top_playlist/$size"),
            "${stringResource(Res.string.m4_my_top)} $size",
        ),
        page = page,
        actions = playlistActions(owners, more, request) { attempt += 1 },
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
private fun likedPage(owners: LibraryPageOwners, more: (ContentOccurrence) -> Unit): ArchiveGraphPage {
    val content = owners.content
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
                owners.scope.launch { content.setAutoSongSort(LibrarySortState(type, descending)) }
            },
        )
    }
    return ArchiveGraphPage.Detail(
        kind = DetailKind.Playlist,
        header = ContentItem(
            ContentRef(ContentProvider.LOCAL.id, ContentKind.PLAYLIST, LIKED_PLAYLIST_ID),
            stringResource(Res.string.m9_library_liked_songs),
        ),
        page = page,
        actions = playlistActions(owners, more, request) { attempt += 1 },
        control = control,
    )
}

/**
 * The row and list actions of a playlist page. A row plays from the owner's complete list in its owner order, starting
 * at the chosen row, and its overflow opens the host's row menu. The page's own back action and playlist menus belong to
 * the host, so they are not offered here.
 */
private fun playlistActions(
    owners: LibraryPageOwners,
    more: (ContentOccurrence) -> Unit,
    request: ContentRequest,
    retry: () -> Unit,
): DetailActions =
    DetailActions(
        content = ContentActions(
            open = { row ->
                hostPlayOccurrence(
                    owners,
                    request,
                    QueueStart(item = row.item.ref, occurrenceId = row.occurrenceId, setVideoId = row.setVideoId),
                )
            },
            more = more,
            loadMore = {},
            retry = retry,
        ),
        back = {},
        playAll = { hostPlayList(owners, request, shuffled = false) },
        shuffle = { hostPlayList(owners, request, shuffled = true) },
        toggleLibrary = null,
        more = null,
    )

/**
 * Plays [start] inside the complete list [collection] names, in the order the owner shows it, so Next follows that
 * order. Refused with one line when there is no list, the owner cannot answer, or the item is not in the list.
 */
fun hostPlayOccurrence(owners: LibraryPageOwners, collection: ContentRequest?, start: QueueStart) {
    owners.scope.launch {
        val request = collection ?: return@launch owners.report("this item has no list to play from on this host")
        val entries = owners.content.currentList(request)?.items
        val startId = entries?.let { list ->
            start.occurrenceId?.takeIf { id -> list.any { it.occurrenceId == id } }
                ?: list.firstOrNull { it.item.ref == start.item }?.occurrenceId
        }
        if (entries == null || startId == null) return@launch owners.report("the list does not hold this item")
        owners.queue.replace(entries, startId, playWhenReady = true)
    }
}

/** Plays a whole list from its first row, or from a shuffled order when [shuffled] is set. Refused when no rows are held. */
fun hostPlayList(owners: LibraryPageOwners, collection: ContentRequest, shuffled: Boolean) {
    owners.scope.launch {
        val entries = owners.content.currentList(collection)?.items.orEmpty()
        if (entries.isEmpty()) return@launch owners.report("nothing to play: the list is unavailable or empty")
        val order = if (shuffled) entries.shuffled() else entries
        owners.queue.replace(order, order.first().occurrenceId, playWhenReady = true)
    }
}

/**
 * A row keeps the occurrence id of its list, and the queue may already hold that id, so it would refuse the insert.
 * Every queue command therefore gets its own occurrence of the same item.
 */
fun ContentOccurrence.queuedEntry(): ContentOccurrence =
    ContentOccurrence("queued:${Random.nextLong().toULong().toString(16)}${Random.nextLong().toULong().toString(16)}", item, setVideoId)
