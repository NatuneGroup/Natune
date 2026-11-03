package moe.rukamori.archivetune.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.shared.domain.queue.FinitePlaylistCompletion
import moe.rukamori.archivetune.shared.domain.queue.IncompleteFinitePlaylistException
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.ContentRequest
import moe.rukamori.archivetune.shared.domain.repository.ContentSurface
import moe.rukamori.archivetune.shared.domain.repository.ContinuationQueueController
import moe.rukamori.archivetune.shared.domain.repository.QueueCollectionKind
import moe.rukamori.archivetune.shared.domain.repository.QueueLoadRequest
import moe.rukamori.archivetune.shared.ui.screens.PagedContentState
import moe.rukamori.archivetune.shared.ui.screens.PodcastBrowseUi
import moe.rukamori.archivetune.shared.ui.screens.PodcastEpisodeUi
import moe.rukamori.archivetune.shared.ui.screens.PodcastSubscriptionUi
import moe.rukamori.archivetune.shared.ui.screens.PodcastUi

internal class DesktopPodcastsOwner(
    private val scope: CoroutineScope,
    private val transport: DesktopPodcastTransport,
    private val subscriptions: DesktopPodcastSubscriptions,
    private val queue: ContinuationQueueController,
    private val report: (String) -> Unit,
    private val filter: suspend (List<ContentOccurrence>) -> List<ContentOccurrence> = { it },
) {
    private val ownerJob = SupervisorJob(scope.coroutineContext[Job])
    private val ownerScope = CoroutineScope(scope.coroutineContext + ownerJob)
    private var browsePage by mutableStateOf(PagedContentState())
    private var query by mutableStateOf("")
    private var subscriptionsOnly by mutableStateOf(false)
    private var followed by mutableStateOf<List<ContentItem>>(emptyList())
    private var subscriptionsLoaded by mutableStateOf(false)
    private var subscriptionsFailed by mutableStateOf(false)
    private val subscriptionStates = mutableStateMapOf<ContentRef, PodcastSubscriptionUi>()
    private val subscriptionLock = Mutex()
    private val details = LinkedHashMap<String, DesktopPodcastOwner>()
    private var browseJob: Job? = null
    private var subscriptionJob: Job? = null
    private var generation = 0
    private val consumed = HashSet<String>()

    fun browseUi(notice: String): PodcastBrowseUi {
        val rows = (browsePage.items.map { it.item } + followed).distinctBy { it.ref }
        return PodcastBrowseUi(
            page = browsePage.copy(
                items = rows.map { ContentOccurrence("desktop-podcasts:${it.ref.id}", it) },
                failed = browsePage.failed || subscriptionsFailed,
            ),
            query = query,
            subscriptionsOnly = subscriptionsOnly,
            subscriptionsAvailable = subscriptionsLoaded,
            subscriptions = rows.associate { it.ref to subscriptionState(it.ref) },
            subscriptionNotice = notice,
        )
    }

    fun ensureLoaded() {
        if (!subscriptionsLoaded && subscriptionJob?.isActive != true) loadSubscriptions()
        if (!browsePage.initialized && browseJob?.isActive != true) reload()
    }

    private fun loadSubscriptions() {
        subscriptionJob = ownerScope.launch {
            try {
                followed = subscriptions.read()
                subscriptionsLoaded = true
                subscriptionsFailed = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                subscriptionsFailed = true
                report("podcast subscriptions not read: ${failure.javaClass.simpleName}")
            }
        }
    }

    fun query(value: String) {
        if (query == value) return
        query = value
        loadBrowse(first = true, debounce = true)
    }

    fun subscriptionsOnly(value: Boolean) {
        subscriptionsOnly = value
    }

    fun reload() {
        if (subscriptionsFailed) loadSubscriptions()
        loadBrowse(first = true)
    }

    fun loadMore() {
        if (!browsePage.loading && !browsePage.failed && browsePage.continuation != null) loadBrowse(first = false)
    }

    private fun loadBrowse(first: Boolean, debounce: Boolean = false) {
        val token = if (first) null else browsePage.continuation ?: return
        val gen = ++generation
        browseJob?.cancel()
        if (first) consumed.clear()
        browsePage = if (first) PagedContentState(loading = true) else browsePage.copy(loading = true, failed = false)
        val requestedQuery = query
        browseJob = ownerScope.launch {
            try {
                if (debounce) delay(250L)
                val page = withTimeoutOrNull(PODCAST_READ_TIMEOUT_MS) { transport.browse(requestedQuery, token) }
                    ?: error("Podcast browse timed out")
                if (gen != generation) return@launch
                if (token != null) consumed.add(token)
                check(page.continuation == null || (page.continuation.isNotBlank() && page.continuation !in consumed)) {
                    "Podcast browse returned an invalid page token"
                }
                val items = ((if (first) emptyList() else browsePage.items.map { it.item }) + page.shows).distinctBy { it.ref }
                check(items.size <= MAX_BROWSE_SHOWS) { "Podcast browse exceeded its bound" }
                browsePage = PagedContentState(
                    items = items.map { ContentOccurrence("desktop-podcasts:${it.ref.id}", it) },
                    initialized = true,
                    continuation = page.continuation,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (gen == generation) browsePage = browsePage.copy(initialized = true, loading = false, failed = true)
                report("podcast browse not loaded: ${failure.javaClass.simpleName}")
            }
        }
    }

    fun detail(id: String): DesktopPodcastOwner = details.getOrPut(id) {
        if (details.size >= MAX_DETAIL_PAGES) {
            details.remove(details.keys.first())?.close()
        }
        DesktopPodcastOwner(ownerScope, transport, id, report)
    }

    fun subscriptionState(ref: ContentRef): PodcastSubscriptionUi = subscriptionStates[ref]
        ?: PodcastSubscriptionUi(followed.any { it.ref == ref }, busy = !subscriptionsLoaded, failed = subscriptionsFailed)

    fun subscribe(ref: ContentRef, subscribed: Boolean) {
        if (!subscriptionsLoaded || subscriptionStates[ref]?.busy == true) return
        val item = details.values.firstOrNull { it.header?.ref == ref }?.header
            ?: details[ref.id]?.header ?: browsePage.items.firstOrNull { it.item.ref == ref }?.item
            ?: followed.firstOrNull { it.ref == ref } ?: return
        val before = subscriptionState(ref)
        subscriptionStates[ref] = before.copy(busy = true, failed = false)
        ownerScope.launch {
            try {
                subscriptionLock.withLock {
                    val next = if (subscribed) (followed + item).distinctBy { it.ref } else followed.filterNot { it.ref == ref }
                    require(next.size <= MAX_PODCAST_SUBSCRIPTIONS)
                    subscriptions.write(next)
                    followed = next
                }
                subscriptionStates.remove(ref)
            } catch (cancelled: CancellationException) {
                subscriptionStates.remove(ref)
                throw cancelled
            } catch (failure: Exception) {
                subscriptionStates[ref] = before.copy(busy = false, failed = true)
                report("podcast subscription not saved: ${failure.javaClass.simpleName}")
            }
        }
    }

    /** Completes the finite show before replacing the same queue; a failed/unknown tail never plays as complete. */
    suspend fun play(request: QueueLoadRequest, isCurrent: () -> Boolean = { true }) {
        if (!ownerJob.isActive) throw CancellationException("Podcast owner closed")
        require(request.collection.provider == ContentProvider.YOUTUBE && request.collection.surface == ContentSurface.PODCAST)
        require(request.kind == QueueCollectionKind.FINITE)
        val id = requireNotNull(request.collection.parent).also { require(it.kind == ContentKind.PODCAST) }.id
        val episodes = withTimeoutOrNull(FinitePlaylistCompletion.TIMEOUT_MS) { completeDesktopPodcast(transport, id) }
            ?: throw IncompleteFinitePlaylistException("Podcast loading timed out")
        check(episodes.isNotEmpty()) { "The podcast has no playable episodes" }
        val rows = filter(episodes.map { it.row })
        check(rows.isNotEmpty()) { "The podcast has no episodes allowed by playback preferences" }
        val start = request.start?.let { selected ->
            rows.firstOrNull { it.occurrenceId == selected.occurrenceId }
                ?: rows.firstOrNull { it.item.ref == selected.item }
                ?: error("The selected episode is no longer in this podcast")
        } ?: rows.first()
        val order = if (!request.shuffle) rows else if (request.start != null) {
            listOf(start) + rows.filterNot { it.occurrenceId == start.occurrenceId }.shuffled()
        } else rows.shuffled()
        val first = if (request.shuffle) order.first() else start
        if (!ownerJob.isActive || !isCurrent()) throw CancellationException("Podcast play request superseded")
        queue.replace(order, first.occurrenceId, request.playWhenReady, staysFinite = true)
        if (request.positionMs > 0 && queue.state.value.currentOccurrenceId == first.occurrenceId) {
            queue.select(first.occurrenceId, request.positionMs)
        }
    }

    fun close() {
        ownerJob.cancel()
        details.values.forEach { it.close() }
    }

    private companion object {
        const val MAX_BROWSE_SHOWS = 1000
        const val MAX_DETAIL_PAGES = 8
    }
}

internal class DesktopPodcastOwner(
    private val scope: CoroutineScope,
    private val transport: DesktopPodcastTransport,
    private val browseId: String,
    private val report: (String) -> Unit,
) {
    var header by mutableStateOf<ContentItem?>(null)
        private set
    private var description by mutableStateOf<String?>(null)
    private var episodes by mutableStateOf<List<PodcastEpisodeUi>>(emptyList())
    private var page by mutableStateOf(PagedContentState())
    private var job: Job? = null
    private var generation = 0
    private val consumed = HashSet<String>()

    fun ui(subscription: PodcastSubscriptionUi, notice: String): PodcastUi = PodcastUi(
        collection = ContentRequest(ContentProvider.YOUTUBE, ContentSurface.PODCAST,
            parent = ContentRef(ContentProvider.YOUTUBE.id, ContentKind.PODCAST, browseId)),
        header = header,
        description = description,
        episodes = episodes,
        page = page.copy(items = episodes.map { it.row }),
        subscription = subscription,
        subscriptionNotice = notice,
    )

    fun ensureLoaded() { if (!page.initialized && job?.isActive != true) reload() }
    fun reload() = load(first = true)
    fun loadMore() {
        if (!page.loading && !page.failed && page.continuation != null) load(first = false)
    }

    private fun load(first: Boolean) {
        val token = if (first) null else page.continuation ?: return
        val gen = ++generation
        job?.cancel()
        if (first) consumed.clear()
        page = page.copy(loading = true, failed = false)
        job = scope.launch {
            try {
                val next = withTimeoutOrNull(PODCAST_READ_TIMEOUT_MS) {
                    if (first) {
                        val detail = transport.detail(browseId)
                        if (gen == generation) { header = detail.header; description = detail.description }
                        detail.page
                    } else transport.episodes(requireNotNull(header), requireNotNull(token))
                } ?: error("Podcast episodes timed out")
                if (gen != generation) return@launch
                if (token != null) consumed.add(token)
                FinitePlaylistCompletion.validateNext(next.continuation, consumed, next.episodes.size, initial = first)
                episodes = ((if (first) emptyList() else episodes) + next.episodes).distinctBy { it.row.item.ref }
                page = PagedContentState(initialized = true, continuation = next.continuation)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (gen == generation) page = page.copy(initialized = true, loading = false, failed = true)
                report("podcast episodes not loaded: ${failure.javaClass.simpleName}")
            }
        }
    }

    fun close() { job?.cancel() }
}

internal suspend fun completeDesktopPodcast(transport: DesktopPodcastTransport, browseId: String): List<PodcastEpisodeUi> {
    val first = transport.detail(browseId)
    val episodes = first.page.episodes.toMutableList()
    val consumed = HashSet<String>()
    var next = first.page.continuation
    var requests = 1
    FinitePlaylistCompletion.validateNext(next, consumed, episodes.size, initial = true)
    while (next != null) {
        if (++requests > FinitePlaylistCompletion.MAX_REQUESTS || !consumed.add(next)) throw IncompleteFinitePlaylistException()
        val page = transport.episodes(first.header, next)
        FinitePlaylistCompletion.validateNext(page.continuation, consumed, page.episodes.size)
        episodes += page.episodes
        next = page.continuation
    }
    return episodes.distinctBy { it.row.item.ref }
}

/** A dispatch adapter only, not another queue or playback owner. All other loads/checkpoints remain untouched. */
internal class DesktopPodcastQueue(
    private val delegate: ContinuationQueueController,
    private val podcasts: DesktopPodcastsOwner,
) : ContinuationQueueController by delegate {
    private var generation = 0L
    override suspend fun load(request: QueueLoadRequest) {
        val ticket = ++generation
        val revision = delegate.state.value.revision
        if (request.collection.provider == ContentProvider.YOUTUBE && request.collection.surface == ContentSurface.PODCAST) {
            podcasts.play(request) { ticket == generation && delegate.state.value.revision == revision }
        } else delegate.load(request)
    }
}

internal fun createDesktopPodcasts(graph: DesktopSessionGraph) = DesktopPodcastsOwner(
    graph.scope, CoreDesktopPodcastTransport(),
    FileDesktopPodcastSubscriptions(graph.instanceDirectory.resolve("podcast_subscriptions.json")), graph.queue, graph::report,
    filter = { desktopPlayableRows(graph, it) },
)

private const val PODCAST_READ_TIMEOUT_MS = 30_000L
