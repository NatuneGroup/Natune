package moe.rukamori.archivetune.shared.ui.screens

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import moe.rukamori.archivetune.shared.domain.repository.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PagedContentPresenterTest {
    private val song = ContentItem(ContentRef("youtube", ContentKind.SONG, "same"), "Repeated song")
    private fun row(id: String) = ContentOccurrence(id, song, "set-$id")
    @Test fun typedSectionAndFilterSurviveContinuationWithoutLegacyProjection() = runTest {
        val request = ContentRequest(ContentProvider.YOUTUBE, ContentSurface.SEARCH, "podcasts",
            section = ContentSection("opaque", "+/=&?"), filter = ContentSearchFilter.EPISODES)
        val calls = mutableListOf<Pair<ContentRequest, String?>>()
        val repository = object : TypedContentRepository {
            override val provider = ContentProvider.YOUTUBE
            override suspend fun page(request: ContentRequest, continuation: String?): ContentPage {
                calls += request to continuation
                return if (continuation == null) ContentPage(listOf(row("a")), "next") else ContentPage(listOf(row("b")))
            }
            override suspend fun item(ref: ContentRef): ContentItem = error("unused")
        }
        val presenter = PagedContentPresenter(repository, backgroundScope)
        presenter.open(request); runCurrent()
        presenter.loadMore(); runCurrent()
        assertEquals(listOf(request to null, request to "next"), calls)
        assertEquals(request, presenter.state.value.request)
        assertTrue(presenter.state.value.complete)
        assertFailsWith<IllegalArgumentException> { PagedContentPresenter(Repository { _, _ -> ContentPage(emptyList()) },
            backgroundScope).open(request) }
    }
    private class Repository(val load: suspend (ContentQuery, String?) -> ContentPage) : ContentRepository {
        override suspend fun page(query: ContentQuery, continuation: String?) = load(query, continuation)
        override suspend fun item(ref: ContentRef): ContentItem = error("unused in paging")
    }
    @Test fun continuationKeepsDuplicateMediaAndMembershipInExactOrder() = runTest {
        val calls = mutableListOf<String?>()
        val presenter = PagedContentPresenter(Repository { _, token ->
            calls += token
            if (token == null) ContentPage(listOf(row("first")), "next") else ContentPage(listOf(row("second")))
        }, backgroundScope)
        presenter.open(ContentQuery("playlist")); runCurrent()
        presenter.loadMore(); presenter.loadMore(); runCurrent()
        assertEquals(listOf(null, "next"), calls)
        assertEquals(listOf("first", "second"), presenter.state.value.items.map { it.occurrenceId })
        assertEquals(listOf("set-first", "set-second"), presenter.state.value.items.map { it.setVideoId })
        assertTrue(presenter.state.value.complete)
    }
    @Test fun failedTailRetainsRowsAndRetryUsesThatSameContinuation() = runTest {
        var fail = true
        val presenter = PagedContentPresenter(Repository { _, token ->
            if (token == null) ContentPage(listOf(row("first")), "next")
            else if (fail) error("network") else ContentPage(listOf(row("second")))
        }, backgroundScope)
        presenter.open(ContentQuery("playlist")); runCurrent()
        presenter.loadMore(); runCurrent()
        assertEquals(1, presenter.state.value.items.size)
        assertFalse(presenter.state.value.complete)
        assertTrue(presenter.state.value.failed)
        fail = false; presenter.retry(); runCurrent()
        assertEquals(2, presenter.state.value.items.size)
        assertFalse(presenter.state.value.failed)
    }
    @Test fun reloadRejectsAnOldUncooperativeResponse() = runTest {
        val old = CompletableDeferred<ContentPage>()
        val presenter = PagedContentPresenter(Repository { query, _ ->
            if (query.text == "old") withContext(NonCancellable) { old.await() } else ContentPage(listOf(row("new")))
        }, backgroundScope)
        presenter.open(ContentQuery("search", "old")); runCurrent()
        presenter.open(ContentQuery("search", "new")); runCurrent()
        old.complete(ContentPage(listOf(row("old")))); runCurrent()
        assertEquals("new", presenter.state.value.items.single().occurrenceId)
    }
    @Test fun cancellationIsNotPresentedAsNetworkFailure() = runTest {
        val presenter = PagedContentPresenter(Repository { _, _ -> throw CancellationException("cancelled") }, backgroundScope)
        presenter.open(ContentQuery("home")); runCurrent()
        assertFalse(presenter.state.value.failed)
        assertFalse(presenter.state.value.loading)
    }
    @Test fun continuationCyclesAreVisibleFailuresNotAnInfiniteLoader() = runTest {
        val presenter = PagedContentPresenter(Repository { _, token ->
            ContentPage(listOf(row(if (token == null) "a" else "b")), "loop")
        }, backgroundScope)
        presenter.open(ContentQuery("playlist")); runCurrent()
        presenter.loadMore(); runCurrent()
        assertTrue(presenter.state.value.failed)
        assertFalse(presenter.state.value.complete)
        assertEquals(listOf("a"), presenter.state.value.items.map { it.occurrenceId })
    }
    @Test fun duplicateOccurrenceIsRejectedButDuplicateMediaIsNot() = runTest {
        val presenter = PagedContentPresenter(Repository { _, _ ->
            ContentPage(listOf(row("same"), row("same")))
        }, backgroundScope)
        presenter.open(ContentQuery("playlist")); runCurrent()
        assertTrue(presenter.state.value.failed)
        assertTrue(presenter.state.value.items.isEmpty())
    }
}
