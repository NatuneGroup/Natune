package moe.rukamori.archivetune.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import moe.rukamori.archivetune.shared.data.provider.YouTubeMoodGenre
import moe.rukamori.archivetune.shared.data.provider.YouTubeRelease
import moe.rukamori.archivetune.shared.data.provider.YouTubeReleaseType
import moe.rukamori.archivetune.shared.data.provider.YouTubeShelf
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRequest
import moe.rukamori.archivetune.shared.domain.repository.ContentSurface
import moe.rukamori.archivetune.shared.ui.home.HomeFeedDesign
import moe.rukamori.archivetune.shared.ui.home.HomeShelf
import moe.rukamori.archivetune.shared.ui.home.HomeSource
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveGraphPage
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveRoute
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveRoutes
import moe.rukamori.archivetune.shared.ui.screens.BrowseShelfUi
import moe.rukamori.archivetune.shared.ui.screens.DiscoveryTab
import moe.rukamori.archivetune.shared.ui.screens.DiscoveryUi
import moe.rukamori.archivetune.shared.ui.screens.MoodGenreUi
import moe.rukamori.archivetune.shared.ui.screens.PagedContentState
import moe.rukamori.archivetune.shared.ui.screens.ReleaseCategory
import moe.rukamori.archivetune.shared.ui.screens.ReleaseUi
import moe.rukamori.archivetune.shared.ui.screens.ReleasesUi
import moe.rukamori.archivetune.shared.ui.screens.rememberPagedContent

/**
 * The shelf pages the desktop catalogue answers: Home, Discovery, Charts, Moods, Browse and Releases. Each binding
 * records the rows its page shows into [DesktopCatalogueOwners], so a row tap can queue exactly those rows.
 */

/** The feed's collection request: one key per page, so its shelves never share rows with another feed. */
private val HOME_REQUEST = ContentRequest(ContentProvider.YOUTUBE, ContentSurface.HOME)
private val DISCOVERY_REQUEST = ContentRequest(ContentProvider.YOUTUBE, ContentSurface.DISCOVERY)
private val CHARTS_REQUEST = ContentRequest(ContentProvider.YOUTUBE, ContentSurface.EXPLORE, text = "charts")

@Composable
internal fun desktopHomePage(graph: DesktopSessionGraph, openRoute: (String) -> Unit): ArchiveGraphPage {
    val feed = graph.catalogueOwners.home
    androidx.compose.runtime.LaunchedEffect(feed) { feed.reload() }
    val snapshot by graph.queue.player.snapshot.collectAsState()
    val shelves = feed.shelves.map { shelf -> shelf.toHomeShelf(graph, HOME_REQUEST, openRoute) }
    SideEffect { shelves.forEach { graph.catalogueOwners.recordShelf(HOME_REQUEST, it.key, it.items) } }
    val page = PagedContentState(
        request = HOME_REQUEST,
        loading = feed.loading,
        initialized = feed.initialized,
        failed = feed.failed,
        continuation = feed.continuation,
    )
    return ArchiveGraphPage.Home(
        source = HomeSource.YOUTUBE,
        availableSources = listOf(HomeSource.YOUTUBE),
        sourceChange = {},
        design = HomeFeedDesign.Classic,
        shelves = shelves,
        page = page,
        loadMore = feed::loadMore,
        retry = feed::reload,
        // The anonymous greeting line; the desktop account name is not resolved for the feed header yet.
        accountName = "",
        activeIds = emptySet(),
        playing = snapshot?.playWhenReady == true,
        refreshing = feed.refreshing,
        refresh = feed::refresh,
    )
}

/** One feed shelf as the screen binds it; "see all" opens the provider's own browse route for the section. */
private fun YouTubeShelf.toHomeShelf(
    graph: DesktopSessionGraph,
    collection: ContentRequest,
    openRoute: (String) -> Unit,
): HomeShelf =
    HomeShelf(
        key = key,
        title = title,
        items = entries.mapIndexed { index, entry -> catalogueOccurrence(collection, entry, index) },
        more = more?.let { section ->
            { openRoute(ArchiveRoutes.browse(browseRequest(ContentSurface.EXPLORE, section))) }
        },
    )

/** One feed shelf as a discovery/charts shelf: its rows queue under the page's collection, "more" browses on. */
private fun YouTubeShelf.toBrowseShelfUi(
    graph: DesktopSessionGraph,
    collection: ContentRequest,
    openRoute: (String) -> Unit,
): BrowseShelfUi =
    BrowseShelfUi(
        key = key,
        title = title,
        collection = collection,
        rows = entries.mapIndexed { index, entry -> catalogueOccurrence(collection, entry, index) },
        more = more?.let { browseRequest(ContentSurface.EXPLORE, it) },
        ranked = numbered,
    )

