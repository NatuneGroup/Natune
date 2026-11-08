package moe.rukamori.archivetune.shared.ui.ios

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.data.provider.YouTubeCatalogueTransport
import moe.rukamori.archivetune.shared.data.provider.filterBlockedSongs
import moe.rukamori.archivetune.shared.data.provider.YouTubeEntry
import moe.rukamori.archivetune.shared.data.provider.YouTubeExplore
import moe.rukamori.archivetune.shared.data.provider.YouTubeFeedChip
import moe.rukamori.archivetune.shared.data.provider.YouTubeMoodGenre
import moe.rukamori.archivetune.shared.data.provider.YouTubeRelease
import moe.rukamori.archivetune.shared.data.provider.YouTubeReleaseType
import moe.rukamori.archivetune.shared.data.provider.YouTubeShelf
import moe.rukamori.archivetune.shared.data.provider.YouTubeShelfPage
import moe.rukamori.archivetune.shared.data.provider.selectYouTubeFeedChip
import moe.rukamori.archivetune.shared.data.provider.stableKey
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.ContentRequest
import moe.rukamori.archivetune.shared.domain.repository.ContentSection
import moe.rukamori.archivetune.shared.domain.repository.ContentSurface
import moe.rukamori.archivetune.shared.domain.settings.HomeSourceKey
import moe.rukamori.archivetune.shared.domain.settings.BlockedSongIdsKey
import moe.rukamori.archivetune.shared.domain.settings.ShowHomeCategoryChipsKey
import moe.rukamori.archivetune.shared.domain.settings.parseBlockedSongRefs
import moe.rukamori.archivetune.shared.ui.home.HomeFeedDesign
import moe.rukamori.archivetune.shared.ui.home.HomeShelf
import moe.rukamori.archivetune.shared.ui.home.resolvedHomeSources
import moe.rukamori.archivetune.shared.ui.home.resolvedHomeSource
import moe.rukamori.archivetune.shared.domain.settings.ActiveHomeSourcesKey
import moe.rukamori.archivetune.shared.domain.settings.SpotifySpDcKey
import moe.rukamori.archivetune.shared.ui.home.HomeSource
import moe.rukamori.archivetune.shared.ui.home.variants.HomeVariantChip
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveGraphPage
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveRoutes
import moe.rukamori.archivetune.shared.ui.resources.Res
import moe.rukamori.archivetune.shared.ui.resources.ios_transports_explore_failed
import moe.rukamori.archivetune.shared.ui.resources.ios_transports_feed_failed
import moe.rukamori.archivetune.shared.ui.resources.ios_transports_releases_failed
import moe.rukamori.archivetune.shared.ui.resources.m4_new_releases
import moe.rukamori.archivetune.shared.ui.screens.BrowseShelfUi
import moe.rukamori.archivetune.shared.ui.screens.DiscoveryTab
import moe.rukamori.archivetune.shared.ui.screens.DiscoveryUi
import moe.rukamori.archivetune.shared.ui.screens.MoodGenreUi
import moe.rukamori.archivetune.shared.ui.screens.PagedContentState
import moe.rukamori.archivetune.shared.ui.screens.ReleaseCategory
import moe.rukamori.archivetune.shared.ui.screens.ReleaseUi
import moe.rukamori.archivetune.shared.ui.screens.ReleasesUi
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/**
 * The iOS shelf pages: Home, Discovery, Charts, Moods and New releases, all reading the one InnerTubeX session
 * through [YouTubeCatalogueTransport]. Every page records the rows it shows with [IosCatalogueOwners], so a tap
 * plays the shelf or list the listener actually saw, and a failed read shows the page's failed state instead of an
 * empty shelf that looks loaded.
 *
 * The three feed owners keep the load state the shared pages expect. They are created per page composition on the
 * composition's own scope, so leaving the route cancels an in-flight read and no stale feed outlives its page.
 */

/** The home feed's collection: one key per page, so its shelves never share rows with another feed. */
private val IOS_HOME_REQUEST = ContentRequest(ContentProvider.YOUTUBE, ContentSurface.HOME)

/** Discovery's collection; its new-release shelf records under a fixed key of its own. */
private val IOS_DISCOVERY_REQUEST = ContentRequest(ContentProvider.YOUTUBE, ContentSurface.DISCOVERY)

