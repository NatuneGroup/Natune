package moe.rukamori.archivetune.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.data.provider.YouTubeArtistDetail
import moe.rukamori.archivetune.shared.data.provider.YouTubeWatchTarget
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.ContentRequest
import moe.rukamori.archivetune.shared.domain.repository.ContentSection
import moe.rukamori.archivetune.shared.domain.repository.ContentShelf
import moe.rukamori.archivetune.shared.domain.repository.ContentPage
import moe.rukamori.archivetune.shared.domain.repository.ContentSurface
import moe.rukamori.archivetune.shared.domain.repository.QueueCollectionKind
import moe.rukamori.archivetune.shared.domain.repository.QueueLoadRequest
import moe.rukamori.archivetune.shared.domain.repository.QueueStart
import moe.rukamori.archivetune.shared.domain.settings.ArtistSongSortDescendingKey
import moe.rukamori.archivetune.shared.domain.settings.ArtistSongSortTypeKey
import moe.rukamori.archivetune.shared.domain.library.AlbumSortType
import moe.rukamori.archivetune.shared.domain.library.LibraryAlbumRow
import moe.rukamori.archivetune.shared.domain.library.SongSortType
import moe.rukamori.archivetune.shared.domain.library.LibrarySortState
import moe.rukamori.archivetune.shared.domain.library.writeTo
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveGraphPage
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveRoute
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveRoutes
import moe.rukamori.archivetune.shared.ui.screens.artistalbum.ArtistAlbumHost
import moe.rukamori.archivetune.shared.ui.screens.artistalbum.ArtistAlbumMenuTarget
import moe.rukamori.archivetune.shared.ui.screens.artistalbum.ArtistAlbumNowPlaying
import moe.rukamori.archivetune.shared.ui.screens.artistalbum.ArtistBlockUiState
import moe.rukamori.archivetune.shared.ui.screens.artistalbum.ArtistLibraryUi
import moe.rukamori.archivetune.shared.ui.screens.artistalbum.ArtistPageUi
import moe.rukamori.archivetune.shared.ui.screens.artistalbum.ArtistReleaseType
import moe.rukamori.archivetune.shared.ui.screens.artistalbum.ArtistReleaseUi
import moe.rukamori.archivetune.shared.ui.screens.artistalbum.ArtistShelfLayout
import moe.rukamori.archivetune.shared.ui.screens.artistalbum.ArtistShelfUi
import moe.rukamori.archivetune.shared.ui.screens.artistalbum.ArtistSongSort
import moe.rukamori.archivetune.shared.ui.screens.artistalbum.ArtistSongSortType
import moe.rukamori.archivetune.shared.data.provider.YouTubeReleaseType
import moe.rukamori.archivetune.shared.data.provider.YouTubeRelease

/**
 * The artist routes the desktop answers: the remote artist page, its library songs and albums, and one complete
 * provider section. All data, queue, library and menu effects go through the one host below; navigation stays with
 * the shell's wrapper.
 */
private val ARTIST_SHELF_REQUEST_SURFACE = ContentSurface.ARTIST

/** The artist reference the route names; the provider arg keeps a Spotify-catalog artist apart. */
private fun artistRef(route: ArchiveRoute): ContentRef? {
    val id = route.arguments["artistId"] ?: route.arguments["id"] ?: return null
    val provider = route.arguments["provider"]?.let { ContentProvider.fromId(it) } ?: ContentProvider.YOUTUBE
    return ContentRef(provider.id, ContentKind.ARTIST, id)
}

