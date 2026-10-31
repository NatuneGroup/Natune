package moe.rukamori.archivetune.shared.ui.ios

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.library.AlbumSortType
import moe.rukamori.archivetune.shared.domain.library.LibraryAlbumRow
import moe.rukamori.archivetune.shared.domain.library.LibrarySortState
import moe.rukamori.archivetune.shared.domain.library.SongSortType
import moe.rukamori.archivetune.shared.domain.library.writeTo
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.ContentRequest
import moe.rukamori.archivetune.shared.domain.repository.ContentSurface
import moe.rukamori.archivetune.shared.domain.repository.QueueCollectionKind
import moe.rukamori.archivetune.shared.domain.repository.QueueLoadRequest
import moe.rukamori.archivetune.shared.domain.repository.QueueStart
import moe.rukamori.archivetune.shared.domain.settings.ArtistSongSortDescendingKey
import moe.rukamori.archivetune.shared.domain.settings.ArtistSongSortTypeKey
import moe.rukamori.archivetune.shared.ui.IosHost
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveGraphPage
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveRoute
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveRoutes
import moe.rukamori.archivetune.shared.ui.screens.artistalbum.ArtistAlbumHost
import moe.rukamori.archivetune.shared.ui.screens.artistalbum.ArtistAlbumMenuTarget
import moe.rukamori.archivetune.shared.ui.screens.artistalbum.ArtistAlbumNowPlaying
import moe.rukamori.archivetune.shared.ui.screens.artistalbum.ArtistBlockUiState
import moe.rukamori.archivetune.shared.ui.screens.artistalbum.ArtistLibraryUi
import moe.rukamori.archivetune.shared.ui.screens.artistalbum.ArtistReleaseType
import moe.rukamori.archivetune.shared.ui.screens.artistalbum.ArtistReleaseUi
import moe.rukamori.archivetune.shared.ui.screens.artistalbum.ArtistShelfLayout
import moe.rukamori.archivetune.shared.ui.screens.artistalbum.ArtistSongSort
import moe.rukamori.archivetune.shared.ui.screens.artistalbum.ArtistSongSortType
import moe.rukamori.archivetune.shared.ui.screens.rememberPagedContent

/** The artist reference the route names; the provider argument keeps a local or catalog artist apart. */
internal fun iosArtistRef(route: ArchiveRoute): ContentRef? {
    val id = route.arguments["artistId"] ?: route.arguments["id"] ?: return null
    val provider = route.arguments["provider"]?.let { ContentProvider.fromId(it) } ?: ContentProvider.YOUTUBE
    return ContentRef(provider.id, ContentKind.ARTIST, id)
}

/**
 * The artist page over the library, the subscription flag and the blocklist. This host has no artist-page shelf
 * transport, so the provider part of the page is reported as not loaded (`pageFailed`) instead of an empty page, and
 * the library songs, albums, follow and block controls stay usable.
 */
@Composable
internal fun iosArtistPage(
    graph: IosSessionGraph,
    host: IosHost,
    route: ArchiveRoute,
    menu: IosItemMenuState,
    openRoute: (String) -> Unit,
    back: () -> Unit,
): ArchiveGraphPage {
    val artist = iosArtistRef(route) ?: return ArchiveGraphPage.Host
    val blocked by remember(artist) { graph.artistBlocks.observeBlocked(artist) }.collectAsState(null)
    val subscribed by remember(artist) { graph.libraryContent.isLiked(artist) }.collectAsState(false)
    val songs by remember(artist) {
        graph.libraryContent.artistSongs(artist, LibrarySortState(SongSortType.NAME, descending = false))
    }.collectAsState(null)
    val albums by remember(artist) {
        graph.libraryContent.artistAlbums(artist, LibrarySortState(AlbumSortType.NAME, descending = false))
    }.collectAsState(null)
    val savedSongs = songs.orEmpty()
    val savedAlbums = albums.orEmpty()
    return ArchiveGraphPage.Artist(
        artist = artist,
        page = null,
        pageFailed = true,
        library = ArtistLibraryUi(
            name = artistNameOf(artist, savedSongs.map { it.item } + savedAlbums.map { it.album }),
            artworkUri = null,
            isLocal = artist.provider == ContentProvider.LOCAL.id,
            subscribed = subscribed,
            songs = savedSongs,
            albums = savedAlbums.map { it.toReleaseUi() },
        ),
        blockState = when (blocked) {
            null -> ArtistBlockUiState.Loading
            else -> ArtistBlockUiState.Known(blocked == true)
        },
        nowPlaying = iosNowPlaying(graph),
        host = IosArtistAlbumHost(graph, host, menu, openRoute, back),
    )
}

