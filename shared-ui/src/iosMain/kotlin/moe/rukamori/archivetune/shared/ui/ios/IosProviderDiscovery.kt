package moe.rukamori.archivetune.shared.ui.ios

import androidx.compose.runtime.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import moe.rukamori.archivetune.shared.data.provider.KtorAppleMusicRepository
import moe.rukamori.archivetune.shared.data.provider.KtorQqMusicRepository
import moe.rukamori.archivetune.shared.domain.repository.*
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.ui.home.*
import moe.rukamori.archivetune.shared.ui.home.variants.*
import moe.rukamori.archivetune.shared.ui.host.hostSearchPage
import moe.rukamori.archivetune.shared.ui.navigation.*
import moe.rukamori.archivetune.shared.ui.screens.*

private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.obj(key: String) = this[key] as? JsonObject

private class IosSpotifySearchRepository(private val graph: IosSessionGraph) : TypedContentRepository {
    override val provider = ContentProvider.SPOTIFY_CATALOG
    private val client = IosSpotifyRestClient(graph)
    override suspend fun item(ref: ContentRef): ContentItem = graph.library.item(ref)
    override suspend fun page(request: ContentRequest, continuation: String?): ContentPage {
        require(request.provider == provider && request.surface == ContentSurface.SEARCH)
        val offset = continuation?.substringAfter("spotify-search:")?.toInt() ?: 0
        require(continuation == null || continuation == "spotify-search:$offset")
        val type = when (request.filter) {
            ContentSearchFilter.ALL -> "track,album,artist,playlist"
            ContentSearchFilter.SONGS -> "track"; ContentSearchFilter.ALBUMS -> "album"
            ContentSearchFilter.ARTISTS -> "artist"; ContentSearchFilter.PLAYLISTS -> "playlist"
            else -> throw UnsupportedContentOperation()
        }
        val url = URLBuilder("https://api.spotify.com/v1/search").apply {
            parameters.append("q", requireNotNull(request.text)); parameters.append("type", type)
            parameters.append("limit", "20"); parameters.append("offset", offset.toString())
        }.buildString()
        val root = client.getJson(url)
        val rows = mutableListOf<ContentOccurrence>()
        var more = false
        for ((key, kind) in listOf("tracks" to ContentKind.SONG, "albums" to ContentKind.ALBUM,
            "artists" to ContentKind.ARTIST, "playlists" to ContentKind.PLAYLIST)) {
            val page = root.obj(key) ?: continue
            if (page.text("next") != null) more = true
            (page["items"] as? JsonArray).orEmpty().forEach { value ->
                val row = value as? JsonObject ?: return@forEach
                if (kind == ContentKind.SONG) client.parseTrackOccurrence("spotify-search", row)?.let(rows::add)
                else {
                    val id = row.text("id") ?: return@forEach
                    val title = row.text("name") ?: return@forEach
                    val image = (row["images"] as? JsonArray)?.firstOrNull()?.jsonObject?.text("url")
                    rows += ContentOccurrence("spotify-search:$kind:$id", ContentItem(ContentRef(provider.id, kind, id), title, artworkUri = image))
                }
            }
        }
        require(offset <= 1000)
        return ContentPage(rows, if (more && offset < 1000) "spotify-search:${offset + 20}" else null)
    }
}

