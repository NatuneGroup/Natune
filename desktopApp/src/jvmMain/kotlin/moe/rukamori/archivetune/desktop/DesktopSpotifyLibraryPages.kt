package moe.rukamori.archivetune.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.desktop.integration.DesktopStrings
import moe.rukamori.archivetune.shared.domain.library.DefaultLibraryFilterOrder
import moe.rukamori.archivetune.shared.domain.library.LibraryDefaults
import moe.rukamori.archivetune.shared.domain.library.LibraryFilter
import moe.rukamori.archivetune.shared.domain.library.toLibraryFilterOrder
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.ContentRequest
import moe.rukamori.archivetune.shared.domain.repository.ContentSurface
import moe.rukamori.archivetune.shared.domain.repository.HistoryOrigin
import moe.rukamori.archivetune.shared.domain.repository.hasBlockedArtist
import moe.rukamori.archivetune.shared.domain.settings.ChipSortTypeKey
import moe.rukamori.archivetune.shared.domain.settings.HideAiMixKey
import moe.rukamori.archivetune.shared.domain.settings.HideCachedCardKey
import moe.rukamori.archivetune.shared.domain.settings.HideLikedSongsCardKey
import moe.rukamori.archivetune.shared.domain.settings.HideLocalFilesCardKey
import moe.rukamori.archivetune.shared.domain.settings.HideOfflineCardKey
import moe.rukamori.archivetune.shared.domain.settings.HideTop50CardKey
import moe.rukamori.archivetune.shared.domain.settings.LibraryChipOrderKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.SpotifySpDcKey
import moe.rukamori.archivetune.shared.domain.settings.TopSize
import moe.rukamori.archivetune.shared.domain.settings.toEnum
import moe.rukamori.archivetune.shared.ui.host.hostCatalogDetailPage
import moe.rukamori.archivetune.shared.ui.host.queuedEntry
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveDestination
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveGraphPage
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveRoute
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveRoutes
import moe.rukamori.archivetune.shared.ui.screens.ContentActions
import moe.rukamori.archivetune.shared.ui.screens.DetailActions
import moe.rukamori.archivetune.shared.ui.screens.DetailKind
import moe.rukamori.archivetune.shared.ui.screens.LibraryMixHeaderActions
import moe.rukamori.archivetune.shared.ui.screens.LibraryMixHeaderBinding
import moe.rukamori.archivetune.shared.ui.screens.LibraryMixHeaderUi
import moe.rukamori.archivetune.shared.ui.screens.LibraryMixRailUi
import moe.rukamori.archivetune.shared.ui.screens.LibraryShortcutsUi
import moe.rukamori.archivetune.shared.ui.screens.LibraryTopMixesUi
import moe.rukamori.archivetune.shared.ui.screens.PagedContentState
import moe.rukamori.archivetune.spotify.Spotify
import moe.rukamori.archivetune.spotify.SpotifyMapper
import moe.rukamori.archivetune.spotify.models.SpotifyAlbum
import moe.rukamori.archivetune.spotify.models.SpotifyArtist
import moe.rukamori.archivetune.spotify.models.SpotifyPlaylist

private const val SPOTIFY_LIKED_SONGS_ID = "__spotify_liked_songs__"
private const val SPOTIFY_RESOLVE_TIMEOUT_MS = 20_000L

private const val SPOTIFY_RECENT_SONG_LIMIT = 15
private const val SPOTIFY_DEFAULT_MY_TOP_SIZE = 50
private const val SPOTIFY_SECTION_RETRY_COOLDOWN_MS = 60_000L