/** Charts' collection, kept apart from the home feed and from generic browse pages. */
private val IOS_CHARTS_REQUEST = ContentRequest(ContentProvider.YOUTUBE, ContentSurface.EXPLORE, text = "charts")

/** Discovery's suggestion feed collection, so its shelf rows never share keys with the release shelf. */
private val IOS_DISCOVERY_SUGGESTIONS_REQUEST =
    ContentRequest(ContentProvider.YOUTUBE, ContentSurface.DISCOVERY, text = "suggestions")

/** New releases' collection: its rows are albums, so a tap opens the album page rather than a queue. */
private val IOS_RELEASES_REQUEST = ContentRequest(ContentProvider.YOUTUBE, ContentSurface.EXPLORE, text = "new_releases")

private const val NEW_RELEASES_SHELF_KEY = "new_releases"

/**
 * One paged shelf feed: the home feed's section list and the charts sheet read through the same state machine. The
 * generation counter keeps a superseded reload from publishing over a newer one, and a failed read reports the
 * failure instead of leaving an empty page behind.
 */
internal class IosShelfFeed(
    private val scope: CoroutineScope,
    private val report: (String) -> Unit,
    private val read: suspend (continuation: String?, params: String?) -> YouTubeShelfPage,
) {
    var shelves by mutableStateOf<List<YouTubeShelf>>(emptyList())
        private set
    var continuation by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(true)
        private set
    var failed by mutableStateOf(false)
        private set
    var initialized by mutableStateOf(false)
        private set

    /** A pull-to-refresh, kept apart from [loading] so a load-more does not run the pull indicator. */
    var refreshing by mutableStateOf(false)
        private set

    /** The feed chips the last first-page read returned; a feed that carries none leaves this empty. */
    var chips by mutableStateOf<List<YouTubeFeedChip>>(emptyList())
        private set

    /** The chip currently filtering the feed, as the Android home tracks it; null keeps the un-filtered feed. */
    var selectedChip by mutableStateOf<YouTubeFeedChip?>(null)
        private set

    private var chipParams: String? = null
    private var generation = 0

    fun reload() {
        refreshing = true
        load(continuation = null)
    }

    fun loadMore() {
        val token = continuation ?: return
        if (loading) return
        load(token)
    }

    /**
     * A chip tap re-reads the first page under it, and a re-tap of the selected chip clears it through the shared
     * toggle — the same select/deselect semantics the Android home applies.
     */
    fun selectChip(chip: YouTubeFeedChip?) {
        val selection = selectYouTubeFeedChip(selectedChip, chip)
        selectedChip = selection.selected
        chipParams = selection.params
        load(continuation = null)
    }

    private fun load(continuation: String?) {
        val current = ++generation
        loading = true
        failed = false
        scope.launch {
            try {
                // Continuation reads carry the token alone; the transport drops params on continuation anyway.
                val page = read(continuation, chipParams)
                if (current != generation) return@launch
                shelves = if (continuation == null) page.shelves else shelves + page.shelves
                if (continuation == null) chips = page.chips
                this@IosShelfFeed.continuation = page.continuation
                initialized = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (current != generation) return@launch
                report(getString(Res.string.ios_transports_feed_failed))
                failed = true
            } finally {
                if (current == generation) {
                    loading = false
                    refreshing = false
                }
            }
        }
    }
}

/** The explore payload behind Discovery and Moods: the new-release shelf and the mood/genre tiles. */
internal class IosExploreFeed(
    private val scope: CoroutineScope,
    private val report: (String) -> Unit,
    private val read: suspend () -> YouTubeExplore,
) {
    var releases by mutableStateOf<List<YouTubeRelease>>(emptyList())
        private set
    var moods by mutableStateOf<List<YouTubeMoodGenre>>(emptyList())
        private set
    var loading by mutableStateOf(true)
        private set
    var failed by mutableStateOf(false)
        private set

    private var generation = 0

    fun reload() {
        val current = ++generation
        loading = true
        failed = false
        scope.launch {
            try {
                val page = read()
                if (current != generation) return@launch
                releases = page.releases
                moods = page.moods
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (current != generation) return@launch
                report(getString(Res.string.ios_transports_explore_failed))
                failed = true
            } finally {
                if (current == generation) loading = false
            }
        }
    }
}

