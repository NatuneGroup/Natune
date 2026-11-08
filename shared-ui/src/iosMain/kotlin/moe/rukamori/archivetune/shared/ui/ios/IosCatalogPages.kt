package moe.rukamori.archivetune.shared.ui.ios

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.ContentRequest
import moe.rukamori.archivetune.shared.domain.repository.QueueStart
import moe.rukamori.archivetune.shared.ui.IosHost
import moe.rukamori.archivetune.shared.ui.host.CatalogPageOwners
import moe.rukamori.archivetune.shared.ui.host.hostCatalogDetailPage
import moe.rukamori.archivetune.shared.ui.host.hostPlayCatalogOccurrence
import moe.rukamori.archivetune.shared.ui.host.hostPlayOccurrence
import moe.rukamori.archivetune.shared.ui.host.hostPlaylistPage
import moe.rukamori.archivetune.shared.ui.host.hostSearchPage
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveDestination
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveGraphPage
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveRoute
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveRoutes
import moe.rukamori.archivetune.shared.ui.news.newsArticlePage
import moe.rukamori.archivetune.shared.ui.news.newsFeedPage
import moe.rukamori.archivetune.shared.ui.screens.rememberPagedContent

/**
 * The catalogue owners the iOS pages share: the shared catalog pages answer the search and detail routes, and the
 * recorded row lists let a tap on a browse or artist-section row play the list the page showed. Both are created once
 * per bindings value, so a page keyed on the route keeps its remembered owner across recompositions.
 */
internal class IosCatalogOwners(graph: IosSessionGraph) {
    val pages = CatalogPageOwners(graph.youtubeContent, graph.pageOwners)
    val lists = IosCatalogueOwners(graph.youtubeContent, graph.youtubeCatalogue, graph.pageOwners)
}

/**
 * The catalogue destinations this host answers: the home feed and the discovery, charts, mood and new-release
 * shelves, search, albums, artists and their sections, playlists and tags, browse sections, the account, and the news,
 * podcast and track-info pages. A destination no owner here answers returns null, so the caller keeps its own pages
 * and the explicit host boundary for the rest.
 */
@Composable
internal fun iosCatalogGraphPage(
    graph: IosSessionGraph,
    host: IosHost,
    owners: IosCatalogOwners,
    menu: IosItemMenuState,
    creation: IosPlaylistCreation,
    tags: IosPlaylistTagsState,
    route: ArchiveRoute,
    back: () -> Unit,
    openRoute: (String) -> Unit,
): ArchiveGraphPage? = when (route.destination) {
    ArchiveDestination.HOME -> iosHomePage(graph, owners.lists, openRoute)
    ArchiveDestination.DISCOVERY -> iosDiscoveryPage(graph, owners.lists)
    ArchiveDestination.CHARTS -> iosChartsPage(graph, owners.lists)
    ArchiveDestination.MOODS -> iosMoodsPage(graph)
    ArchiveDestination.RELEASES -> iosReleasesPage(graph)
    ArchiveDestination.SEARCH, ArchiveDestination.SEARCH_RESULTS ->
        iosProviderSearchPage(graph, owners, route) { openRoute(ArchiveRoutes.musicRecognition()) }
    ArchiveDestination.ALBUM ->
        if (route.arguments["provider"] == ContentProvider.SPOTIFY_CATALOG.id) {
            iosSpotifyAlbumPage(graph, host, owners.lists, route, menu, openRoute, back)
        } else {
            hostCatalogDetailPage(owners.pages, route) { menu.open(it) }
        }
    ArchiveDestination.PLAYLIST ->
        if (route.arguments["playlistType"] == "spotify_playlist" ||
            route.arguments["provider"] == ContentProvider.SPOTIFY_CATALOG.id
        ) {
            iosSpotifyPlaylistPage(graph, route.arguments.getValue("playlistId"), back)
        } else {
            hostCatalogDetailPage(owners.pages, route) { menu.open(it) }
                ?: hostPlaylistPage(graph.pageOwners, route) { occurrence ->
                    // Only an editable local playlist offers the row's remove-from-playlist action.
                    menu.open(
                        occurrence,
                        playlistId = route.arguments["playlistId"].takeIf {
                            route.arguments["playlistType"] == "local_playlist"
                        },
                    )
                }
        }
    ArchiveDestination.ARTIST ->
        if (route.arguments["provider"] == ContentProvider.SPOTIFY_CATALOG.id) {
            iosSpotifyArtistPage(graph, host, owners.lists, route, menu, openRoute, back)
        } else {
            iosArtistPage(graph, host, owners.lists, route, menu, openRoute, back)
        }
    ArchiveDestination.ARTIST_SONGS -> iosArtistSongsPage(graph, host, owners.lists, route, menu, openRoute, back)
    ArchiveDestination.ARTIST_ALBUMS -> iosArtistAlbumsPage(graph, host, owners.lists, route, menu, openRoute, back)
    ArchiveDestination.ARTIST_ITEMS ->
        if (route.arguments["provider"] == ContentProvider.SPOTIFY_CATALOG.id) {
            iosSpotifyArtistItemsPage(graph, host, owners.lists, route, menu, openRoute, back)
        } else iosArtistItemsPage(graph, host, owners.lists, route, menu, openRoute, back)
    ArchiveDestination.BROWSE, ArchiveDestination.YOUTUBE_BROWSE -> iosBrowsePage(graph, owners.lists, route)
    ArchiveDestination.ACCOUNT -> iosAccountPage(graph, menu)
    ArchiveDestination.PLAYLISTS -> iosPlaylistsPage(graph, host, creation, tags, menu)
    ArchiveDestination.TAGS -> iosTagsPage(graph, tags)
    ArchiveDestination.NEWS -> newsFeedPage(graph.newsFeed)
    ArchiveDestination.ARTICLE -> newsArticlePage(graph.newsFeed, route.arguments.getValue("newsId"))
    ArchiveDestination.PODCASTS -> iosPodcastsPage(graph)
    ArchiveDestination.PODCAST -> iosPodcastPage(graph, route.arguments.getValue("browseId"))
    ArchiveDestination.TRACK_INFO -> iosTrackInfoPage(graph, route.arguments.getValue("trackId"), route.arguments["provider"])
    ArchiveDestination.MEDIA_INFO -> iosMediaInfoPage(graph, host, route.arguments.getValue("mediaId"), route.arguments["provider"])
    else -> null
}

