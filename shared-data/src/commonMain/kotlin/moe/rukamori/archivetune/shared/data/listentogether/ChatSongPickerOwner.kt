package moe.rukamori.archivetune.shared.data.listentogether

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.data.repository.ContentRepositoryRouter
import moe.rukamori.archivetune.shared.domain.listentogether.ChatSongPickerState
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRequest
import moe.rukamori.archivetune.shared.domain.repository.ContentSearchFilter
import moe.rukamori.archivetune.shared.domain.repository.ContentSurface

/** Debounce window for the picker's search field, as the Android picker waits before it searches. */
const val CHAT_SONG_SEARCH_DEBOUNCE_MS = 400L

/**
 * The chat song picker's search owner: a debounced YouTube song search through the typed content router, with
 * paging and retry. A query change restarts the debounce window, an answer that a newer change has overtaken is
 * dropped, and a failed search surfaces as [ChatSongPickerState.failed] rather than an empty success.
 *
 * Work and state run on the dispatcher of [scope]; the host passes its UI dispatcher and calls [close] when the
 * picker leaves composition. Shared song rows are handed to [onShare] as the chat wire model.
 */
class ChatSongPickerOwner(
    private val content: ContentRepositoryRouter,
    private val scope: CoroutineScope,
    private val onShare: (TrackInfo) -> Unit,
    private val debounceMs: Long = CHAT_SONG_SEARCH_DEBOUNCE_MS,
) {
    private val published = MutableStateFlow(ChatSongPickerState())
    val state: StateFlow<ChatSongPickerState> = published.asStateFlow()

    /** Bumped by every change that makes an in-flight answer obsolete; an answer lands only at its own generation. */
    private var generation = 0L
    private var job: Job? = null

    /** Continuation tokens already spent for the current query; a provider that repeats one is refused. */
    private val consumed = mutableSetOf<String>()

    /** Typing: a blank query clears the picker at once; any other query waits out the debounce window first. */
    fun changeQuery(text: String) {
        val current = published.value
        if (text == current.query) return
        cancelPending()
        if (text.isBlank()) {
            published.value = ChatSongPickerState(query = text)
            return
        }
        published.value = current.copy(query = text, loading = true, failed = false)
        startPage(token = null, delayMs = debounceMs)
    }

    /** Searches the current query now, without the debounce window. A blank query is ignored. */
    fun search() {
        val current = published.value
        if (current.query.isBlank()) return
        cancelPending()
        published.value = current.copy(loading = true, failed = false)
        startPage(token = null, delayMs = 0L)
    }

    /** Appends the next page when the provider reported one. Ignored while a page is in flight or after a failure. */
    fun loadMore() {
        val current = published.value
        val token = current.continuation ?: return
        if (current.loading || current.failed) return
        published.value = current.copy(loading = true)
        startPage(token = token, delayMs = 0L)
    }

    /** Repeats the failed request: the next page when one is pending, otherwise the first page of the query. */
    fun retry() {
        val current = published.value
        if (!current.failed || current.loading || current.query.isBlank()) return
        published.value = current.copy(loading = true, failed = false)
        startPage(token = current.continuation, delayMs = 0L)
    }

    /** Hands a picked song to the chat owner as a rich song card. */
    fun share(item: ContentItem) {
        onShare(item.toChatTrack())
    }

    /** Cancels the pending debounce or page, so nothing still in flight lands after the picker has closed. */
    fun close() {
        cancelPending()
        published.update { it.copy(loading = false) }
    }

    private fun cancelPending() {
        generation++
        job?.cancel()
        job = null
    }

    /** Starts one page of the current query. The answer lands only if no newer change happened meanwhile. */
    private fun startPage(token: String?, delayMs: Long) {
        if (token == null) consumed.clear()
        val version = generation
        val query = published.value.query.trim()
        job?.cancel()
        job =
            scope.launch {
                try {
                    if (delayMs > 0L) delay(delayMs)
                    val request =
                        ContentRequest(
                            provider = ContentProvider.YOUTUBE,
                            surface = ContentSurface.SEARCH,
                            text = query,
                            filter = ContentSearchFilter.SONGS,
                        )
                    val page = content.page(request, token)
                    if (version != generation) return@launch
                    val next = page.continuation
                    require(next == null || (next != token && next !in consumed)) { "Continuation cycle" }
                    if (token != null) consumed.add(token)
                    val songs = page.items.filter { it.item.ref.kind == ContentKind.SONG }
                    published.update { current ->
                        val merged = if (token == null) songs else current.items + songs
                        // A later page may repeat a row; rows are keyed by occurrence, so keep the first one.
                        current.copy(
                            items = merged.distinctBy { it.occurrenceId },
                            continuation = next,
                            loading = false,
                            initialized = true,
                            failed = false,
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failed: Exception) {
                    // Transport messages can carry URLs or account data, so the state keeps only the flag.
                    if (version == generation) published.update { it.copy(loading = false, failed = true) }
                }
            }
    }
}

/**
 * The chat song card for a picked song, matching the Android picker's mapping: the subtitle carries the artists,
 * the duration is milliseconds, and an unreported duration is zero.
 */
fun ContentItem.toChatTrack(): TrackInfo =
    TrackInfo(
        id = ref.id,
        title = title,
        artist = subtitle,
        album = album,
        duration = durationMs ?: 0L,
        thumbnail = artworkUri,
    )