@Composable
internal fun iosProviderSearchPage(graph: IosSessionGraph, owners: IosCatalogOwners, route: ArchiveRoute,
    recognition: () -> Unit): ArchiveGraphPage {
    val prefs by graph.settings.snapshots.collectAsState()
    val request = if (route.destination == ArchiveDestination.SEARCH_RESULTS) route.contentRequest() else null
    val defaultSource = prefs?.get(DefaultSearchSourceKey)
    val provider = request?.provider ?: when (defaultSource) {
        "SPOTIFY" -> ContentProvider.SPOTIFY_CATALOG
        "APPLE_MUSIC" -> ContentProvider.APPLE
        else -> ContentProvider.YOUTUBE
    }
    if (provider == ContentProvider.YOUTUBE || provider == ContentProvider.LOCAL) return hostSearchPage(owners.pages, route, recognition)
    // Apple search is admitted through the same personal/pool token ring the playback source uses.
    var apple by remember(graph, provider, defaultSource) { mutableStateOf<KtorAppleMusicRepository?>(null) }
    var appleLoaded by remember(graph, provider, defaultSource) { mutableStateOf(false) }
    LaunchedEffect(graph, provider, defaultSource, request) {
        if (provider == ContentProvider.APPLE && request != null) {
            apple = try {
                iosAppleMusicSearchRepository(graph.http, graph.accounts, { graph.settings.snapshots.value }, graph.playbackPool)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // No bearer or no admitted account: the failed page below reports the missing account.
                null
            }
            appleLoaded = true
        }
    }
    val repository: TypedContentRepository? = remember(graph, provider, apple) { when (provider) {
        ContentProvider.SPOTIFY_CATALOG -> IosSpotifySearchRepository(graph)
        ContentProvider.QQ -> KtorQqMusicRepository(graph.http, null, { "ios-catalog" })
        ContentProvider.APPLE -> apple
        else -> null
    } }
    var query by remember(route.value) { mutableStateOf(route.searchQuery.orEmpty()) }
    var scope by remember(route.value) { mutableStateOf(SearchScope.Online) }
    var page = PagedContentState(request = request)
    var load: () -> Unit = {}
    var retry: () -> Unit = {}
    if (request != null && repository != null) {
        val presenter = rememberPagedContent(repository, request)
        page = presenter.state.collectAsState().value
        load = presenter::loadMore; retry = presenter::retry
        SideEffect {
            if (provider == ContentProvider.SPOTIFY_CATALOG) iosRecordSpotifyRows(request, page.items)
            else owners.lists.recordShelf(request, "provider-search", page.items)
        }
    } else if (request != null && appleLoaded) {
        // No Apple account produced a repository: the page keeps its honest failed state instead of searching YouTube.
        page = PagedContentState(request = request, initialized = true, failed = true)
    }
    return ArchiveGraphPage.Search(query, scope, provider, page, { query = it }, { scope = it }, null,
        recognition, load, retry, filter = request?.filter ?: ContentSearchFilter.ALL)
}

@Composable
internal fun iosProviderHomePage(graph: IosSessionGraph, source: HomeSource, available: List<HomeSource>, sourceChange: (HomeSource) -> Unit,
    openRoute: (String) -> Unit): ArchiveGraphPage {
    var shelves by remember(graph, source) { mutableStateOf<List<HomeShelf>>(emptyList()) }
    var page by remember(graph, source) { mutableStateOf(PagedContentState()) }
    var epoch by remember(graph, source) { mutableStateOf(0) }
    LaunchedEffect(graph, source, epoch) {
        page = PagedContentState(loading = true)
        try {
            shelves = if (source == HomeSource.QQ) KtorQqMusicRepository(graph.http, null, { "ios-catalog" }).home().map { chart ->
                HomeShelf("qq:${chart.id}", chart.title, chart.tracks.map { ContentOccurrence("qq:${chart.id}:${it.item.ref.id}", it.queueItem()) })
            } else spotifyHome(graph)
            page = PagedContentState(initialized = true)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: Exception) { page = PagedContentState(initialized = true, failed = true); graph.report("provider home failed: ${failure::class.simpleName}") }
    }
    val binding = HomeSourceBinding(source, available, sourceChange)
    if (source == HomeSource.QQ) {
        val state = when {
            page.loading -> QqHomeUi.Loading; page.failed -> QqHomeUi.Failed()
            shelves.isEmpty() -> QqHomeUi.Empty
            else -> QqHomeUi.Ready(shelves.map { QqHomeSectionUi(it.title, "", it.items) })
        }
        return ArchiveGraphPage.HomeQq(binding, state, QqHomeActions({ section, selected ->
            graph.scope.launch {
                val rows = section.tracks.map { it.queuedEntry() }
                val index = section.tracks.indexOfFirst { it.occurrenceId == selected.occurrenceId }
                if (index >= 0) graph.queue.replace(rows, rows[index].occurrenceId, true)
            }
        }, { epoch++ }), null, graph.player.snapshot.value?.playWhenReady == true, page.loading)
    }
    val state = when {
        page.loading -> SpotifyHomeUi.Loading; page.failed -> SpotifyHomeUi.Failed()
        shelves.isEmpty() -> SpotifyHomeUi.Empty
        else -> SpotifyHomeUi.Ready(SpotifyHomeContent(shelves = shelves.map {
            SpotifyHomeShelf.Cards(it.key, SpotifyShelfTitle.Plain(it.title), it.items)
        }))
    }
    return ArchiveGraphPage.HomeSpotify(binding, state, SpotifyHomeActions(
        play = { intent -> if (intent is HomeVariantPlay.Shelf)
            iosPlaySpotifyTrackList(graph, intent.title, intent.occurrences, intent.startIndex, false) },
        openPlaylist = { openRoute(ArchiveRoutes.content(it)) },
        select = { openRoute(ArchiveRoutes.content(it.ref)) },
        switchToYoutube = { sourceChange(HomeSource.YOUTUBE) }, refresh = { epoch++ }),
        HomeVariantNowPlaying(isPlaying = graph.player.snapshot.value?.playWhenReady == true))

}

