package moe.rukamori.archivetune.shared.ui.news

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.data.news.NatuneNewsClient
import moe.rukamori.archivetune.shared.data.news.hasUnreadNews
import moe.rukamori.archivetune.shared.domain.repository.ContentNews
import moe.rukamori.archivetune.shared.domain.settings.NewsLastReadTimestampKey
import moe.rukamori.archivetune.shared.domain.settings.SettingsStore
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveGraphPage
import moe.rukamori.archivetune.shared.ui.screens.NewsArticleUi
import moe.rukamori.archivetune.shared.ui.screens.NewsUi
import kotlin.coroutines.cancellation.CancellationException

/** One read of the feed: newest first, with the last good index kept through a failed refresh. */
data class NewsFeedState(
    val items: List<ContentNews> = emptyList(),
    val loading: Boolean = false,
    val failed: Boolean = false,
)

/**
 * The News owner a non-Android host shares between its pages and its entry point. It loads once when created, as the
 * Android activity's news view model does, so the unread dot can show before the page is opened. The last-read
 * timestamp is the same persisted `news_last_read_timestamp` key the Android host writes.
 *
 * The owner owns [client]: [close] cancels its refresh and read-mark work and then closes the client, so a host calls it
 * before it closes the HTTP client whose engine the feed borrows.
 */
class NewsFeedOwner(
    private val client: NatuneNewsClient,
    private val settings: SettingsStore,
    parent: CoroutineScope,
    private val report: (String) -> Unit,
) {
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))

    val enabled: Boolean get() = client.enabled

    private val mutableState = MutableStateFlow(NewsFeedState(loading = client.enabled))
    val state: StateFlow<NewsFeedState> = mutableState.asStateFlow()

    val unread: Flow<Boolean> = combine(mutableState, settings.snapshots) { feed, snapshot ->
        enabled && hasUnreadNews(feed.items, snapshot?.get(NewsLastReadTimestampKey) ?: 0L)
    }.distinctUntilChanged()

    private var refreshing: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        if (!enabled || !scope.isActive) return
        refreshing?.cancel()
        mutableState.value = mutableState.value.copy(loading = true, failed = false)
        refreshing = scope.launch {
            mutableState.value = try {
                NewsFeedState(items = client.list())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                report("the news feed was not loaded: ${failure::class.simpleName}")
                NewsFeedState(items = client.cached.value.orEmpty(), failed = true)
            }
        }
    }

    fun markRead(latestTimestamp: Long) {
        scope.launch {
            try {
                settings.edit { this[NewsLastReadTimestampKey] = latestTimestamp }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                report("the news read mark was not saved: ${failure::class.simpleName}")
            }
        }
    }

    fun cachedItem(id: String): ContentNews? = client.cachedItem(id)

    suspend fun article(id: String): String = client.content(id)

    fun close() {
        scope.cancel()
        client.close()
    }
}

/** The `news` page over [feed]. Opening it refreshes the feed, as each Android entry builds a fresh view model. */
@Composable
fun newsFeedPage(feed: NewsFeedOwner): ArchiveGraphPage {
    val feedState by feed.state.collectAsState()
    var query by remember { mutableStateOf("") }
    LaunchedEffect(feed) { feed.refresh() }
    return ArchiveGraphPage.News(
        state = NewsUi(
            items = feedState.items,
            available = feed.enabled,
            query = query,
            loading = feedState.loading,
            failed = feedState.failed,
        ),
        query = { query = it },
        refresh = feed::refresh,
        markRead = feed::markRead,
    )
}

/** The `view_news/<id>` page: the cached entry's header, and its Markdown body loaded on entry and on retry. */
@Composable
fun newsArticlePage(feed: NewsFeedOwner, newsId: String): ArchiveGraphPage {
    var attempt by remember(newsId) { mutableIntStateOf(0) }
    var markdown by remember(newsId) { mutableStateOf<String?>(null) }
    var loading by remember(newsId) { mutableStateOf(feed.enabled) }
    var failed by remember(newsId) { mutableStateOf(false) }
    val feedState by feed.state.collectAsState()
    LaunchedEffect(feed, newsId, attempt) {
        if (!feed.enabled) return@LaunchedEffect
        loading = true
        failed = false
        try {
            markdown = feed.article(newsId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            failed = true
        } finally {
            loading = false
        }
    }
    val item = feedState.items.firstOrNull { it.id == newsId } ?: feed.cachedItem(newsId)
    return ArchiveGraphPage.Article(
        state = NewsArticleUi(item = item, markdown = markdown, loading = loading, failed = failed, available = feed.enabled),
        retry = { attempt++ },
    )
}