/** The Spotify-backed Library filters: Android's mixed home for Library and one section per selected chip. */
@Composable
internal fun desktopSpotifyLibraryPage(
    graph: DesktopSessionGraph,
    spotify: DesktopSpotifyAccounts,
    openRoute: (String) -> Unit,
): ArchiveGraphPage {
    val snapshot by graph.settings.snapshots.collectAsState()
    var chosen by remember { mutableStateOf<LibraryFilter?>(null) }
    var attempt by remember { mutableStateOf(0) }
    var artistsAttempt by remember { mutableStateOf(0) }
    val order = snapshot?.get(LibraryChipOrderKey)?.toLibraryFilterOrder() ?: DefaultLibraryFilterOrder
    val defaultFilter = snapshot?.get(ChipSortTypeKey).toEnum(LibraryDefaults.chipSortType)
    val filter = chosen ?: defaultFilter.takeIf { it in order } ?: order.first()
    val accountKey = snapshot?.get(SpotifySpDcKey).orEmpty()
    val loaded = remember(accountKey) { mutableStateMapOf<LibraryFilter, List<ContentOccurrence>>() }
    var mix by remember(accountKey) { mutableStateOf(SpotifyLibraryMixState()) }
    var loading by remember(accountKey) { mutableStateOf(true) }
    var failed by remember(accountKey) { mutableStateOf(false) }
    val request = ContentRequest(ContentProvider.SPOTIFY_CATALOG, filter.librarySurface())

    LaunchedEffect(filter, attempt, accountKey) {
        if (filter == LibraryFilter.LIBRARY) {
            loading = false
            failed = false
            val loadedPlaylists = runCatching {
                spotify.libraryPlaylists().take(8).mapIndexed { index, playlist ->
                    ContentOccurrence("spotify:library:mix:playlist:$index:${playlist.id}", playlist.mixItem())
                }
            }
            loadedPlaylists.onFailure { failure ->
                if (failure is CancellationException) throw failure
                graph.report("spotify library playlists not loaded: ${failure.javaClass.simpleName}")
            }
            val preservedPlaylists = loadedPlaylists.getOrNull() ?: spotify.cachedPlaylists()?.take(8)?.mapIndexed { index, playlist ->
                ContentOccurrence("spotify:library:mix:playlist:$index:${playlist.id}", playlist.mixItem())
            } ?: mix.playlists
            mix = mix.copy(
                playlists = preservedPlaylists,
                playlistsInitialized = loadedPlaylists.isSuccess ||
                    spotify.cachedPlaylists() != null ||
                    mix.playlistsInitialized,
            )
            return@LaunchedEffect
        }
        val cached = loaded[filter]
        if (cached != null && attempt == 0) {
            loading = false
            failed = false
            return@LaunchedEffect
        }
        loading = true
        failed = false
        try {
            loaded[filter] = spotify.loadLibrarySection(filter)
            loading = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            graph.report("spotify library ${filter.name.lowercase()} not loaded: ${failure.javaClass.simpleName}")
            loading = false
            failed = true
        }
    }

    LaunchedEffect(filter, artistsAttempt, accountKey) {
        if (filter != LibraryFilter.LIBRARY) return@LaunchedEffect
        val retried = mix.artistsLoadAttempt != artistsAttempt
        if (!retried && mix.artistsInitialized) return@LaunchedEffect
        if (!retried) {
            val failedAt = mix.artistsFailedAtMillis
            val cooldown = if (isSpotifyRateLimitMessage(mix.artistsError)) {
                SPOTIFY_SECTION_RETRY_COOLDOWN_MS * 5
            } else {
                SPOTIFY_SECTION_RETRY_COOLDOWN_MS
            }
            if (failedAt != null && monotonicMillis() - failedAt < cooldown) return@LaunchedEffect
        }
        mix = mix.copy(
            artistsLoadAttempt = artistsAttempt,
            artistsFailedAtMillis = null,
            artistsError = null,
        )
        val loadedArtists = runCatching {
            spotify.libraryArtists().take(10).mapIndexed { index, artist ->
                ContentOccurrence("spotify:library:mix:artist:$index:${artist.id}", artist.libraryItem())
            }
        }
        loadedArtists.onFailure { failure ->
            if (failure is CancellationException) throw failure
            graph.report("spotify library artists not loaded: ${failure.javaClass.simpleName}")
        }
        val failure = loadedArtists.exceptionOrNull()
        mix = mix.copy(
            artists = loadedArtists.getOrNull() ?: mix.artists,
            artistsInitialized = loadedArtists.isSuccess || mix.artistsInitialized,
            artistsFailedAtMillis = failure?.let { monotonicMillis() },
            artistsError = failure?.let {
                it.message ?: it.javaClass.simpleName ?: DesktopStrings["spotify_load_failed"]
            },
        )
    }

    val myTopSize = snapshot?.takeUnless { it.get(HideTop50CardKey) == true }
        ?.let { current -> current.get(TopSize)?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: SPOTIFY_DEFAULT_MY_TOP_SIZE }
    return ArchiveGraphPage.Library(
        filter = filter,
        order = order,
        filterChange = { chosen = it },
        page = PagedContentState(
            request = request,
            items = loaded[filter].orEmpty(),
            loading = if (filter == LibraryFilter.LIBRARY) false else loading,
            initialized = filter == LibraryFilter.LIBRARY || !loading || failed,
            failed = filter != LibraryFilter.LIBRARY && failed,
        ),
        loadMore = {},
        retry = { attempt += 1 },
        myTopSize = myTopSize,
        mixHeader = if (filter == LibraryFilter.LIBRARY) {
            spotifyLibraryMixHeader(
                graph,
                snapshot,
                myTopSize,
                attempt,
                mix,
                openRoute,
                { chosen = it },
            ) { key ->
                if (key == "artists") artistsAttempt += 1 else attempt += 1
            }
        } else {
            null
        },
    )
}

