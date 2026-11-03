package moe.rukamori.archivetune.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.shared.data.provider.JvmYouTubeCatalogueTransport
import moe.rukamori.archivetune.shared.data.provider.YouTubeContentRepository
import moe.rukamori.archivetune.shared.data.provider.YouTubeEntry
import moe.rukamori.archivetune.shared.data.provider.YouTubeShelfPage
import moe.rukamori.archivetune.shared.data.provider.selectYouTubeFeedChip
import moe.rukamori.archivetune.shared.domain.queue.FinitePlaylistCompletion
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.ContentRequest
import moe.rukamori.archivetune.shared.domain.repository.ContentSection
import moe.rukamori.archivetune.shared.domain.repository.ContentSurface
import moe.rukamori.archivetune.shared.domain.repository.QueueStart
import moe.rukamori.archivetune.shared.ui.host.LibraryPageOwners
import moe.rukamori.archivetune.shared.ui.screens.PagedContentState

/**
 * The desktop catalogue owners: the feeds behind Home, Discovery, Charts, Moods, Browse and Releases, plus the
 * artist page. Every one of them reads the retained InnerTube client through [JvmYouTubeCatalogueTransport] and keeps
 * the rows it showed, so a tap on a row plays the rows the listener saw — the shelf is the queue, as the Android
 * screens queue their shelves.
 *
 * Main-thread only: feeds write their state from launched reads and taps read it before any work leaves the thread.
 */