/** The new-releases list the release chips filter and the query searches. */
internal class IosReleasesFeed(
    private val scope: CoroutineScope,
    private val report: (String) -> Unit,
    private val read: suspend () -> List<YouTubeRelease>,
) {
    var releases by mutableStateOf<List<YouTubeRelease>>(emptyList())
        private set
    var loading by mutableStateOf(true)
        private set
    var failed by mutableStateOf(false)
        private set

    private var generation = 0

    fun reload() {
        val current = ++generation
        loading = true
        failed = false
        scope.launch {
            try {
                val list = read()
                if (current != generation) return@launch
                releases = list
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (current != generation) return@launch
                report(getString(Res.string.ios_transports_releases_failed))
                failed = true
            } finally {
                if (current == generation) loading = false
            }
        }
    }
}

/**
 * Home over the live feed. The home request asks for the signed-in session, so the reload re-reads the persisted
 * sign-in first; the shelf rows record with [IosCatalogueOwners] under the home collection.
 */
@Composable
internal fun iosHomePage(
    graph: IosSessionGraph,
    lists: IosCatalogueOwners,
    openRoute: (String) -> Unit,
): ArchiveGraphPage {
    val saved by graph.settings.snapshots.collectAsState()
    val available = resolvedHomeSources(saved?.get(ActiveHomeSourcesKey).orEmpty(),
        spotifyAvailable = !saved?.get(SpotifySpDcKey).isNullOrBlank(), qqAvailable = true)
    val selected = resolvedHomeSource(saved?.get(HomeSourceKey), available)
    val sourceChange: (HomeSource) -> Unit = { source -> graph.scope.launch { graph.settings.edit { this[HomeSourceKey] = source.name } } }
    if (selected != HomeSource.YOUTUBE) return iosProviderHomePage(graph, selected, available, sourceChange, openRoute)
    val scope = rememberCoroutineScope()
    val catalogue = graph.youtubeCatalogue
    val feed =
        remember(graph, scope, catalogue) {
            IosShelfFeed(scope, graph::report) { token, params ->
                graph.youtubeTransport.refreshSession(graph.settings.snapshots.value)
                // The feed keeps the raw shelves; the block list filters at render below so blocking a visible
                // song hides it and an unblock restores it without a re-read, as the Android home does.
                catalogue.home(token, params)
            }
        }
    LaunchedEffect(feed) { feed.reload() }
    val showChips = graph.settings.snapshots.value?.get(ShowHomeCategoryChipsKey) ?: true
    // Hiding the row while a chip filters the feed clears it, as the Android home does.
    LaunchedEffect(showChips) {
        if (!showChips && feed.selectedChip != null) feed.selectChip(null)
    }
    val settingsSnapshot by graph.settings.snapshots.collectAsState()
    val blocked = parseBlockedSongRefs(settingsSnapshot?.get(BlockedSongIdsKey).orEmpty()).toSet()
    val shelves = feed.shelves.filterBlockedSongs(blocked).map { shelf -> shelf.toHomeShelf(openRoute) }
    SideEffect { shelves.forEach { lists.recordShelf(IOS_HOME_REQUEST, it.key, it.items) } }
    val snapshot by graph.player.snapshot.collectAsState()
    return ArchiveGraphPage.Home(
        source = HomeSource.YOUTUBE,
        // The Spotify catalog home and the QQ source have no owner on this host, so the switcher stays hidden.
        availableSources = available,
        sourceChange = sourceChange,
        design = HomeFeedDesign.Classic,
        shelves = shelves,
        page =
            PagedContentState(
                request = IOS_HOME_REQUEST,
                loading = feed.loading,
                initialized = feed.initialized,
                failed = feed.failed,
                continuation = feed.continuation,
            ),
        loadMore = feed::loadMore,
        retry = feed::reload,
        activeIds = setOfNotNull(snapshot?.occurrenceId),
        playing = snapshot?.playWhenReady == true,
        refreshing = feed.refreshing,
        refresh = feed::reload,
        chips = feed.chips.map { HomeVariantChip(it.stableKey, it.title) },
        selectedChipKey = feed.selectedChip?.stableKey,
        selectChip = { tapped -> feed.chips.firstOrNull { it.stableKey == tapped.key }?.let(feed::selectChip) },
        showChips = showChips,
    )
}