private data class SpotifyLibraryMixState(
    val playlists: List<ContentOccurrence> = emptyList(),
    val playlistsInitialized: Boolean = false,
    val artists: List<ContentOccurrence> = emptyList(),
    val artistsInitialized: Boolean = false,
    val artistsLoadAttempt: Int = -1,
    val artistsFailedAtMillis: Long? = null,
    val artistsError: String? = null,
)

private fun monotonicMillis(): Long = System.nanoTime() / 1_000_000

private fun isSpotifyRateLimitMessage(message: String?): Boolean =
    message != null &&
        (
            message.contains("429") ||
                message.contains("Too Many Requests", ignoreCase = true) ||
                message.contains("Rate limit", ignoreCase = true)
        )

/**
 * Android's Spotify Library home keeps the local shortcut grid and local recent rail, then replaces the section rows
 * with its source-owned playlist and artist rails. The Spotify Liked card names its source and opens the synthetic
 * liked playlist rather than the local auto playlist.
 */
@Composable
private fun spotifyLibraryMixHeader(
    graph: DesktopSessionGraph,
    snapshot: PreferenceSnapshot?,
    myTopSize: Int?,
    attempt: Int,
    mix: SpotifyLibraryMixState,
    openRoute: (String) -> Unit,
    selectFilter: (LibraryFilter) -> Unit,
    retryRail: (String) -> Unit,
): LibraryMixHeaderBinding {
    val owners = graph.pageOwners
    val blocked by remember { owners.blocked }.collectAsState(emptySet())
    var recent by remember { mutableStateOf<List<ContentOccurrence>>(emptyList()) }
    LaunchedEffect(attempt, blocked) {
        recent = try {
            owners.history.page(HistoryOrigin.LOCAL).events
                .map { it.item }
                .filter { it.item.ref.kind == ContentKind.SONG && !it.item.hasBlockedArtist(blocked) }
                .distinctBy { it.item.ref }
                .take(SPOTIFY_RECENT_SONG_LIMIT)
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
            likedCount = null,
            offline = snapshot?.get(HideOfflineCardKey) != true,
            cached = snapshot?.get(HideCachedCardKey) != true,
            localFiles = snapshot?.get(HideLocalFilesCardKey) != true,
            myTopSize = myTopSize,
            likedDetail = DesktopStrings["home_source_spotify"],
            likedRoute = "spotify_playlist/$SPOTIFY_LIKED_SONGS_ID",
        ),
        recent = recent,
        topMixes = if (snapshot?.get(HideAiMixKey) == true) {
            null
        } else {
            LibraryTopMixesUi.Message(DesktopStrings["spotify_library_mixes_unavailable"])
        },
        rails = listOf(
            LibraryMixRailUi(
                key = "playlists",
                title = DesktopStrings["spotify_your_playlists"],
                items = mix.playlists,
                target = LibraryFilter.PLAYLISTS,
                emptyText = DesktopStrings["spotify_library_playlists_empty"]
                    .takeIf { mix.playlistsInitialized && mix.playlists.isEmpty() },
            ),
            LibraryMixRailUi(
                key = "artists",
                title = DesktopStrings["spotify_your_artists"],
                items = mix.artists,
                target = LibraryFilter.ARTISTS,
                emptyText = DesktopStrings["spotify_library_artists_empty"]
                    .takeIf { mix.artistsInitialized && mix.artists.isEmpty() && mix.artistsError == null },
                errorText = mix.artistsError,
            ),
        ),
    )
    val actions = remember(owners, openRoute, selectFilter, retryRail) {
        LibraryMixHeaderActions(
            openRoute = openRoute,
            play = { song ->
                owners.scope.launch {
                    val entry = song.queuedEntry()
                    owners.queue.replace(listOf(entry), entry.occurrenceId, playWhenReady = true)
                }
            },
            open = { occurrence -> openRoute(ArchiveRoutes.content(occurrence.item.ref)) },
            selectFilter = selectFilter,
            retryRail = retryRail,
        )
    }
    return LibraryMixHeaderBinding(state, actions, replacesRows = true)
}

