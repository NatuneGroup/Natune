package moe.rukamori.archivetune.shared.ui.host

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.data.provider.HistoryPageAccumulator
import moe.rukamori.archivetune.shared.data.provider.YouTubeEntry
import moe.rukamori.archivetune.shared.data.provider.YouTubeHistoryPage
import moe.rukamori.archivetune.shared.data.provider.YouTubeHistoryRoutingUnavailable
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentAccountChanged
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.songmetadata.SongMetadataOverride
import moe.rukamori.archivetune.shared.domain.songmetadata.withSongMetadataOverrides
import moe.rukamori.archivetune.shared.ui.screens.HistoryEventUi
import moe.rukamori.archivetune.shared.ui.screens.HistorySectionUi
import moe.rukamori.archivetune.shared.ui.screens.HistorySource

/** The same account-history paging lifetime on desktop and iOS, including dated episode rows. */
class YouTubeHistoryPager(
    private val scope: CoroutineScope,
    private val fetch: suspend (String?) -> YouTubeHistoryPage,
    private val play: (ContentOccurrence) -> Unit,
    private val playShuffled: (List<ContentOccurrence>) -> Unit,
    private val report: (String) -> Unit,
    private val isCurrentAccount: () -> Boolean = { true },
    /**
     * A host's song edits, projected onto the rows it shows and the rows its shuffle filters, as the shared history
     * screen projects them. The fetched provider entries and the held pages stay canonical.
     */
    overrides: Flow<Map<ContentRef, SongMetadataOverride>>? = null,
) : RemoteHistoryPager {
    override val source = HistorySource.REMOTE
    override val playable = true
    override val rowMenu = true
    override var sections by mutableStateOf<List<HistorySectionUi>>(emptyList())
    override var query by mutableStateOf("")
    override var loading by mutableStateOf(false)
    override var failed by mutableStateOf(false)
    override var canLoadMore by mutableStateOf(false)
    override var busy by mutableStateOf(false)
    override var paginationUnavailable by mutableStateOf(false)
    private var initialized = false
    private var edits: Map<ContentRef, SongMetadataOverride>? = null
    private val pages = HistoryPageAccumulator<YouTubeEntry> { "${it.kind}:${it.id}" }

    init {
        // A re-edit or reset re-projects the held pages in place: same rows, same order, only the names change.
        overrides?.let { flow ->
            scope.launch { flow.collect { latest -> edits = latest; project() } }
        }
    }

    override fun loadOnce() {
        if (!initialized) reload()
    }

    override fun reload() = request(reset = true)
    override fun loadMore() {
        if (canLoadMore && !failed) request(reset = false)
    }

    private fun request(reset: Boolean) {
        if (loading || busy) return
        loading = true
        failed = false
        scope.launch {
            try {
                read(reset)
                initialized = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                failed = true
                if (failure is YouTubeHistoryRoutingUnavailable) paginationUnavailable = true
                report("account history not read: ${failure::class.simpleName}")
            } finally {
                loading = false
            }
        }
    }

    private suspend fun read(reset: Boolean) {
        if (!isCurrentAccount()) throw ContentAccountChanged()
        val token = if (reset) null else pages.page.continuation ?: return
        val page = fetch(token)
        if (!isCurrentAccount()) throw ContentAccountChanged()
        if (reset) pages.replace(page) else pages.append(requireNotNull(token), page)
        paginationUnavailable = pages.page.paginationUnavailable
        canLoadMore = pages.page.continuation != null
        project()
    }

    private fun project() {
        sections = pages.page.sections.map { section ->
            HistorySectionUi(
                key = "yt:${section.title}",
                dateLabel = section.title,
                events = section.entries.filter { it.kind == ContentKind.SONG || it.kind == ContentKind.EPISODE }.map { entry ->
                    val key = "history:youtube:${section.title.length}:${section.title}:${entry.kind}:${entry.id}"
                    val row = ContentOccurrence(key, entry.historyItem(), entry.setVideoId).withSongMetadataOverrides(edits)
                    HistoryEventUi(null, row, "")
                },
            )
        }.filter { it.events.isNotEmpty() }
    }

    override fun activate(event: HistoryEventUi, sectionKey: String) {
        if (isCurrentAccount()) play(event.row)
    }

    /** A complete finite shuffle, never a silent shuffle of the already loaded prefix. */
    override fun shuffle(query: String) {
        if (loading || busy || failed) return
        busy = true
        scope.launch {
            try {
                if (!isCurrentAccount()) throw ContentAccountChanged()
                if (!initialized) {
                    read(reset = true)
                    initialized = true
                }
                var count = 0
                while (pages.page.continuation != null && count++ < MAX_SHUFFLE_PAGES) read(reset = false)
                check(pages.page.continuation == null) { "History shuffle page bound reached" }
                if (paginationUnavailable) throw YouTubeHistoryRoutingUnavailable()
                if (!isCurrentAccount()) throw ContentAccountChanged()
                val rows = sections.flatMap { it.events }.map { it.row }.filter {
                    query.isBlank() || it.item.title.contains(query, true) || it.item.subtitle.contains(query, true)
                }
                if (rows.isNotEmpty()) playShuffled(rows.shuffled())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                failed = true
                report("account history shuffle not read: ${failure::class.simpleName}")
            } finally {
                busy = false
            }
        }
    }

    private companion object { const val MAX_SHUFFLE_PAGES = 100 }
}

private fun YouTubeEntry.historyItem() = ContentItem(
    ref = ContentRef(ContentProvider.YOUTUBE.id, kind, id),
    title = title,
    subtitle = subtitle,
    artworkUri = artworkUri,
    durationMs = durationSeconds?.times(1_000L),
    explicit = explicit,
    album = album,
    artists = artists,
    albumId = albumId,
    isVideo = isVideo,
)