private suspend fun spotifyHome(graph: IosSessionGraph): List<HomeShelf> {
    val variables = buildJsonObject {
        put("homeEndUserIntegration", "INTEGRATION_WEB_PLAYER"); put("timeZone", "UTC"); put("sp_t", "")
        put("facet", ""); put("sectionItemsLimit", 10); put("includeEpisodeContentRatingsV2", false)
    }
    val token = iosValidSpotifyCatalogToken(graph)
    val response = graph.http.post("https://api-partner.spotify.com/pathfinder/v2/query") {
        header("Authorization", "Bearer $token"); contentType(ContentType.Application.Json)
        setBody(buildJsonObject {
            put("operationName", "home"); put("variables", variables)
            put("extensions", buildJsonObject { put("persistedQuery", buildJsonObject {
                put("version", 1); put("sha256Hash", "23e37f2e58d82d567f27080101d36609009d8c3676457b1086cb0acc55b72a5d")
            }) })
        }.toString())
    }
    require(response.status.isSuccess())
    val home = Json.parseToJsonElement(response.bodyAsText()).jsonObject.obj("data")?.obj("home") ?: error("Invalid Spotify home")
    return (home.obj("sectionContainer")?.obj("sections")?.get("items") as? JsonArray).orEmpty().mapNotNull { entry ->
        val section = entry.jsonObject
        val title = section.obj("data")?.obj("title")?.let { it.text("transformedLabel") ?: it.text("translatedBaseText") ?: it.text("text") }.orEmpty()
        val rows = (section.obj("sectionItems")?.get("items") as? JsonArray).orEmpty().mapNotNull { element ->
            val data = element.jsonObject.obj("content")?.obj("data") ?: return@mapNotNull null
            val uri = data.text("uri") ?: return@mapNotNull null
            val kind = when (uri.substringBeforeLast(':').substringAfter(':')) {
                "playlist" -> ContentKind.PLAYLIST; "album" -> ContentKind.ALBUM; "artist" -> ContentKind.ARTIST
                else -> return@mapNotNull null
            }
            val id = uri.substringAfterLast(':')
            val name = data.text("name") ?: data.obj("profile")?.text("name") ?: return@mapNotNull null
            val sources = data.obj("coverArt")?.get("sources") as? JsonArray
                ?: data.obj("visuals")?.obj("avatarImage")?.get("sources") as? JsonArray
                ?: (data.obj("images")?.get("items") as? JsonArray)?.firstOrNull()?.jsonObject?.get("sources") as? JsonArray
            val image = sources?.lastOrNull()?.jsonObject?.text("url")
            ContentOccurrence("spotify-home:$id", ContentItem(ContentRef(ContentProvider.SPOTIFY_CATALOG.id, kind, id), name, artworkUri = image))
        }
        if (rows.isEmpty()) null else HomeShelf("spotify-home:$title", title, rows)
    }
}