/** Discovery: the explore page's new-release shelf, its mood tiles and the page's own tabs. */
@Composable
internal fun iosDiscoveryPage(graph: IosSessionGraph, lists: IosCatalogueOwners): ArchiveGraphPage {
    val scope = rememberCoroutineScope()
    val catalogue = graph.youtubeCatalogue
    val feed = remember(graph, scope, catalogue) { IosExploreFeed(scope, graph::report) { catalogue.explore() } }
    // The suggestion feed is the provider's own suggestion surface — the un-filtered home feed — read on first tab
    // entry rather than on page mount, so the tab costs nothing while it is unseen.
    val suggest =
        remember(graph, scope, catalogue) {
            IosShelfFeed(scope, graph::report) { token, _ ->
                graph.youtubeTransport.refreshSession(graph.settings.snapshots.value)
                // The suggestion surface filters the same recommendation feed at render, as Android's suggestion
                // list does; keeping the raw shelves lets an unblock restore them without a re-read.
                catalogue.home(token, null)
            }
        }
    LaunchedEffect(feed) { feed.reload() }
    var tab by remember { mutableStateOf(DiscoveryTab.EXPLORE) }
    LaunchedEffect(tab) {
        if (tab == DiscoveryTab.SUGGESTIONS && !suggest.initialized) suggest.reload()
    }
    val releaseRows = feed.releases.mapIndexed { index, release -> catalogueOccurrence(IOS_DISCOVERY_REQUEST, release.entry, index) }
    SideEffect { lists.recordShelf(IOS_DISCOVERY_REQUEST, NEW_RELEASES_SHELF_KEY, releaseRows) }
    val releaseShelf =
        BrowseShelfUi(
            key = NEW_RELEASES_SHELF_KEY,
            title = stringResource(Res.string.m4_new_releases),
            collection = IOS_DISCOVERY_REQUEST,
            rows = releaseRows,
            more = null,
        )
    val settingsSnapshot by graph.settings.snapshots.collectAsState()
    val blocked = parseBlockedSongRefs(settingsSnapshot?.get(BlockedSongIdsKey).orEmpty()).toSet()
    val suggestionShelves = suggest.shelves.filterBlockedSongs(blocked).map { it.toBrowseShelf(IOS_DISCOVERY_SUGGESTIONS_REQUEST) }
    SideEffect {
        suggestionShelves.forEach { lists.recordShelf(IOS_DISCOVERY_SUGGESTIONS_REQUEST, it.key, it.rows) }
    }
    val suggestionsActive = tab == DiscoveryTab.SUGGESTIONS
    val state =
        DiscoveryUi(
            shelves = if (releaseRows.isEmpty()) emptyList() else listOf(releaseShelf),
            moods = feed.moods.map { it.toUi() },
            tab = tab,
            loading = if (suggestionsActive) suggest.loading else feed.loading,
            failed = if (suggestionsActive) suggest.failed else feed.failed,
            suggestions = suggestionShelves,
        )
    return ArchiveGraphPage.Discovery(
        state,
        { tab = it },
        refresh = {
            feed.reload()
            if (suggest.initialized) suggest.reload()
        },
        retry = { if (suggestionsActive) suggest.reload() else feed.reload() },
    )
}

/** Charts: the ranked top songs and the genre/new-release shelves the charts sheet returns. */
@Composable
internal fun iosChartsPage(graph: IosSessionGraph, lists: IosCatalogueOwners): ArchiveGraphPage {
    val scope = rememberCoroutineScope()
    val catalogue = graph.youtubeCatalogue
    val feed =
        remember(graph, scope, catalogue) {
            IosShelfFeed(scope, graph::report) { token, _ -> catalogue.charts(token) }
        }
    LaunchedEffect(feed) { feed.reload() }
    val shelves = feed.shelves.map { it.toBrowseShelf(IOS_CHARTS_REQUEST) }
    SideEffect { shelves.forEach { lists.recordShelf(IOS_CHARTS_REQUEST, it.key, it.rows) } }
    return ArchiveGraphPage.Charts(shelves, feed.loading, feed.failed, feed::reload,
        continuation = feed.continuation, loadMore = feed::loadMore)
}