internal class DesktopCatalogueOwners(
    val catalogue: JvmYouTubeCatalogueTransport,
    val youtube: YouTubeContentRepository,
    val owners: LibraryPageOwners,
) {
    private val scope: CoroutineScope get() = owners.scope

    /**
     * Rows shown per collection. Shelf-keyed collections (home, discovery, charts and browse-shelf pages) hold one
     * row group per shelf key; list collections (a browse or artist-items page) hold the page's own order. Insertion
     * order is the age order, so the oldest page's rows are dropped first once the bound is reached.
     */
    private val shownShelves = LinkedHashMap<ContentRequest, LinkedHashMap<String, List<ContentOccurrence>>>()
    private val shownLists = LinkedHashMap<ContentRequest, List<ContentOccurrence>>()

    fun recordShelf(collection: ContentRequest, shelfKey: String, rows: List<ContentOccurrence>) {
        val shelves = shownShelves.getOrPut(collection) { LinkedHashMap() }
        shelves.remove(shelfKey)
        shelves[shelfKey] = rows
        shownShelves.remove(collection)
        shownShelves[collection] = shelves
        while (shownShelves.size > MAX_SHOWN_PAGES) shownShelves.remove(shownShelves.keys.first())
    }

    fun recordList(collection: ContentRequest, rows: List<ContentOccurrence>) {
        shownLists.remove(collection)
        shownLists[collection] = rows
        while (shownLists.size > MAX_SHOWN_PAGES) shownLists.remove(shownLists.keys.first())
    }

    /**
     * Plays a catalogue row. A shelf row plays that shelf's playable rows from the tapped one; a list row plays the
     * complete list, read to its end first. Returns false when [collection] is not a catalogue page, so the host
     * plays it through its library owner instead.
     */
    fun playOccurrence(collection: ContentRequest, start: QueueStart): Boolean {
        shownShelves[collection]?.let { shelves ->
            val rows = shelves.values.firstOrNull { it.any { row -> row.occurrenceId == start.occurrenceId } }
                ?: return@let
            val playable = rows.filter { it.item.ref.kind == ContentKind.SONG || it.item.ref.kind == ContentKind.EPISODE }
            if (playable.none { it.occurrenceId == start.occurrenceId }) {
                owners.report("the tapped row is not playable in its shelf")
                return@let
            }
            scope.launch {
                try {
                    owners.queue.replace(playable, start.occurrenceId ?: playable.first { it.item.ref == start.item }.occurrenceId, playWhenReady = true)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    owners.report("the shelf could not be played")
                }
            }
            return true
        }
        shownLists[collection]?.let { held ->
            playCatalogueList(collection, held, startOccurrenceId = start.occurrenceId)
            return true
        }
        return false
    }

    /** Plays a whole list from its first row, or from a shuffled order when [shuffled] is set. */
    fun playList(collection: ContentRequest, shuffled: Boolean) {
        playCatalogueList(collection, shownLists[collection].orEmpty(), shuffled = shuffled)
    }

    /**
     * Reads the whole list, from the rows the page already holds, within the shared finite-completion bounds, then
     * hands it to the queue — the same completion rule the shared album/playlist pages follow. A list that repeats a
     * page or outgrows the bounds is incomplete and is not played at all.
     */
    private fun playCatalogueList(
        collection: ContentRequest,
        held: List<ContentOccurrence>,
        shuffled: Boolean = false,
        startOccurrenceId: String? = null,
    ) {
        scope.launch {
            val entries = try {
                withTimeoutOrNull(FinitePlaylistCompletion.TIMEOUT_MS) { completeCatalogueList(collection, held) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (entries.isNullOrEmpty()) {
                return@launch owners.report("the list could not be read to its end, so it was not played")
            }
            val playable = entries.filter { it.item.ref.kind == ContentKind.SONG || it.item.ref.kind == ContentKind.EPISODE }
            if (playable.isEmpty()) return@launch owners.report("the list holds no playable rows")
            val order = if (shuffled) playable.shuffled() else playable
            val startId = startOccurrenceId?.takeIf { id -> order.any { it.occurrenceId == id } }
                ?: order.first().occurrenceId
            try {
                owners.queue.replace(order, startId, playWhenReady = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                owners.report("the list could not be played")
            }
        }
    }

    private suspend fun completeCatalogueList(
        collection: ContentRequest,
        held: List<ContentOccurrence>,
    ): List<ContentOccurrence>? {
        val rows = ArrayList<ContentOccurrence>()
        var next: String?
        if (held.isNotEmpty()) {
            rows += held
            next = heldContinuation(collection)
        } else {
            val first = youtube.page(collection, null)
            rows += first.items
            next = first.continuation
        }
        val consumed = HashSet<String>()
        var pages = 0
        while (next != null && pages < FinitePlaylistCompletion.MAX_REQUESTS) {
            if (!consumed.add(next)) return null
            val page = youtube.page(collection, next)
            rows += page.items
            next = page.continuation
            pages++
        }
        return if (next == null) rows else null
    }

    /** The continuation the page was left on when its rows were recorded, or null when it had none. */
    private var heldContinuations = LinkedHashMap<ContentRequest, String?>()

    fun recordContinuation(collection: ContentRequest, continuation: String?) {
        heldContinuations.remove(collection)
        heldContinuations[collection] = continuation
        while (heldContinuations.size > MAX_SHOWN_PAGES) heldContinuations.remove(heldContinuations.keys.first())
    }

    private fun heldContinuation(collection: ContentRequest): String? = heldContinuations[collection]

    val home = DesktopHomeFeed(this)
    val charts = DesktopShelfFeed(this) { catalogue.charts(it) }
    /** Discovery's SUGGESTIONS tab: the provider's suggestion feed, which is the un-filtered home feed read. */
    val suggestions = DesktopShelfFeed(this) { catalogue.home(it, null) }
    val explore = DesktopExploreFeed(this)
    val releases = DesktopReleasesFeed(this)

    private val artists = LinkedHashMap<ContentRef, DesktopArtistOwner>()
    private val sectionFeeds = LinkedHashMap<ContentRequest, DesktopSectionFeed>()

    /**
     * The flat category browse a mood/genre tile or a shelf's "see all" opens: one section read, paged flat like
     * the Android browse screen (the provider sends no continuation for these pages).
     */
    fun browseSection(request: ContentRequest): DesktopSectionFeed =
        sectionFeeds.getOrPut(request) {
            while (sectionFeeds.size >= MAX_SHOWN_PAGES) sectionFeeds.remove(sectionFeeds.keys.first())
            val section = request.section!!
            DesktopSectionFeed(this) { catalogue.browseSection(section.id, section.params) }
        }

    fun artist(ref: ContentRef): DesktopArtistOwner =
        artists.getOrPut(ref) {
            while (artists.size >= MAX_SHOWN_PAGES) artists.remove(artists.keys.first())
            DesktopArtistOwner(this, ref)
        }

    internal fun report(message: String) = owners.report(message)
    internal fun launch(block: suspend () -> Unit) = scope.launch {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            owners.report("catalogue read failed: ${failure::class.simpleName} ${failure.message}")
        }
    }

    internal companion object {
        /** A few pages back in the navigation stack; an older page reloads its first rows when it plays. */
        const val MAX_SHOWN_PAGES = 8
    }
}

/**
 * The home feed: shelves, the chip bar and the shelf continuation. A chip re-requests the feed with its params;
 * re-tapping the selected chip un-selects it through the provider's deselect endpoint when it gave one.
 */
internal class DesktopHomeFeed(private val owners: DesktopCatalogueOwners) {
    var shelves by mutableStateOf<List<moe.rukamori.archivetune.shared.data.provider.YouTubeShelf>>(emptyList())
        private set
    var chips by mutableStateOf<List<moe.rukamori.archivetune.shared.data.provider.YouTubeFeedChip>>(emptyList())
        private set
    var chipParams by mutableStateOf<String?>(null)
        private set
    var continuation by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(true)
        private set
    var failed by mutableStateOf(false)
        private set
    var initialized by mutableStateOf(false)
        private set
    var refreshing by mutableStateOf(false)
        private set
    /** The chip currently filtering the feed, as the Android home tracks it; null keeps the un-filtered feed. */
    var selectedChip by mutableStateOf<moe.rukamori.archivetune.shared.data.provider.YouTubeFeedChip?>(null)
        private set

    private var generation = 0

    fun reload() = load(chipParams, continuation = null, first = true)
    fun loadMore() {
        val token = continuation ?: return
        if (loading) return
        load(chipParams, token, first = false)
    }

    fun refresh() {
        if (refreshing) return
        refreshing = true
        load(chipParams, continuation = null, first = true)
    }

    fun selectChip(chip: moe.rukamori.archivetune.shared.data.provider.YouTubeFeedChip?) {
        val selection = selectYouTubeFeedChip(selectedChip, chip)
        selectedChip = selection.selected
        chipParams = selection.params
        reload()
    }

    private fun load(params: String?, continuation: String?, first: Boolean) {
        val gen = ++generation
        loading = true
        failed = false
        owners.launch {
            // Continuation reads send the token alone, as the Android feed does; the chip's params belong to
            // first-page re-reads only. The page keeps the raw shelves: the song block list filters at render in
            // the home page, so an unblock restores the cached rows without a re-read.
            val page = owners.catalogue.home(if (first) null else continuation, if (first) params else null)
            if (gen != generation) return@launch
            if (first) {
                shelves = page.shelves
                chips = page.chips
                initialized = true
            } else {
                shelves = shelves + page.shelves
            }
            this@DesktopHomeFeed.continuation = page.continuation
        }.invokeOnCompletion {
            if (gen == generation) {
                loading = false
                refreshing = false
                if (it != null && it !is CancellationException) failed = true
            }
        }
    }
}

/** One paged shelf feed — the charts page reads its sections this way. */
internal class DesktopShelfFeed(
    private val owners: DesktopCatalogueOwners,
    private val read: suspend (continuation: String?) -> YouTubeShelfPage,
) {
    var shelves by mutableStateOf<List<moe.rukamori.archivetune.shared.data.provider.YouTubeShelf>>(emptyList())
        private set
    var continuation by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(true)
        private set
    var failed by mutableStateOf(false)
        private set
    var initialized by mutableStateOf(false)
        private set

    private var generation = 0

    fun reload() = load(null)
    fun loadMore() {
        val token = continuation ?: return
        if (loading) return
        load(token)
    }

    private fun load(continuation: String?) {
        val gen = ++generation
        loading = true
        failed = false
        owners.launch {
            val page = read(continuation)
            if (gen != generation) return@launch
            shelves = if (continuation == null) page.shelves else shelves + page.shelves
            this@DesktopShelfFeed.continuation = page.continuation
            initialized = true
        }.invokeOnCompletion {
            if (gen == generation) {
                loading = false
                if (it != null && it !is CancellationException) failed = true
            }
        }
    }
}

/**
 * One section browse page: the provider's shelf rows flattened into the browse screen's single list. The provider
 * sends no continuation for category pages, so the whole page is the one read.
 */
internal class DesktopSectionFeed(
    private val owners: DesktopCatalogueOwners,
    private val read: suspend () -> YouTubeShelfPage,
) {
    var entries by mutableStateOf<List<moe.rukamori.archivetune.shared.data.provider.YouTubeEntry>>(emptyList())
        private set
    var title by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(true)
        private set
    var failed by mutableStateOf(false)
        private set
    var initialized by mutableStateOf(false)
        private set

    private var generation = 0

    fun reload() {
        val gen = ++generation
        loading = true
        failed = false
        owners.launch {
            val page = read()
            if (gen != generation) return@launch
            entries = page.shelves.flatMap { it.entries }
            title = page.title
            initialized = true
        }.invokeOnCompletion {
            if (gen == generation) {
                loading = false
                if (it != null && it !is CancellationException) failed = true
            }
        }
    }
}

/** The explore page behind Discovery: the new-release shelf and the flat mood/genre tile list. */
internal class DesktopExploreFeed(private val owners: DesktopCatalogueOwners) {
    var releases by mutableStateOf<List<moe.rukamori.archivetune.shared.data.provider.YouTubeRelease>>(emptyList())
        private set
    var moods by mutableStateOf<List<moe.rukamori.archivetune.shared.data.provider.YouTubeMoodGenre>>(emptyList())
        private set
    var loading by mutableStateOf(true)
        private set
    var failed by mutableStateOf(false)
        private set
    var initialized by mutableStateOf(false)
        private set

    private var generation = 0

    fun reload() {
        val gen = ++generation
        loading = true
        failed = false
        owners.launch {
            val page = owners.catalogue.explore()
            if (gen != generation) return@launch
            releases = page.releases
            moods = page.moods
            initialized = true
        }.invokeOnCompletion {
            if (gen == generation) {
                loading = false
                if (it != null && it !is CancellationException) failed = true
            }
        }
    }
}

/** The new-releases feed: the full list the endpoint returns, filtered by the page's chips and query. */
internal class DesktopReleasesFeed(private val owners: DesktopCatalogueOwners) {
    var releases by mutableStateOf<List<moe.rukamori.archivetune.shared.data.provider.YouTubeRelease>>(emptyList())
        private set
    var loading by mutableStateOf(true)
        private set
    var failed by mutableStateOf(false)
        private set
    var initialized by mutableStateOf(false)
        private set

    private var generation = 0

    fun reload() {
        val gen = ++generation
        loading = true
        failed = false
        owners.launch {
            val list = owners.catalogue.newReleases()
            if (gen != generation) return@launch
            releases = list
            initialized = true
        }.invokeOnCompletion {
            if (gen == generation) {
                loading = false
                if (it != null && it !is CancellationException) failed = true
            }
        }
    }
}

/**
 * The remote artist page behind the artist routes. [load] re-requests the provider page; a failure keeps the last
 * good page and raises [failed] only while nothing has loaded, matching the Android screen's load-state gate.
 */
internal class DesktopArtistOwner(
    private val owners: DesktopCatalogueOwners,
    val ref: ContentRef,
) {
    var page by mutableStateOf<moe.rukamori.archivetune.shared.data.provider.YouTubeArtistDetail?>(null)
        private set
    var loading by mutableStateOf(true)
        private set
    var failed by mutableStateOf(false)
        private set

    private var generation = 0

    fun load() {
        val gen = ++generation
        loading = true
        owners.launch {
            val detail = owners.catalogue.artist(ref.id)
            if (gen != generation) return@launch
            page = detail
        }.invokeOnCompletion {
            if (gen == generation) {
                loading = false
                if (it != null && it !is CancellationException) failed = page == null
            }
        }
    }
}

/**
 * The collection key a catalogue page's rows queue under. One request is one collection, so a shelf page and a list
 * page never share a key space; the shelf key keeps one shelf's rows apart from the rest of the feed.
 */
internal fun catalogueOccurrence(request: ContentRequest, entry: YouTubeEntry, ordinal: Int): ContentOccurrence {
    val collection = requestKey(request)
    val setId = entry.setVideoId?.takeIf(String::isNotBlank)
    return ContentOccurrence(
        occurrenceId = if (setId != null) "$collection:set:$setId" else "$collection:ordinal:$ordinal",
        item = entry.toCatalogueItem(),
        setVideoId = setId,
    )
}

private fun requestKey(request: ContentRequest): String =
    "${request.provider.id}:${request.surface.id}:${request.section?.id.orEmpty()}:${request.text.orEmpty()}"

private fun YouTubeEntry.toCatalogueItem(): ContentItem =
    ContentItem(
        ref = ContentRef(ContentProvider.YOUTUBE.id, kind, id),
        title = title,
        subtitle = subtitle,
        artworkUri = artworkUri,
        durationMs = durationSeconds?.let { it * 1000L },
        explicit = explicit,
        album = album,
        artists = artists,
        albumId = albumId?.takeIf(String::isNotBlank),
    )

internal fun YouTubeEntry.toCatalogueOccurrence(collection: String, ordinal: Int): ContentOccurrence {
    val setId = setVideoId?.takeIf(String::isNotBlank)
    return ContentOccurrence(
        occurrenceId = if (setId != null) "$collection:set:$setId" else "$collection:ordinal:$ordinal",
        item = toCatalogueItem(),
        setVideoId = setId,
    )
}

/** The request a shelf's "see all" endpoint browses to, under the page's own surface. */
internal fun browseRequest(surface: ContentSurface, section: ContentSection, parent: ContentRef? = null): ContentRequest =
    ContentRequest(ContentProvider.YOUTUBE, surface, parent = parent, section = section)
