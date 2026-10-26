/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.shared.ui.screens

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import moe.rukamori.archivetune.shared.domain.repository.*

data class PagedContentState(
    val query: ContentQuery? = null,
    val items: List<ContentOccurrence> = emptyList(),
    val continuation: String? = null,
    val loading: Boolean = false,
    val initialized: Boolean = false,
    val failed: Boolean = false,
    val request: ContentRequest? = null,
) {
    val complete: Boolean get() = initialized && !loading && !failed && continuation == null
}

/** A page's presentation lifetime, not a repository/cache or a second playback/queue owner. */
class PagedContentPresenter(private val repository: ContentRepository, private val scope: CoroutineScope) {
    private val mutableState = MutableStateFlow(PagedContentState())
    val state: StateFlow<PagedContentState> = mutableState.asStateFlow()
    private var pageJob: Job? = null
    private var generation = 0L
    private val consumed = mutableSetOf<String>()

    fun open(query: ContentQuery) {
        pageJob?.cancel()
        generation++
        consumed.clear()
        mutableState.value = PagedContentState(query = query)
        request(null)
    }

    fun open(request: ContentRequest) {
        require(repository is TypedContentRepository) { "Typed sections/filters require the typed repository contract" }
        require(repository.provider == request.provider) { "Foreign content provider" }
        pageJob?.cancel()
        generation++
        consumed.clear()
        mutableState.value = PagedContentState(request = request)
        request(null)
    }

    fun loadMore() {
        val current = state.value
        if (current.loading || current.failed) return
        current.continuation?.let(::request)
    }

    fun retry() {
        val current = state.value
        if (!current.failed || current.loading) return
        request(current.continuation)
    }

    fun close() {
        generation++
        pageJob?.cancel()
        pageJob = null
        mutableState.value = state.value.copy(loading = false)
    }

    private fun request(token: String?) {
        val before = state.value
        if (before.query == null && before.request == null) return
        val version = generation
        pageJob?.cancel()
        mutableState.value = before.copy(loading = true, failed = false)
        pageJob = scope.launch {
            try {
                val page = before.request?.let { (repository as TypedContentRepository).page(it, token) }
                    ?: repository.page(requireNotNull(before.query), token)
                ensureActive()
                if (version != generation) return@launch
                val appended = if (token == null) page.items else before.items + page.items
                require(appended.all { it.occurrenceId.isNotBlank() })
                require(appended.map { it.occurrenceId }.toSet().size == appended.size) {
                    "Repeated occurrence key"
                }
                require(page.continuation == null || (page.continuation != token && page.continuation !in consumed)) {
                    "Continuation cycle"
                }
                token?.let(consumed::add)
                mutableState.value = before.copy(items = appended.toList(), continuation = page.continuation,
                    initialized = true, failed = false, loading = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Raw transport messages may contain URLs/account data; UI shows a localized retry state.
                if (version == generation) mutableState.value = before.copy(failed = true, loading = false)
            } finally {
                if (version == generation) mutableState.value = state.value.copy(loading = false)
            }
        }
    }
}