/**
 * One browse route, such as a mood, genre or chart section, paged by the typed YouTube catalogue. The shown rows
 * record with the catalogue owner on every composition, so a tap plays the list the listener saw, read to its end
 * within the shared finite-completion bounds first.
 */
@Composable
private fun iosBrowsePage(graph: IosSessionGraph, lists: IosCatalogueOwners, route: ArchiveRoute): ArchiveGraphPage {
    val request = route.contentRequest()
    val presenter = rememberPagedContent(graph.youtubeContent, request)
    val page by presenter.state.collectAsState()
    SideEffect { lists.record(request, page.items, page.continuation) }
    return ArchiveGraphPage.Browse(
        title = route.arguments["title"].orEmpty(),
        collection = request,
        page = page,
        loadMore = presenter::loadMore,
        retry = presenter::retry,
    )
}

/**
 * A tap on a playable row: the recorded browse and artist-section lists answer first, then the shared catalog pages
 * (search and detail rows), then the library owners. A collection none of them recorded falls to the library owners'
 * own refusal.
 */
internal fun iosPlayOccurrence(graph: IosSessionGraph, owners: IosCatalogOwners, collection: ContentRequest?, start: QueueStart) {
    if (iosPlaySpotifyOccurrence(graph, collection, start)) return
    if (collection != null && owners.lists.playOccurrence(collection, start)) return
    if (collection != null && hostPlayCatalogOccurrence(owners.pages, collection, start)) return
    hostPlayOccurrence(graph.pageOwners, collection, start)
}

/**
 * The row menu for a content reference the statistics or recap page names. A song's menu needs its library row, so a
 * song the library does not hold reports the failure notice instead of a menu over made-up song data. Album, artist
 * and playlist rows open with the reference alone: their menu never shows the title and only offers an action it can
 * commit, such as a share link it can really build.
 */
internal fun iosOpenRefMenu(graph: IosSessionGraph, menu: IosItemMenuState, source: String, ref: ContentRef) {
    graph.scope.launch {
        val item = when (ref.kind) {
            ContentKind.SONG, ContentKind.EPISODE -> try {
                graph.library.findSong(ref.id, ref.provider) ?: graph.library.findSong(ref.id, null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            else -> ContentItem(ref, ref.id)
        }
        withContext(Dispatchers.Main) {
            if (item == null) {
                menu.failed()
            } else {
                menu.open(ContentOccurrence("$source:${ref.provider}:${ref.id}", item))
            }
        }
    }
}