@Composable
internal fun iosArtistSongsPage(
    graph: IosSessionGraph,
    host: IosHost,
    route: ArchiveRoute,
    menu: IosItemMenuState,
    openRoute: (String) -> Unit,
    back: () -> Unit,
): ArchiveGraphPage {
    val artist = iosArtistRef(route) ?: return ArchiveGraphPage.Host
    val snapshot by graph.settings.snapshots.collectAsState()
    val sort = ArtistSongSort(
        type = snapshot?.get(ArtistSongSortTypeKey)
            ?.let { name -> ArtistSongSortType.entries.firstOrNull { it.name == name } }
            ?: ArtistSongSortType.CREATE_DATE,
        descending = snapshot?.get(ArtistSongSortDescendingKey) ?: true,
    )
    val songs by remember(artist, sort) { graph.libraryContent.artistSongs(artist, sort.toLibrarySort()) }
        .collectAsState(null)
    val saved = songs.orEmpty()
    return ArchiveGraphPage.ArtistSongs(
        artistName = artistNameOf(artist, saved.map { it.item }).orEmpty(),
        songs = saved,
        sort = sort,
        nowPlaying = iosNowPlaying(graph),
        host = IosArtistAlbumHost(graph, host, menu, openRoute, back),
    )
}

/** The artist's saved albums. The provider discography needs the artist-page transport this host does not have. */
@Composable
internal fun iosArtistAlbumsPage(
    graph: IosSessionGraph,
    host: IosHost,
    route: ArchiveRoute,
    menu: IosItemMenuState,
    openRoute: (String) -> Unit,
    back: () -> Unit,
): ArchiveGraphPage {
    val artist = iosArtistRef(route) ?: return ArchiveGraphPage.Host
    val albums by remember(artist) {
        graph.libraryContent.artistAlbums(artist, LibrarySortState(AlbumSortType.NAME, descending = false))
    }.collectAsState(null)
    val saved = albums.orEmpty()
    return ArchiveGraphPage.ArtistAlbums(
        title = artistNameOf(artist, saved.map { it.album }).orEmpty(),
        albums = saved.map { it.toReleaseUi() },
        nowPlaying = iosNowPlaying(graph),
        host = IosArtistAlbumHost(graph, host, menu, openRoute, back),
    )
}

/** One complete provider section, paged by the typed YouTube catalogue; its rows record so a tap plays the list. */
@Composable
internal fun iosArtistItemsPage(
    graph: IosSessionGraph,
    host: IosHost,
    catalogue: IosCatalogueOwners,
    route: ArchiveRoute,
    menu: IosItemMenuState,
    openRoute: (String) -> Unit,
    back: () -> Unit,
): ArchiveGraphPage {
    val request = route.contentRequest()
    val presenter = rememberPagedContent(graph.youtubeContent, request)
    val page by presenter.state.collectAsState()
    SideEffect { catalogue.record(request, page.items, page.continuation) }
    return ArchiveGraphPage.ArtistItems(
        title = route.artistItemsTitle,
        request = request,
        layout = route.artistItemsLayout,
        repository = graph.youtubeContent,
        nowPlaying = iosNowPlaying(graph),
        host = IosArtistAlbumHost(graph, host, menu, openRoute, back),
    )
}

@Composable
private fun iosNowPlaying(graph: IosSessionGraph): ArtistAlbumNowPlaying {
    val playing by graph.player.snapshot.collectAsState()
    return ArtistAlbumNowPlaying(playing?.mediaId, null, playing?.playWhenReady == true)
}

/** The name a saved item credits the artist under, or null while no saved item names this artist id. */
internal fun artistNameOf(artist: ContentRef, items: List<ContentItem>): String? =
    items.firstNotNullOfOrNull { item -> item.artists.firstOrNull { it.id == artist.id }?.name }

/** A saved-library album row as an artist-page release; the library carries no release-type or year split. */
private fun LibraryAlbumRow.toReleaseUi(): ArtistReleaseUi =
    ArtistReleaseUi(album = album.ref, title = album.title, artworkUri = album.artworkUri, year = null,
        type = ArtistReleaseType.ALBUM)

private fun ArtistSongSort.toLibrarySort(): LibrarySortState<SongSortType> =
    LibrarySortState(
        when (type) {
            ArtistSongSortType.CREATE_DATE -> SongSortType.CREATE_DATE
            ArtistSongSortType.NAME -> SongSortType.NAME
            ArtistSongSortType.PLAY_TIME -> SongSortType.PLAY_TIME
        },
        descending,
    )