private suspend fun DesktopSpotifyAccounts.loadLibrarySection(filter: LibraryFilter): List<ContentOccurrence> =
    when (filter) {
        LibraryFilter.SONGS -> likedSongs().map { libraryTrackRow(it, "songs") }
        LibraryFilter.ARTISTS -> libraryArtists().mapIndexed { index, artist ->
            ContentOccurrence("spotify:library:artists:$index:${artist.id}", artist.libraryItem())
        }
        LibraryFilter.ALBUMS -> libraryAlbums().mapIndexed { index, album ->
            ContentOccurrence("spotify:library:albums:$index:${album.id}", album.libraryItem())
        }
        LibraryFilter.PLAYLISTS -> libraryPlaylists().mapIndexed { index, playlist ->
            ContentOccurrence("spotify:library:playlists:$index:${playlist.id}", playlist.libraryItem())
        }
        LibraryFilter.LIBRARY -> emptyList()
    }

private fun LibraryFilter.librarySurface(): ContentSurface = when (this) {
    LibraryFilter.LIBRARY -> ContentSurface.LIBRARY_MIX
    LibraryFilter.PLAYLISTS -> ContentSurface.LIBRARY_PLAYLISTS
    LibraryFilter.SONGS -> ContentSurface.LIBRARY_SONGS
    LibraryFilter.ARTISTS -> ContentSurface.LIBRARY_ARTISTS
    LibraryFilter.ALBUMS -> ContentSurface.LIBRARY_ALBUMS
}

private fun SpotifyPlaylist.libraryItem(): ContentItem = ContentItem(
    ref = ContentRef(ContentProvider.SPOTIFY_CATALOG.id, ContentKind.PLAYLIST, id),
    title = name,
    subtitle = owner?.displayName.orEmpty(),
    artworkUri = SpotifyMapper.getPlaylistThumbnail(this),
)

private fun SpotifyPlaylist.mixItem(): ContentItem = libraryItem().copy(
    subtitle = DesktopStrings["spotify_tracks_count"].format(tracks?.total ?: 0),
)

private fun SpotifyArtist.libraryItem(): ContentItem = ContentItem(
    ref = ContentRef(ContentProvider.SPOTIFY_CATALOG.id, ContentKind.ARTIST, id),
    title = name,
    subtitle = genres.take(3).joinToString(", "),
    artworkUri = SpotifyMapper.getArtistThumbnail(this),
)

private fun SpotifyAlbum.libraryItem(): ContentItem = ContentItem(
    ref = ContentRef(ContentProvider.SPOTIFY_CATALOG.id, ContentKind.ALBUM, id),
    title = name,
    subtitle = artists.joinToString(", ") { it.name },
    artworkUri = images.maxByOrNull { it.width ?: 0 }?.url,
)

