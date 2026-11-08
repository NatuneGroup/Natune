package moe.rukamori.archivetune.shared.ui.ios

import androidx.compose.runtime.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import moe.rukamori.archivetune.shared.domain.repository.*
import moe.rukamori.archivetune.shared.domain.settings.StringPreferenceKey
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveGraphPage
import moe.rukamori.archivetune.shared.ui.resources.*
import moe.rukamori.archivetune.shared.ui.screens.*
import org.jetbrains.compose.resources.stringResource

private val PodcastFollows = StringPreferenceKey("iosPodcastSubscriptions")

private fun follows(raw: String?): List<ContentItem> = try {
    (Json.parseToJsonElement(raw ?: "[]") as JsonArray).take(256).map { value ->
        val row = value.jsonObject
        ContentItem(ContentRef(ContentProvider.YOUTUBE.id, ContentKind.PODCAST, row.getValue("id").jsonPrimitive.content),
            row.getValue("title").jsonPrimitive.content, row["author"]?.jsonPrimitive?.content.orEmpty(),
            artworkUri = row["artwork"]?.jsonPrimitive?.contentOrNull)
    }.distinctBy { it.ref }
} catch (_: Exception) { emptyList() }

private suspend fun follow(graph: IosSessionGraph, item: ContentItem, subscribed: Boolean) {
    graph.settings.edit {
        val rows = follows(this[PodcastFollows]).filter { it.ref != item.ref }.toMutableList()
        if (subscribed) { require(rows.size < 256); rows.add(item) }
        this[PodcastFollows] = buildJsonArray {
            rows.forEach { add(buildJsonObject {
                put("id", it.ref.id); put("title", it.title); put("author", it.subtitle)
                it.artworkUri?.let { uri -> put("artwork", uri) }
            }) }
        }.toString()
    }
}

@Composable
internal fun iosPodcastsPage(graph: IosSessionGraph): ArchiveGraphPage {
    var query by remember(graph) { mutableStateOf("") }
    var onlyFollows by remember(graph) { mutableStateOf(false) }
    var page by remember(graph) { mutableStateOf(PagedContentState()) }
    var epoch by remember(graph) { mutableStateOf(0) }
    var next by remember(graph) { mutableStateOf<String?>(null) }
    val prefs by graph.settings.snapshots.collectAsState()
    val followed = remember(prefs) { follows(prefs?.get(PodcastFollows)) }
    val notice = stringResource(Res.string.content_podcast_subscriptions_local)
    LaunchedEffect(graph, query, epoch) {
        val token = next
        page = if (token == null) PagedContentState(loading = true) else page.copy(loading = true, failed = false)
        try {
            graph.youtubeTransport.refreshSession(graph.settings.snapshots.value)
            val result = if (query.isBlank()) {
                val feed = graph.youtubeCatalogue.home(token)
                val shows = feed.shelves.flatMap { it.entries }.filter { it.kind == ContentKind.PODCAST }.map {
                    ContentOccurrence("ios-podcast:${it.id}", ContentItem(
                        ContentRef(ContentProvider.YOUTUBE.id, ContentKind.PODCAST, it.id), it.title,
                        it.subtitle, artworkUri = it.artworkUri))
                }
                ContentPage(shows, feed.continuation)
            } else graph.youtubeContent.page(ContentRequest(ContentProvider.YOUTUBE, ContentSurface.SEARCH,
                text = query.trim(), filter = ContentSearchFilter.PODCASTS), token)
            require(result.continuation == null || result.continuation != token)
            page = PagedContentState(items = ((if (token == null) emptyList() else page.items) + result.items)
                .distinctBy { it.item.ref }.take(2048), continuation = result.continuation, initialized = true)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: Exception) {
            page = page.copy(initialized = true, loading = false, failed = true)
            graph.report("podcasts failed: ${failure::class.simpleName}")
        }
    }
    val rows = (page.items.map { it.item } + followed).distinctBy { it.ref }
    return ArchiveGraphPage.Podcasts(
        state = PodcastBrowseUi(page.copy(items = rows.map { ContentOccurrence("ios-podcast:${it.ref.id}", it) }),
            query, onlyFollows, subscriptionsAvailable = true,
            subscriptions = rows.associate { it.ref to PodcastSubscriptionUi(followed.any { show -> show.ref == it.ref }, false, false) },
            subscriptionNotice = notice),
        query = { query = it; next = null }, subscriptionsOnly = { onlyFollows = it },
        subscribe = { ref, value -> rows.firstOrNull { it.ref == ref }?.let { item -> graph.scope.launch { follow(graph, item, value) } } },
        retry = { next = null; epoch++ },
        loadMore = { if (!page.loading && !page.failed && page.continuation != null) { next = page.continuation; epoch++ } },
    )
}

@Composable
internal fun iosPodcastPage(graph: IosSessionGraph, browseId: String): ArchiveGraphPage {
    val collection = remember(browseId) { ContentRequest(ContentProvider.YOUTUBE, ContentSurface.PODCAST,
        parent = ContentRef(ContentProvider.YOUTUBE.id, ContentKind.PODCAST, browseId)) }
    val presenter = rememberPagedContent(graph.youtubeContent, collection)
    val page by presenter.state.collectAsState()
    var header by remember(graph, browseId) { mutableStateOf<ContentItem?>(null) }
    var description by remember(graph, browseId) { mutableStateOf<String?>(null) }
    val prefs by graph.settings.snapshots.collectAsState()
    val subscribed = follows(prefs?.get(PodcastFollows)).any { it.ref == collection.parent }
    val notice = stringResource(Res.string.content_podcast_subscriptions_local)
    LaunchedEffect(graph, browseId) {
        try {
            graph.youtubeTransport.refreshSession(graph.settings.snapshots.value)
            val result = graph.youtubeCatalogue.artist(browseId)
            header = ContentItem(requireNotNull(collection.parent), result.artist.title, result.artist.subtitle,
                artworkUri = result.artist.artworkUri)
            description = result.description
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) { graph.report("podcast header failed: ${error::class.simpleName}") }
    }
    return ArchiveGraphPage.Podcast(
        state = PodcastUi(collection, header, description,
            page.items.map { PodcastEpisodeUi(it, null, null, null) }, page,
            PodcastSubscriptionUi(subscribed, header == null, false), notice),
        subscribe = { value -> header?.let { item -> graph.scope.launch { follow(graph, item, value) } } },
        retry = presenter::retry, loadMore = presenter::loadMore,
    )
}