@Composable
internal fun desktopArtistPage(
    graph: DesktopSessionGraph,
    route: ArchiveRoute,
    menu: DesktopItemMenuState,
): ArchiveGraphPage {
    val artist = artistRef(route) ?: return ArchiveGraphPage.Host
    val owner = remember(artist) { graph.catalogueOwners.artist(artist) }
    LaunchedEffect(owner) { owner.load() }
    val detail = owner.page
    val snapshot by graph.queue.player.snapshot.collectAsState()
    val blockState by remember(artist) { graph.artistBlocks.observeBlocked(artist) }.collectAsState(null)
    val subscribed by remember(artist) { graph.libraryContent.isLiked(artist) }.collectAsState(false)
    val librarySongs by remember(artist) {
        graph.libraryContent.artistSongs(artist, LibrarySortState(SongSortType.NAME, descending = false))
    }.collectAsState(null)
    val libraryAlbums by remember(artist) {
        graph.libraryContent.artistAlbums(artist, LibrarySortState(AlbumSortType.NAME, descending = false))
    }.collectAsState(null)
    val nowPlaying = ArtistAlbumNowPlaying(
        songId = snapshot?.mediaId,
        albumId = null,
        isPlaying = snapshot?.playWhenReady == true,
    )
    return ArchiveGraphPage.Artist(
        artist = artist,
        page = detail?.toUi(graph, artist),
        pageFailed = owner.failed,
        library = ArtistLibraryUi(
            name = detail?.artist?.title.orEmpty(),
            artworkUri = detail?.artist?.artworkUri,
            isLocal = artist.provider == ContentProvider.LOCAL.id,
            subscribed = subscribed,
            songs = librarySongs.orEmpty(),
            albums = libraryAlbums.orEmpty().map { it.toReleaseUi() },
        ),
        blockState = when (blockState) {
            null -> ArtistBlockUiState.Loading
            else -> ArtistBlockUiState.Known(blockState == true)
        },
        nowPlaying = nowPlaying,
        host = DesktopArtistAlbumHost(graph, artist, menu),
    )
}

/** A saved-library album row as an artist-page release; the library carries no release-type or year split. */
private fun LibraryAlbumRow.toReleaseUi(): ArtistReleaseUi =
    ArtistReleaseUi(
        album = album.ref,
        title = album.title,
        artworkUri = album.artworkUri,
        year = null,
        type = ArtistReleaseType.ALBUM,
    )

/** The provider page as the screen binds it. Shelf rows queue under the artist's own collection. */
private fun YouTubeArtistDetail.toUi(graph: DesktopSessionGraph, artist: ContentRef): ArtistPageUi {
    val collection = ContentRequest(ContentProvider.YOUTUBE, ContentSurface.ARTIST, parent = artist)
    return ArtistPageUi(
        artist = this.artist.toCatalogueOccurrence("artist:${artist.id}", 0).item,
        description = description,
        monthlyListenersText = monthlyListenersText,
        subscribersText = subscribersText,
        shelves = shelves.mapIndexed { index, section ->
            val rows = section.entries.mapIndexed { row, entry ->
                catalogueOccurrence(collection, entry, index * 10_000 + row)
            }
            graph.catalogueOwners.recordShelf(collection, section.title, rows)
            ArtistShelfUi(
                shelf = ContentShelf(
                    section = section.more ?: ContentSection("${artist.id}#$index", null),
                    title = section.title,
                    page = ContentPage(rows),
                ),
                layout = if (section.grid) ArtistShelfLayout.GRID else ArtistShelfLayout.LIST,
                more = section.more?.let { ContentRequest(ContentProvider.YOUTUBE, ContentSurface.ARTIST, parent = artist, section = it) },
            )
        },
        releases = releases.map { it.toReleaseUi() },
        shareLink = shareLink,
        play = play?.toWatchRequest(artist),
        shuffle = shuffle?.toWatchRequest(artist),
        radio = radio?.toWatchRequest(artist),
    )
}

private fun YouTubeRelease.toReleaseUi(): ArtistReleaseUi =
    ArtistReleaseUi(
        album = ContentRef(ContentProvider.YOUTUBE.id, ContentKind.ALBUM, entry.id),
        title = entry.title,
        artworkUri = entry.artworkUri,
        year = year,
        type = when (type) {
            YouTubeReleaseType.ALBUM -> ArtistReleaseType.ALBUM
            YouTubeReleaseType.SINGLE -> ArtistReleaseType.SINGLE
            YouTubeReleaseType.EP -> ArtistReleaseType.EP
        },
    )