/** Android's artist open: resolve the Spotify artist name to the first YouTube Music artist, then show that page. */
@Composable
internal fun desktopSpotifyArtistPage(
    graph: DesktopSessionGraph,
    spotify: DesktopSpotifyAccounts,
    route: ArchiveRoute,
    menu: DesktopItemMenuState,
    openRoute: (String) -> Unit,
    back: () -> Unit,
): ArchiveGraphPage {
    val artistId = route.arguments.getValue("artistId")
    var attempt by remember { mutableStateOf(0) }
    var target by remember { mutableStateOf<ContentRef?>(null) }
    var failed by remember { mutableStateOf(false) }
    var spotifyArtist by remember { mutableStateOf<SpotifyArtist?>(null) }
    LaunchedEffect(artistId, attempt) {
        failed = false
        target = null
        try {
            val artist = spotify.artist(artistId)
            spotifyArtist = artist
            target = spotify.resolveArtistTarget(artist.name)
            failed = target == null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            graph.report("spotify artist not resolved: ${failure.javaClass.simpleName}")
            failed = true
        }
    }
    val resolved = target
    if (resolved != null) {
        return desktopArtistPage(
            graph,
            route.copy(
                arguments = mapOf("artistId" to resolved.id, "provider" to ContentProvider.YOUTUBE.id),
            ),
            menu,
            openRoute,
            back,
        )
    }
    return spotifyDetailPlaceholder(
        kind = DetailKind.Artist,
        header = spotifyArtist?.libraryItem(),
        loading = !failed,
        failed = failed,
        back = back,
        retry = { attempt += 1 },
    )
}

/** Android's album open: YouTube album page first, then one song, then the raw Spotify tracks. */
@Composable
internal fun desktopSpotifyAlbumPage(
    graph: DesktopSessionGraph,
    spotify: DesktopSpotifyAccounts,
    route: ArchiveRoute,
    menu: DesktopItemMenuState,
    back: () -> Unit,
): ArchiveGraphPage {
    val albumId = route.arguments.getValue("albumId")
    var attempt by remember { mutableStateOf(0) }
    var album by remember { mutableStateOf<SpotifyAlbum?>(null) }
    var target by remember { mutableStateOf<SpotifyCatalogTarget?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(albumId, attempt) {
        failed = false
        target = null
        try {
            val loaded = spotify.album(albumId)
            album = loaded
            target = kotlinx.coroutines.withTimeoutOrNull(SPOTIFY_RESOLVE_TIMEOUT_MS) {
                spotify.resolveAlbumTarget(loaded)
            }
            failed = target == null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            graph.report("spotify album not resolved: ${failure.javaClass.simpleName}")
            failed = true
        }
    }
    when (val resolved = target) {
        is SpotifyCatalogTarget.AlbumPage -> {
            val resolvedRoute = ArchiveRoute(
                value = "album/${resolved.browseId}?provider=${ContentProvider.YOUTUBE.id}",
                destination = ArchiveDestination.ALBUM,
                arguments = mapOf("albumId" to resolved.browseId, "provider" to ContentProvider.YOUTUBE.id),
            )
            return hostCatalogDetailPage(graph.catalogPages, resolvedRoute) { occurrence -> menu.open(occurrence) }
                ?: ArchiveGraphPage.Host
        }
        is SpotifyCatalogTarget.Song -> {
            LaunchedEffect(resolved) {
                spotify.playYouTubeSong(resolved.song)
                back()
            }
        }
        is SpotifyCatalogTarget.SpotifyTracks -> {
            LaunchedEffect(resolved) {
                spotify.playTrackList(albumId, resolved.tracks)
                back()
            }
        }
        null -> Unit
    }
    return spotifyDetailPlaceholder(
        kind = DetailKind.Album,
        header = album?.libraryItem(),
        loading = !failed,
        failed = failed,
        back = back,
        retry = { attempt += 1 },
    )
}

private fun spotifyDetailPlaceholder(
    kind: DetailKind,
    header: ContentItem?,
    loading: Boolean,
    failed: Boolean,
    back: () -> Unit,
    retry: () -> Unit,
): ArchiveGraphPage.Detail = ArchiveGraphPage.Detail(
    kind = kind,
    header = header,
    page = PagedContentState(loading = loading, initialized = !loading || failed, failed = failed),
    actions = DetailActions(
        content = ContentActions(open = {}, more = null, loadMore = {}, retry = retry),
        back = back,
        playAll = null,
        shuffle = null,
        toggleLibrary = null,
        more = null,
    ),
)