/**
 * The one host the iOS artist pages share. Queue, library, blocklist, share sheet, pasteboard and item menu effects are
 * real. Downloads and the remote artist page are not connected here, so those requests report the unavailable notice
 * instead of succeeding silently. Navigation is wrapped by the shell's navigation host.
 */
internal class IosArtistAlbumHost(
    private val graph: IosSessionGraph,
    private val host: IosHost,
    private val menu: IosItemMenuState,
    private val openRoute: (String) -> Unit,
    private val backRequest: () -> Unit,
) : ArtistAlbumHost {
    override fun back() = backRequest()
    override fun backToMain() = openRoute("home")
    override fun openContent(ref: ContentRef) = openRoute(ArchiveRoutes.content(ref))
    override fun openArtistSongs(artist: ContentRef) = openRoute(ArchiveRoutes.artistSongs(artist))
    override fun openArtistAlbums(artist: ContentRef) = openRoute(ArchiveRoutes.artistAlbums(artist))
    override fun openArtistSection(request: ContentRequest, title: String, layout: ArtistShelfLayout) =
        openRoute(ArchiveRoutes.artistItems(request, title, layout))
    override fun openDownloads() = menu.refuse()

    override fun loadArtistPage(artist: ContentRef) = menu.refuse()

    override fun loadAlbumPage(album: ContentRef) {
        // Album pages mount through the shared catalogue detail page, which reloads on retry itself.
    }

    override fun playOccurrences(entries: List<ContentOccurrence>, startOccurrenceId: String) =
        launch("the songs could not be played") {
            graph.queue.replace(entries, startOccurrenceId, playWhenReady = true)
        }

    override fun playSong(song: ContentOccurrence, context: List<ContentOccurrence>) {
        if (song.item.ref.provider != ContentProvider.YOUTUBE.id) {
            playOccurrences(context.ifEmpty { listOf(song) }, song.occurrenceId)
            return
        }
        // A provider song starts that provider's watch queue for the song, never the page's loaded prefix.
        launch("the song's radio could not be started") {
            graph.queue.load(
                QueueLoadRequest(
                    collection = ContentRequest(ContentProvider.YOUTUBE, ContentSurface.ARTIST, text = song.item.ref.id),
                    kind = QueueCollectionKind.RADIO,
                    start = QueueStart(song.item.ref, song.occurrenceId, song.setVideoId),
                    playWhenReady = true,
                ),
            )
        }
    }

    override fun playCollection(request: QueueLoadRequest) =
        launch("the collection could not be loaded") { graph.queue.load(request) }

    override fun togglePlayPause() {
        if (graph.player.snapshot.value?.playWhenReady == true) graph.player.pause() else graph.player.play()
    }

    override fun setLiked(ref: ContentRef, liked: Boolean) =
        launch("the library change could not be written") { graph.libraryContent.setLiked(ContentItem(ref, ref.id), liked) }

    override fun setArtistBlocked(artist: ContentRef, name: String, blocked: Boolean) =
        launch("the blocklist change could not be written") { graph.artistBlocks.setBlocked(artist, name, blocked) }

    override fun downloadSongs(songs: List<ContentRef>) = menu.refuse()
    override fun removeDownloads(songs: List<ContentRef>) = menu.refuse()

    override fun share(link: String) = iosShareText(host, link)
    override fun copyLink(link: String) = copyToIosClipboard(link)

    override fun openMenu(target: ArtistAlbumMenuTarget) {
        val occurrence = target.occurrence
            ?: ContentOccurrence("menu:${target.ref.provider}:${target.ref.kind}:${target.ref.id}", ContentItem(target.ref, target.ref.id))
        menu.open(occurrence)
    }

    override fun openSelectionMenu(songs: List<ContentOccurrence>) {
        songs.firstOrNull()?.let { menu.open(it) }
    }

    override fun setArtistSongSort(sort: ArtistSongSort) =
        launch("the song order could not be saved") {
            graph.settings.edit {
                LibrarySortState(SongSortType.valueOf(sort.type.name), sort.descending)
                    .writeTo(this, ArtistSongSortTypeKey, ArtistSongSortDescendingKey)
            }
        }

    private fun launch(refusal: String, block: suspend () -> Unit) {
        graph.scope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                graph.report("$refusal: ${failure::class.simpleName}")
            }
        }
    }
}