/** A provider watch/radio collection: the video id in the text, the radio playlist in the section. */
private fun YouTubeWatchTarget.toWatchRequest(artist: ContentRef): ContentRequest =
    ContentRequest(
        ContentProvider.YOUTUBE,
        ARTIST_SHELF_REQUEST_SURFACE,
        text = videoId,
        parent = artist,
        section = playlistId?.takeIf { it.isNotBlank() }?.let { ContentSection(it, params) },
    )

@Composable
internal fun desktopArtistSongsPage(
    graph: DesktopSessionGraph,
    route: ArchiveRoute,
    menu: DesktopItemMenuState,
): ArchiveGraphPage {
    val artist = artistRef(route) ?: return ArchiveGraphPage.Host
    val snapshot by graph.settings.snapshots.collectAsState()
    val sort = snapshot?.let {
        ArtistSongSort(
            type = it.get(ArtistSongSortTypeKey)?.let { name -> ArtistSongSortType.entries.firstOrNull { t -> t.name == name } }
                ?: ArtistSongSortType.CREATE_DATE,
            descending = it.get(ArtistSongSortDescendingKey) ?: true,
        )
    } ?: ArtistSongSort(ArtistSongSortType.CREATE_DATE, descending = true)
    val songs by remember(artist, sort) { graph.libraryContent.artistSongs(artist, sort.toLibrarySort()) }.collectAsState(null)
    val playing by graph.queue.player.snapshot.collectAsState()
    val owner = remember(artist) { graph.catalogueOwners.artist(artist) }
    LaunchedEffect(owner) { owner.load() }
    return ArchiveGraphPage.ArtistSongs(
        artistName = owner.page?.artist?.title.orEmpty(),
        songs = songs.orEmpty(),
        sort = sort,
        nowPlaying = ArtistAlbumNowPlaying(playing?.mediaId, null, playing?.playWhenReady == true),
        host = DesktopArtistAlbumHost(graph, artist, menu),
    )
}

@Composable
internal fun desktopArtistAlbumsPage(
    graph: DesktopSessionGraph,
    route: ArchiveRoute,
    menu: DesktopItemMenuState,
): ArchiveGraphPage {
    val artist = artistRef(route) ?: return ArchiveGraphPage.Host
    val owner = remember(artist) { graph.catalogueOwners.artist(artist) }
    LaunchedEffect(owner) { owner.load() }
    val playing by graph.queue.player.snapshot.collectAsState()
    return ArchiveGraphPage.ArtistAlbums(
        title = owner.page?.artist?.title.orEmpty(),
        albums = owner.page?.releases?.map { it.toReleaseUi() }.orEmpty(),
        nowPlaying = ArtistAlbumNowPlaying(playing?.mediaId, null, playing?.playWhenReady == true),
        host = DesktopArtistAlbumHost(graph, artist, menu),
    )
}

@Composable
internal fun desktopArtistItemsPage(
    graph: DesktopSessionGraph,
    route: ArchiveRoute,
    menu: DesktopItemMenuState,
): ArchiveGraphPage {
    val request = route.contentRequest()
    val presenter = moe.rukamori.archivetune.shared.ui.screens.rememberPagedContent(graph.youtubeContent, request)
    val page by presenter.state.collectAsState()
    androidx.compose.runtime.SideEffect {
        graph.catalogueOwners.recordList(request, page.items)
        graph.catalogueOwners.recordContinuation(request, page.continuation)
    }
    val playing by graph.queue.player.snapshot.collectAsState()
    val artist = request.parent ?: ContentRef(ContentProvider.YOUTUBE.id, ContentKind.ARTIST, "")
    return ArchiveGraphPage.ArtistItems(
        title = route.artistItemsTitle,
        request = request,
        layout = route.artistItemsLayout,
        repository = graph.youtubeContent,
        nowPlaying = ArtistAlbumNowPlaying(playing?.mediaId, null, playing?.playWhenReady == true),
        host = DesktopArtistAlbumHost(graph, artist, menu),
    )
}

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
 * The one host every artist and album page shares. Every effect is real: the queue, the library, the blocklist, the
 * system share/open and the item menu owners. No member defaults to a silent success — what the desktop cannot do is
 * refused through the diagnostic line.
 */
