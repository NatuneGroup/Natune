package moe.rukamori.archivetune.shared.ui.ios

import androidx.compose.runtime.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.library.*
import moe.rukamori.archivetune.shared.domain.repository.*
import moe.rukamori.archivetune.shared.ui.IosHost
import moe.rukamori.archivetune.shared.ui.navigation.*
import moe.rukamori.archivetune.shared.ui.resources.*
import moe.rukamori.archivetune.shared.ui.screens.artistalbum.*
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun iosSpotifyArtistPage(graph: IosSessionGraph, host: IosHost, catalogue: IosCatalogueOwners,
    route: ArchiveRoute, menu: IosItemMenuState, openRoute: (String) -> Unit, back: () -> Unit): ArchiveGraphPage {
    val artist = requireNotNull(iosArtistRef(route))
    val repository = remember(graph) { IosSpotifyArtistRepository(graph) }
    var page by remember(artist) { mutableStateOf<ArtistPageUi?>(null) }
    var failed by remember(artist) { mutableStateOf(false) }
    var attempt by remember(artist) { mutableStateOf(0) }
    val songsTitle = stringResource(Res.string.ios_spotify_top_tracks)
    val albumsTitle = stringResource(Res.string.ios_spotify_releases)
    val requests = remember(artist) { listOf("top-tracks", "albums").map {
        ContentRequest(ContentProvider.SPOTIFY_CATALOG, ContentSurface.ARTIST, parent = artist, section = ContentSection(it, null))
    } }
    LaunchedEffect(artist, attempt) {
        failed = false
        try {
            page = coroutineScope {
                val metadata = async { repository.item(artist) }
                val tracks = async { repository.page(requests[0]) }
                val albums = async { repository.page(requests[1]) }
                val rows = listOf(tracks.await(), albums.await())
                rows.forEachIndexed { index, result -> iosRecordSpotifyRows(requests[index], result.items) }
                ArtistPageUi(metadata.await(), null, null, null,
                    rows.mapIndexed { index, result -> ArtistShelfUi(
                        ContentShelf(requireNotNull(requests[index].section), if (index == 0) songsTitle else albumsTitle, result),
                        if (index == 0) ArtistShelfLayout.LIST else ArtistShelfLayout.GRID,
                        requests[index].takeIf { result.continuation != null }) },
                    emptyList(), "https://open.spotify.com/artist/${artist.id}")
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) { failed = true; graph.report("Spotify artist unavailable: ${error::class.simpleName}") }
    }
    val subscribed by remember(artist) { graph.libraryContent.isLiked(artist) }.collectAsState(false)
    val blocked by remember(artist) { graph.artistBlocks.observeBlocked(artist) }.collectAsState(null)
    val songs by remember(artist) { graph.libraryContent.artistSongs(artist, LibrarySortState(SongSortType.NAME, false)) }.collectAsState(null)
    val albums by remember(artist) { graph.libraryContent.artistAlbums(artist, LibrarySortState(AlbumSortType.NAME, false)) }.collectAsState(null)
    val adapter = IosSpotifyDetailHost(graph, IosArtistAlbumHost(graph, host, catalogue, menu, openRoute, back),
        reloadArtist = { attempt++ }, likedItem = { page?.artist })
    return ArchiveGraphPage.Artist(artist, page, failed,
        ArtistLibraryUi(page?.artist?.title, page?.artist?.artworkUri, false, subscribed, songs.orEmpty(),
            albums.orEmpty().map { ArtistReleaseUi(it.album.ref, it.album.title, it.album.artworkUri, null, ArtistReleaseType.ALBUM) }),
        if (blocked == null) ArtistBlockUiState.Loading else ArtistBlockUiState.Known(blocked == true),
        iosNowPlaying(graph), adapter)
}

@Composable
internal fun iosSpotifyAlbumPage(graph: IosSessionGraph, host: IosHost, catalogue: IosCatalogueOwners,
    route: ArchiveRoute, menu: IosItemMenuState, openRoute: (String) -> Unit, back: () -> Unit): ArchiveGraphPage {
    val album = remember(route.value) { ContentRef(ContentProvider.SPOTIFY_CATALOG.id, ContentKind.ALBUM, route.arguments.getValue("albumId")) }
    val client = remember(graph) { IosSpotifyRestClient(graph) }
    var detail by remember(album) { mutableStateOf<IosSpotifyAlbumDetail?>(null) }
    var state by remember(album) { mutableStateOf<AlbumLoadState>(AlbumLoadState.Loading) }
    var attempt by remember(album) { mutableStateOf(0) }
    val subtitle = stringResource(Res.string.ios_library_source_spotify)
    LaunchedEffect(album, attempt) {
        state = AlbumLoadState.Loading
        try {
            detail = client.fetchAlbumDetail(album.id, subtitle)
            iosRecordSpotifyRows(ContentRequest(ContentProvider.SPOTIFY_CATALOG, ContentSurface.ALBUM, parent = album), detail!!.tracks)
            state = if (detail!!.tracks.isEmpty()) AlbumLoadState.Empty else AlbumLoadState.Content
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) { state = AlbumLoadState.Error(false); graph.report("Spotify album unavailable: ${error::class.simpleName}") }
    }
    val liked by remember(album) { graph.libraryContent.isLiked(album) }.collectAsState(false)
    return ArchiveGraphPage.Album(album, detail?.let {
        AlbumPageUi(it.album, it.artists, it.year, liked, it.tracks, state == AlbumLoadState.Content, emptyList())
    }, state, emptyList(), iosNowPlaying(graph),
        IosSpotifyDetailHost(graph, IosArtistAlbumHost(graph, host, catalogue, menu, openRoute, back),
            reloadAlbum = { attempt++ }, likedItem = { detail?.album }))
}

@Composable
internal fun iosSpotifyArtistItemsPage(graph: IosSessionGraph, host: IosHost, catalogue: IosCatalogueOwners,
    route: ArchiveRoute, menu: IosItemMenuState, openRoute: (String) -> Unit, back: () -> Unit): ArchiveGraphPage =
    ArchiveGraphPage.ArtistItems(route.artistItemsTitle, route.contentRequest(), route.artistItemsLayout,
        remember(graph) { IosSpotifyArtistRepository(graph) }, iosNowPlaying(graph),
        IosSpotifyDetailHost(graph, IosArtistAlbumHost(graph, host, catalogue, menu, openRoute, back)))

private class IosSpotifyDetailHost(private val graph: IosSessionGraph, private val delegate: ArtistAlbumHost,
    private val reloadArtist: () -> Unit = {}, private val reloadAlbum: () -> Unit = {},
    private val likedItem: () -> ContentItem? = { null }) : ArtistAlbumHost by delegate {
    override fun loadArtistPage(artist: ContentRef) = reloadArtist()
    override fun loadAlbumPage(album: ContentRef) = reloadAlbum()
    override fun playOccurrences(entries: List<ContentOccurrence>, startOccurrenceId: String) {
        if (entries.any { it.item.ref.provider == ContentProvider.SPOTIFY_CATALOG.id }) {
            val index = entries.indexOfFirst { it.occurrenceId == startOccurrenceId }
            if (index >= 0) iosPlaySpotifyTrackList(graph, "catalog", entries, index, false)
        } else delegate.playOccurrences(entries, startOccurrenceId)
    }
    override fun playSong(song: ContentOccurrence, context: List<ContentOccurrence>) {
        if (song.item.ref.provider == ContentProvider.SPOTIFY_CATALOG.id) playOccurrences(listOf(song), song.occurrenceId)
        else delegate.playSong(song, context)
    }
    override fun setLiked(ref: ContentRef, liked: Boolean) {
        val item = likedItem()?.takeIf { it.ref == ref } ?: return delegate.setLiked(ref, liked)
        graph.scope.launch {
            try { graph.libraryContent.setLiked(item, liked) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { graph.report("Spotify library change failed: ${error::class.simpleName}") }
        }
    }
    override fun downloadSongs(songs: List<ContentRef>) {
        if (songs.any { it.provider == ContentProvider.SPOTIFY_CATALOG.id }) graph.report("Import Spotify songs to the library before downloading them.")
        else delegate.downloadSongs(songs)
    }
}
