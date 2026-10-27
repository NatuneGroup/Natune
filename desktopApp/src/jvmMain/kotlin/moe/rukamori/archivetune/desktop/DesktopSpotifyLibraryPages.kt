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
import moe.rukamori.archivetune.desktop.integration.DesktopStrings
import moe.rukamori.archivetune.shared.domain.library.DefaultLibraryFilterOrder
import moe.rukamori.archivetune.shared.domain.library.LibraryFilter
import moe.rukamori.archivetune.shared.domain.library.toLibraryFilterOrder
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.ContentRequest
import moe.rukamori.archivetune.shared.domain.repository.ContentSurface
import moe.rukamori.archivetune.shared.domain.settings.LibraryChipOrderKey
import moe.rukamori.archivetune.shared.ui.host.hostCatalogDetailPage
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveDestination
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveGraphPage
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveRoute
import moe.rukamori.archivetune.shared.ui.screens.ContentActions
import moe.rukamori.archivetune.shared.ui.screens.DetailActions
import moe.rukamori.archivetune.shared.ui.screens.DetailKind
import moe.rukamori.archivetune.shared.ui.screens.PagedContentState
import moe.rukamori.archivetune.spotify.Spotify
import moe.rukamori.archivetune.spotify.SpotifyMapper
import moe.rukamori.archivetune.spotify.models.SpotifyAlbum
import moe.rukamori.archivetune.spotify.models.SpotifyArtist
import moe.rukamori.archivetune.spotify.models.SpotifyPlaylist

private const val SPOTIFY_LIKED_SONGS_ID = "__spotify_liked_songs__"
private const val SPOTIFY_RESOLVE_TIMEOUT_MS = 20_000L

/** The Spotify-backed Library filters: one complete catalog section per selected chip. */
@Composable
internal fun desktopSpotifyLibraryPage(
    graph: DesktopSessionGraph,
    spotify: DesktopSpotifyAccounts,
): ArchiveGraphPage {
    val snapshot by graph.settings.snapshots.collectAsState()
    var chosen by remember { mutableStateOf<LibraryFilter?>(null) }
    var attempt by remember { mutableStateOf(0) }
    val order = snapshot?.get(LibraryChipOrderKey)?.toLibraryFilterOrder() ?: DefaultLibraryFilterOrder
    val filter = chosen ?: order.firstOrNull { it != LibraryFilter.LIBRARY } ?: LibraryFilter.SONGS
    val loaded = remember { mutableStateMapOf<LibraryFilter, List<ContentOccurrence>>() }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    val request = ContentRequest(ContentProvider.SPOTIFY_CATALOG, filter.librarySurface())

    LaunchedEffect(filter, attempt) {
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

    return ArchiveGraphPage.Library(
        filter = filter,
        order = order,
        filterChange = { chosen = it },
        page = PagedContentState(
            request = request,
            items = loaded[filter].orEmpty(),
            loading = loading,
            initialized = !loading || failed,
            failed = failed,
        ),
        loadMore = {},
        retry = { attempt += 1 },
        myTopSize = null,
        mixHeader = null,
    )
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
        LibraryFilter.LIBRARY -> buildList {
            add(
                ContentOccurrence(
                    "spotify:library:liked",
                    ContentItem(
                        ref = ContentRef(ContentProvider.SPOTIFY_CATALOG.id, ContentKind.PLAYLIST, SPOTIFY_LIKED_SONGS_ID),
                        title = DesktopStrings["liked_songs"],
                    ),
                ),
            )
            addAll(libraryPlaylists().take(8).mapIndexed { index, playlist ->
                ContentOccurrence("spotify:library:mix:playlist:$index:${playlist.id}", playlist.libraryItem())
            })
            addAll(libraryArtists().take(10).mapIndexed { index, artist ->
                ContentOccurrence("spotify:library:mix:artist:$index:${artist.id}", artist.libraryItem())
            })
            addAll(libraryAlbums().take(10).mapIndexed { index, album ->
                ContentOccurrence("spotify:library:mix:album:$index:${album.id}", album.libraryItem())
            })
        }
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