internal class DesktopArtistAlbumHost(
    private val graph: DesktopSessionGraph,
    private val artist: ContentRef,
    private val menu: DesktopItemMenuState,
) : ArtistAlbumHost {
    override fun back() {}
    override fun backToMain() {}
    override fun openContent(ref: ContentRef) {}
    override fun openArtistSongs(artist: ContentRef) {}
    override fun openArtistAlbums(artist: ContentRef) {}
    override fun openArtistSection(request: ContentRequest, title: String, layout: ArtistShelfLayout) {}
    override fun openDownloads() {}

    override fun loadArtistPage(artist: ContentRef) {
        graph.catalogueOwners.artist(artist).load()
    }

    override fun loadAlbumPage(album: ContentRef) {
        // Album pages mount through the shared catalogue detail host, which reloads on retry itself.
    }

    override fun playOccurrences(entries: List<ContentOccurrence>, startOccurrenceId: String) {
        graph.scope.launch {
            try {
                graph.queue.replace(entries, startOccurrenceId, playWhenReady = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                graph.report("the songs could not be played")
            }
        }
    }

    override fun playSong(song: ContentOccurrence, context: List<ContentOccurrence>) {
        if (song.item.ref.provider != ContentProvider.YOUTUBE.id) {
            playOccurrences(context.ifEmpty { listOf(song) }, song.occurrenceId)
            return
        }
        // A provider song starts that provider's watch queue for the song, never the page's loaded prefix.
        graph.scope.launch {
            try {
                graph.queue.load(
                    QueueLoadRequest(
                        collection = ContentRequest(ContentProvider.YOUTUBE, ContentSurface.ARTIST, text = song.item.ref.id),
                        kind = QueueCollectionKind.RADIO,
                        start = QueueStart(song.item.ref, song.occurrenceId, song.setVideoId),
                        playWhenReady = true,
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                graph.report("the song's radio could not be started")
            }
        }
    }

    override fun playCollection(request: QueueLoadRequest) {
        graph.scope.launch {
            try {
                graph.queue.load(request)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                graph.report("the collection could not be loaded")
            }
        }
    }

    override fun togglePlayPause() {
        val snapshot = graph.queue.player.snapshot.value
        if (snapshot?.playWhenReady == true) graph.queue.player.pause() else graph.queue.player.play()
    }

    override fun setLiked(ref: ContentRef, liked: Boolean) {
        graph.scope.launch {
            try {
                graph.libraryContent.setLiked(ContentItem(ref, ref.id), liked)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                graph.report("the library change could not be written")
            }
        }
    }

    override fun setArtistBlocked(artist: ContentRef, name: String, blocked: Boolean) {
        graph.scope.launch {
            try {
                graph.artistBlocks.setBlocked(artist, name, blocked)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                graph.report("the blocklist change could not be written")
            }
        }
    }

    override fun downloadSongs(songs: List<ContentRef>) {
        graph.report("downloads are not available on this host")
    }

    override fun removeDownloads(songs: List<ContentRef>) {
        graph.report("downloads are not available on this host")
    }

    override fun share(link: String) {
        try {
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI(link)) else copyLink(link)
        } catch (_: Exception) {
            copyLink(link)
        }
    }

    override fun copyLink(link: String) {
        try {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(link), null)
        } catch (_: Exception) {
            graph.report("the link could not be copied")
        }
    }

    override fun openMenu(target: ArtistAlbumMenuTarget) {
        val occurrence = target.occurrence
            ?: ContentOccurrence("menu:${target.ref.provider}:${target.ref.kind}:${target.ref.id}", ContentItem(target.ref, target.ref.id))
        menu.open(occurrence)
    }

    override fun openSelectionMenu(songs: List<ContentOccurrence>) {
        songs.firstOrNull()?.let { menu.open(it) }
    }

    override fun setArtistSongSort(sort: ArtistSongSort) {
        graph.scope.launch {
            try {
                graph.settings.edit {
                    LibrarySortState(SongSortType.valueOf(sort.type.name), sort.descending)
                        .writeTo(this, ArtistSongSortTypeKey, ArtistSongSortDescendingKey)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                graph.report("the song order could not be saved")
            }
        }
    }
}