private fun YouTubeMoodGenre.toUi(surface: ContentSurface): MoodGenreUi =
    MoodGenreUi(title, title, stripeArgb, browseRequest(surface, section))

@Composable
internal fun desktopDiscoveryPage(graph: DesktopSessionGraph, openRoute: (String) -> Unit): ArchiveGraphPage {
    val feed = graph.catalogueOwners.explore
    androidx.compose.runtime.LaunchedEffect(feed) { feed.reload() }
    var tab by remember { mutableStateOf(DiscoveryTab.EXPLORE) }
    val releaseRows = feed.releases.mapIndexed { index, release -> catalogueOccurrence(DISCOVERY_REQUEST, release.entry, index) }
    SideEffect { graph.catalogueOwners.recordShelf(DISCOVERY_REQUEST, "new_releases", releaseRows) }
    val releaseShelf = BrowseShelfUi(
        key = "new_releases",
        title = "New releases",
        collection = DISCOVERY_REQUEST,
        rows = releaseRows,
        more = null,
    )
    val state = DiscoveryUi(
        shelves = if (releaseRows.isEmpty()) emptyList() else listOf(releaseShelf),
        moods = feed.moods.map { it.toUi(ContentSurface.EXPLORE) },
        tab = tab,
        loading = feed.loading,
        failed = feed.failed,
    )
    return ArchiveGraphPage.Discovery(state, { tab = it }, feed::reload, feed::reload)
}

@Composable
internal fun desktopChartsPage(graph: DesktopSessionGraph): ArchiveGraphPage {
    val feed = graph.catalogueOwners.charts
    androidx.compose.runtime.LaunchedEffect(feed) { feed.reload() }
    val shelves = feed.shelves.map { it.toBrowseShelfUi(graph, CHARTS_REQUEST) {} }
    SideEffect { shelves.forEach { graph.catalogueOwners.recordShelf(CHARTS_REQUEST, it.key, it.rows) } }
    return ArchiveGraphPage.Charts(shelves, feed.loading, feed.failed, feed::reload)
}

@Composable
internal fun desktopMoodsPage(graph: DesktopSessionGraph): ArchiveGraphPage {
    val feed = graph.catalogueOwners.explore
    androidx.compose.runtime.LaunchedEffect(feed) { feed.reload() }
    return ArchiveGraphPage.Moods(feed.moods.map { it.toUi(ContentSurface.EXPLORE) }, feed.loading, feed.failed, feed::reload)
}

/**
 * A provider browse page: the section the route names, paged through the typed repository. The provider answers
 * whole sections, so the page is complete when it loads and rows record as one shelf list.
 */
@Composable
internal fun desktopBrowsePage(graph: DesktopSessionGraph, route: ArchiveRoute): ArchiveGraphPage {
    val request = route.contentRequest()
    val presenter = rememberPagedContent(graph.youtubeContent, request)
    val page by presenter.state.collectAsState()
    SideEffect {
        graph.catalogueOwners.recordList(request, page.items)
        graph.catalogueOwners.recordContinuation(request, page.continuation)
    }
    return ArchiveGraphPage.Browse(
        title = route.arguments["title"].orEmpty(),
        collection = request,
        page = page,
        loadMore = presenter::loadMore,
        retry = presenter::retry,
    )
}

@Composable
internal fun desktopReleasesPage(graph: DesktopSessionGraph): ArchiveGraphPage {
    val feed = graph.catalogueOwners.releases
    androidx.compose.runtime.LaunchedEffect(feed) { feed.reload() }
    var category by remember { mutableStateOf(ReleaseCategory.ALL) }
    var query by remember { mutableStateOf("") }
    val state = ReleasesUi(
        releases = feed.releases.map { it.toUi() },
        category = category,
        query = query,
        loading = feed.loading,
        failed = feed.failed,
    )
    return ArchiveGraphPage.Releases(state, { category = it }, { query = it }, feed::reload, null)
}

private fun YouTubeRelease.toUi(): ReleaseUi =
    ReleaseUi(
        item = catalogueOccurrence(RELEASES_REQUEST, entry, 0).item,
        category = when (type) {
            YouTubeReleaseType.ALBUM -> ReleaseCategory.ALBUMS
            YouTubeReleaseType.SINGLE -> ReleaseCategory.SINGLES
            YouTubeReleaseType.EP -> ReleaseCategory.EPS
        },
    )

private val RELEASES_REQUEST = ContentRequest(ContentProvider.YOUTUBE, ContentSurface.EXPLORE, text = "new_releases")
