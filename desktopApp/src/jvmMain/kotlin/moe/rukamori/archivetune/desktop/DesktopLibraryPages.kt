package moe.rukamori.archivetune.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.text.DateFormat
import java.util.Date
import java.util.TimeZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.desktop.integration.DesktopStrings
import moe.rukamori.archivetune.desktop.provider.youTubeSignIn
import moe.rukamori.archivetune.shared.domain.library.LibraryDefaults
import moe.rukamori.archivetune.shared.domain.library.LibrarySortState
import moe.rukamori.archivetune.shared.domain.library.librarySortState
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.ContentRequest
import moe.rukamori.archivetune.shared.domain.repository.ContentSurface
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
import moe.rukamori.archivetune.shared.ui.screens.ContentActions
import moe.rukamori.archivetune.shared.ui.screens.DetailActions
import moe.rukamori.archivetune.shared.ui.screens.DetailKind
import moe.rukamori.archivetune.shared.ui.screens.PagedContentState
import moe.rukamori.archivetune.shared.ui.screens.PlaylistCollectionActions
import moe.rukamori.archivetune.shared.ui.screens.PlaylistCollectionRow
import moe.rukamori.archivetune.shared.ui.screens.PlaylistCollectionUi
import moe.rukamori.archivetune.shared.ui.screens.PlaylistTagUi
import moe.rukamori.archivetune.spotify.SpotifyMapper
import moe.rukamori.archivetune.spotify.models.SpotifyPlaylist
import moe.rukamori.archivetune.spotify.models.SpotifyPlaylistTracksRef
import moe.rukamori.archivetune.spotify.models.SpotifyTrack

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
    spotify: DesktopSpotifyAccounts,
    route: ArchiveRoute,
    back: () -> Unit,
    openRoute: (String) -> Unit,
): ArchiveGraphPage = key(route.value) {
    when (route.destination) {
        ArchiveDestination.LIBRARY -> desktopLibraryPage(graph, openRoute)
        ArchiveDestination.SEARCH, ArchiveDestination.SEARCH_RESULTS ->
            hostSearchPage(graph.catalogPages, route) { openRoute(ArchiveRoutes.musicRecognition()) }
        ArchiveDestination.ALBUM -> hostCatalogDetailPage(graph.catalogPages, route) { occurrence -> menu.open(occurrence) }
            ?: ArchiveGraphPage.Host
        ArchiveDestination.PLAYLISTS -> desktopPlaylistsPage(graph, creation, playlistMenus, spotify)
        ArchiveDestination.TAGS -> desktopTagsPage(graph, playlistMenus)
        ArchiveDestination.PLAYLIST -> desktopPlaylistPage(graph, menu, spotify, route)
        ArchiveDestination.MUSIC_RECOGNITION -> desktopRecognitionPage(recognition, route)
        ArchiveDestination.MUSIC_RECOGNITION_DETAILS -> desktopRecognitionDetailsPage(recognition, route)
        ArchiveDestination.LISTEN_TOGETHER -> desktopListenTogetherPage(graph, openRoute)
        ArchiveDestination.LISTEN_TOGETHER_CHAT -> desktopCommentTogetherPage(graph, back)
        ArchiveDestination.TELEGRAM_BROWSE -> desktopTelegramBrowsePage(graph, back)
        ArchiveDestination.TELEGRAM_BOTS -> desktopTelegramBotsPage(graph, back, openRoute)
        ArchiveDestination.HISTORY -> desktopHistoryPage(graph, menu, spotify)
        ArchiveDestination.ACCOUNT -> desktopAccountPage(graph)
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
private fun desktopHistoryPage(
    graph: DesktopSessionGraph,
    menu: DesktopItemMenuState,
    spotify: DesktopSpotifyAccounts,
): ArchiveGraphPage {
    // DateFormat is not thread-safe. It formats only while the page composes, on the window's UI thread.
    val format = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    val snapshot by graph.settings.snapshots.collectAsState()
    val remote = buildList {
        if (snapshot.youTubeSignIn() != null) add(DesktopYouTubeHistoryPager(graph))
        if (spotify.historyAvailable()) add(DesktopSpotifyHistoryPager(graph, spotify))
    }
    return hostHistoryPage(
        owners = graph.pageOwners,
        offsetMsAt = { epochMs -> TimeZone.getDefault().getOffset(epochMs).toLong() },
        nowMs = System::currentTimeMillis,
        formatTime = { epochMs -> format.format(Date(epochMs)) },
        more = { occurrence -> menu.open(occurrence) },
        remote = remote,
    )
}

/** The local playlists and enabled Spotify playlists with their real counts, in the one collection page. */
private const val SPOTIFY_LIKED_SONGS_ID = "__spotify_liked_songs__"
private const val SPOTIFY_DJ_PLAYLIST_ID = "37i9dQZF1EYkqdzj48dyYq"
private const val SPOTIFY_PLAYLIST_LOAD_TIMEOUT_MS = 20_000L

@Composable
private fun desktopPlaylistsPage(
    graph: DesktopSessionGraph,
    creation: DesktopPlaylistCreation,
    menus: DesktopPlaylistMenuState,
    spotify: DesktopSpotifyAccounts,
): ArchiveGraphPage {
    val content = graph.libraryContent
    val snapshot by graph.settings.snapshots.collectAsState()
    var attempt by remember { mutableStateOf(0) }
    var spotifyRows by remember { mutableStateOf<List<SpotifyPlaylist>?>(null) }
    val rows by remember(attempt) { graph.taggedPlaylistRows() }.collectAsState(null)
    LaunchedEffect(spotify.libraryAvailable(), attempt) {
        spotifyRows = if (!spotify.libraryAvailable()) {
            emptyList()
        } else {
            try {
                spotify.libraryPlaylists(force = attempt > 0)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                graph.report("spotify playlists not loaded: ${failure.javaClass.simpleName}")
                spotify.cachedPlaylists().orEmpty()
            }
        }
    }
    val tags by remember(attempt) { graph.playlistTags.observeTags() }.collectAsState(emptyList())
    var filterTagIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var query by remember { mutableStateOf("") }
    var grid by remember { mutableStateOf(false) }
    var showHidden by remember { mutableStateOf(false) }
    val sort = snapshot?.librarySortState(PlaylistSortTypeKey, PlaylistSortDescendingKey, LibraryDefaults.playlistSort)
        ?: LibraryDefaults.playlistSort
    val locked = snapshot?.get(PlaylistEditLockKey) ?: true
    val tagIds = tags.mapTo(HashSet()) { it.id }
    val localRows = rows.orEmpty()
    val state = PlaylistCollectionUi(
        rows = localRows + spotifyRows.orEmpty().map { playlist ->
            PlaylistCollectionRow(
                item = ContentItem(
                    ref = ContentRef(ContentProvider.SPOTIFY_CATALOG.id, ContentKind.PLAYLIST, playlist.id),
                    title = playlist.name,
                    subtitle = playlist.owner?.displayName.orEmpty(),
                    artworkUri = SpotifyMapper.getPlaylistThumbnail(playlist),
                ),
                songCount = playlist.tracks?.total ?: 0,
                editable = false,
                exportable = false,
            )
        },
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
        more = { ref ->
            if (ref.provider == ContentProvider.LOCAL.id) menus.open(ref) else menus.refuseMove()
        },
        assignTags = { ref ->
            if (ref.provider == ContentProvider.LOCAL.id) menus.openTags(ref) else menus.refuseMove()
        },
        exportCsv = { ref ->
            localRows.firstOrNull { it.item.ref == ref }?.let { row -> menus.exportFromRow(ref, row.item.title) }
                ?: menus.refuseMove()
        },
        // The portable file keeps no custom order for local or remote playlists, so a drag says so instead of moving nothing.
        move = { _, _, _ -> menus.refuseMove() },
        retry = { attempt += 1 },
    )
    return ArchiveGraphPage.Playlists(state, actions)
}

/** The playlist routes the owner answers: a local playlist, the liked songs and a My Top list. */
@Composable
private fun desktopPlaylistPage(
    graph: DesktopSessionGraph,
    menu: DesktopItemMenuState,
    spotify: DesktopSpotifyAccounts,
    route: ArchiveRoute,
): ArchiveGraphPage =
    if (route.arguments["playlistType"] == "spotify_playlist") {
        desktopSpotifyPlaylistPage(graph, spotify, route.arguments.getValue("playlistId"))
    } else {
        hostCatalogDetailPage(graph.catalogPages, route) { occurrence -> menu.open(occurrence) }
            ?: hostPlaylistPage(graph.pageOwners, route) { occurrence ->
                // Only an editable local playlist offers the row's remove-from-playlist action.
                menu.open(occurrence)
            }
    }

/** A Spotify playlist, with Android's 20-second load bound and complete playable track drain. */
@Composable
private fun desktopSpotifyPlaylistPage(
    graph: DesktopSessionGraph,
    spotify: DesktopSpotifyAccounts,
    playlistId: String,
): ArchiveGraphPage {
    var attempt by remember { mutableStateOf(0) }
    var state by remember { mutableStateOf(SpotifyPlaylistPageState(loading = true)) }
    LaunchedEffect(playlistId, attempt, spotify.libraryAvailable()) {
        state = SpotifyPlaylistPageState(loading = true)
        val loaded = if (playlistId.isBlank() || playlistId == SPOTIFY_DJ_PLAYLIST_ID || !spotify.libraryAvailable()) {
            null
        } else {
            try {
                withTimeoutOrNull(SPOTIFY_PLAYLIST_LOAD_TIMEOUT_MS) {
                    if (playlistId == SPOTIFY_LIKED_SONGS_ID) {
                        val tracks = spotify.likedSongs()
                        SpotifyPlaylist(
                            id = playlistId,
                            name = DesktopStrings["liked_songs"],
                            tracks = SpotifyPlaylistTracksRef(total = tracks.size),
                        ) to tracks
                    } else {
                        spotify.playlistWithTracks(playlistId)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                graph.report("spotify playlist not loaded: ${failure.javaClass.simpleName}")
                null
            }
        }
        state = loaded?.let { (playlist, tracks) ->
            SpotifyPlaylistPageState(playlist = playlist, tracks = tracks)
        } ?: SpotifyPlaylistPageState(failed = true)
    }
    val playlist = state.playlist
    val request = ContentRequest(
        provider = ContentProvider.SPOTIFY_CATALOG,
        surface = ContentSurface.LIBRARY_PLAYLIST_ITEMS,
        parent = ContentRef(ContentProvider.SPOTIFY_CATALOG.id, ContentKind.PLAYLIST, playlistId),
    )
    val tracks = state.tracks
    val page = PagedContentState(
        request = request,
        items = tracks.mapIndexed { index, track ->
            ContentOccurrence("spotify:playlist:$playlistId:$index:${track.id}", spotify.spotifyTrackItem(track))
        },
        initialized = !state.loading || state.failed,
        loading = state.loading,
        failed = state.failed,
    )
    val play: (Int, Boolean) -> Unit = { start, shuffled ->
        graph.scope.launch {
            try {
                spotify.playTrackList(playlist?.name.orEmpty(), tracks, start, shuffled)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                graph.report("spotify playlist play failed: ${failure.javaClass.simpleName}")
            }
        }
    }
    val header = playlist?.let {
        ContentItem(
            ref = ContentRef(ContentProvider.SPOTIFY_CATALOG.id, ContentKind.PLAYLIST, it.id),
            title = it.name,
            subtitle = it.owner?.displayName ?: it.description.orEmpty(),
            artworkUri = SpotifyMapper.getPlaylistThumbnail(it),
        )
    }
    return ArchiveGraphPage.Detail(
        kind = DetailKind.Playlist,
        header = header,
        page = page,
        actions = DetailActions(
            content = ContentActions(
                open = { occurrence ->
                    val index = page.items.indexOfFirst { it.occurrenceId == occurrence.occurrenceId }
                    if (index >= 0) play(index, false)
                },
                more = null,
                loadMore = {},
                retry = { attempt += 1 },
            ),
            back = {},
            playAll = { play(0, false) },
            shuffle = { play(0, true) },
            toggleLibrary = null,
            more = null,
        ),
    )
}

private data class SpotifyPlaylistPageState(
    val playlist: SpotifyPlaylist? = null,
    val tracks: List<SpotifyTrack> = emptyList(),
    val loading: Boolean = false,
    val failed: Boolean = false,
)

/** Plays [start] inside the complete list [collection] names, in the order the owner shows it. */
internal fun desktopPlayOccurrence(graph: DesktopSessionGraph, collection: ContentRequest?, start: QueueStart) {
    if (collection != null && hostPlayCatalogOccurrence(graph.catalogPages, collection, start)) return
    hostPlayOccurrence(graph.pageOwners, collection, start)
}