/** Moods and genres: the explore page's tile list, each tile browsing the provider's own section. */
@Composable
internal fun iosMoodsPage(graph: IosSessionGraph): ArchiveGraphPage {
    val scope = rememberCoroutineScope()
    val catalogue = graph.youtubeCatalogue
    val feed = remember(graph, scope, catalogue) { IosExploreFeed(scope, graph::report) { catalogue.explore() } }
    LaunchedEffect(feed) { feed.reload() }
    return ArchiveGraphPage.Moods(feed.moods.map { it.toUi() }, feed.loading, feed.failed, feed::reload)
}

/** New releases, filtered by the page's category chips and query. */
@Composable
internal fun iosReleasesPage(graph: IosSessionGraph): ArchiveGraphPage {
    val scope = rememberCoroutineScope()
    val catalogue = graph.youtubeCatalogue
    val feed = remember(graph, scope, catalogue) { IosReleasesFeed(scope, graph::report) { catalogue.newReleases() } }
    LaunchedEffect(feed) { feed.reload() }
    var category by remember { mutableStateOf(ReleaseCategory.ALL) }
    var query by remember { mutableStateOf("") }
    val state =
        ReleasesUi(
            releases = feed.releases.mapIndexed { index, release -> release.toUi(index) },
            category = category,
            query = query,
            loading = feed.loading,
            failed = feed.failed,
        )
    return ArchiveGraphPage.Releases(state, { category = it }, { query = it }, feed::reload, null)
}

/** One shelf as the home screen binds it; "see all" opens the provider's own browse route for the section. */
private fun YouTubeShelf.toHomeShelf(openRoute: (String) -> Unit): HomeShelf =
    HomeShelf(
        key = key,
        title = title,
        items = entries.mapIndexed { index, entry -> catalogueOccurrence(IOS_HOME_REQUEST, entry, index) },
        more = more?.let { section -> { openRoute(ArchiveRoutes.browse(browseRequest(ContentSurface.EXPLORE, section))) } },
    )

/** One shelf as a Discovery or charts shelf: its rows queue under [collection], "more" browses on. */
private fun YouTubeShelf.toBrowseShelf(collection: ContentRequest): BrowseShelfUi =
    BrowseShelfUi(
        key = key,
        title = title,
        collection = collection,
        rows = entries.mapIndexed { index, entry -> catalogueOccurrence(collection, entry, index) },
        more = more?.let { browseRequest(ContentSurface.EXPLORE, it) },
        ranked = numbered,
    )

private fun YouTubeMoodGenre.toUi(): MoodGenreUi =
    MoodGenreUi(title, title, stripeArgb, browseRequest(ContentSurface.EXPLORE, section))

private fun YouTubeRelease.toUi(ordinal: Int): ReleaseUi =
    ReleaseUi(
        item = catalogueOccurrence(IOS_RELEASES_REQUEST, entry, ordinal).item,
        category =
            when (type) {
                YouTubeReleaseType.ALBUM -> ReleaseCategory.ALBUMS
                YouTubeReleaseType.SINGLE -> ReleaseCategory.SINGLES
                YouTubeReleaseType.EP -> ReleaseCategory.EPS
            },
    )

/**
 * The collection key a catalogue page's rows queue under. One request is one collection, so a shelf page and a list
 * page never share a key space; the provider's own entry id keeps a playlist row stable across reloads.
 */
internal fun catalogueOccurrence(request: ContentRequest, entry: YouTubeEntry, ordinal: Int): ContentOccurrence {
    val collection = "${request.provider.id}:${request.surface.id}:${request.section?.id.orEmpty()}:${request.text.orEmpty()}"
    val setId = entry.setVideoId?.takeIf(String::isNotBlank)
    return ContentOccurrence(
        occurrenceId = if (setId != null) "$collection:set:$setId" else "$collection:ordinal:$ordinal",
        item =
            ContentItem(
                ref = ContentRef(ContentProvider.YOUTUBE.id, entry.kind, entry.id),
                title = entry.title,
                subtitle = entry.subtitle,
                artworkUri = entry.artworkUri,
                durationMs = entry.durationSeconds?.let { it * 1000L },
                explicit = entry.explicit,
                album = entry.album,
                artists = entry.artists,
                albumId = entry.albumId?.takeIf(String::isNotBlank),
            ),
        setVideoId = setId,
    )
}

/** The request a shelf's "see all" endpoint browses to, under the page's own surface. */
internal fun browseRequest(surface: ContentSurface, section: ContentSection): ContentRequest =
    ContentRequest(ContentProvider.YOUTUBE, surface, section = section)
