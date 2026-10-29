package moe.rukamori.archivetune.shared.ui.host

import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.rukamori.archivetune.shared.data.provider.*
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence

@OptIn(ExperimentalCoroutinesApi::class)
class YouTubeHistoryPagerTest {
    @Test fun overlappingPagesKeepEpisodesAndStableOccurrenceKeysAcrossDates() = runTest {
        val calls = ArrayList<String?>()
        val pager = YouTubeHistoryPager(this, { token ->
            calls += token
            if (token == null) page("Today", listOf(episode("one"), episode("two")), "older")
            else HistoryBrowsePage(listOf(
                HistoryBrowseSection("Today", listOf(episode("two"), episode("three"))),
                HistoryBrowseSection("Yesterday", listOf(episode("one"))),
            ))
        }, {}, {}, { error(it) })
        pager.loadOnce()
        runCurrent()
        val firstKey = pager.sections.first().events.first().row.occurrenceId
        pager.loadMore()
        runCurrent()
        assertEquals(listOf(null, "older"), calls)
        assertEquals(listOf(3, 1), pager.sections.map { it.events.size })
        assertEquals(firstKey, pager.sections.first().events.first().row.occurrenceId)
        assertNotEquals(firstKey, pager.sections.last().events.first().row.occurrenceId)
        assertTrue(pager.sections.flatMap { it.events }.all { it.row.item.ref.kind == ContentKind.EPISODE })
        assertFalse(pager.canLoadMore)
        pager.loadMore()
        pager.loadOnce()
        runCurrent()
        assertEquals(2, calls.size)
    }

    @Test fun failedAppendAndCursorCycleKeepTheLoadedArchiveForExplicitRetry() = runTest {
        var fail = true
        var calls = 0
        val errors = ArrayList<String>()
        val pager = YouTubeHistoryPager(this, { token ->
            calls++
            if (token == null) page("Today", listOf(episode("one")), "next")
            else if (fail) error("temporary")
            else page("Yesterday", listOf(episode("two")))
        }, {}, {}, { errors += it })
        pager.loadOnce(); runCurrent()
        pager.loadMore(); runCurrent()
        assertTrue(pager.failed)
        assertTrue(pager.canLoadMore)
        assertEquals("one", pager.sections.single().events.single().row.item.ref.id)
        pager.loadMore(); runCurrent()
        assertEquals(2, calls, "An automatic near-tail event must not retry a failed page in a loop")
        fail = false
        pager.reload(); runCurrent()
        pager.loadMore(); runCurrent()
        assertEquals(listOf("one", "two"), pager.sections.flatMap { it.events }.map { it.row.item.ref.id })
        assertFalse(pager.failed)
        assertEquals(1, errors.size)
    }

    @Test fun shuffleFetchesTheEntireArchiveInsteadOfOnlyTheVisiblePrefix() = runTest {
        var played = emptyList<ContentOccurrence>()
        val pager = YouTubeHistoryPager(this, { token ->
            if (token == null) page("Today", listOf(episode("one")), "older")
            else page("Yesterday", listOf(episode("two")))
        }, {}, { played = it }, { error(it) })
        pager.loadOnce(); runCurrent()
        pager.shuffle(""); runCurrent()
        assertEquals(setOf("one", "two"), played.map { it.item.ref.id }.toSet())
        assertFalse(pager.busy)
        assertFalse(pager.canLoadMore)
    }

    @Test fun aCyclicOrRouteLimitedTailCannotStartAPartialShuffle() = runTest {
        for (routeLimited in listOf(false, true)) {
            var played = false
            val errors = ArrayList<String>()
            val pager = YouTubeHistoryPager(this, { token ->
                if (routeLimited) page("Today", listOf(episode("one"))).copy(paginationUnavailable = true)
                else page("Today", listOf(episode("one")), "cycle")
            }, {}, { played = true }, { errors += it })
            pager.loadOnce(); runCurrent()
            pager.shuffle(""); runCurrent()
            assertFalse(played)
            assertTrue(pager.failed)
            assertEquals(routeLimited, pager.paginationUnavailable)
            assertEquals(1, errors.size)
        }
    }

    @Test fun shuffleBeforeTheFirstLoadStillReadsEveryPage() = runTest {
        val calls = ArrayList<String?>()
        var played = emptyList<ContentOccurrence>()
        val pager = YouTubeHistoryPager(this, { token ->
            calls += token
            if (token == null) page("Today", listOf(episode("one")), "older")
            else page("Yesterday", listOf(episode("two")))
        }, {}, { played = it }, { error(it) })
        pager.shuffle("")
        runCurrent()
        assertEquals(listOf(null, "older"), calls)
        assertEquals(setOf("one", "two"), played.map { it.item.ref.id }.toSet())
    }

    @Test fun switchingAccountWhileARequestIsPendingDiscardsTheResponseAndOldActions() = runTest {
        val answer = CompletableDeferred<YouTubeHistoryPage>()
        var current = true
        var played = false
        val errors = ArrayList<String>()
        val pager = YouTubeHistoryPager(this, { answer.await() }, { played = true }, { played = true },
            { errors += it }, isCurrentAccount = { current })
        pager.loadOnce(); runCurrent()
        current = false
        answer.complete(page("Today", listOf(episode("old-account"))))
        runCurrent()
        assertTrue(pager.sections.isEmpty())
        assertTrue(pager.failed)
        pager.shuffle(""); runCurrent()
        assertFalse(played)
        assertEquals(1, errors.size)
    }

    private fun episode(id: String) = YouTubeEntry(ContentKind.EPISODE, id, "Episode $id")
    private fun page(date: String, entries: List<YouTubeEntry>, token: String? = null): YouTubeHistoryPage =
        HistoryBrowsePage(listOf(HistoryBrowseSection(date, entries)), token)
}
