/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.queue

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Characterizes whole-list completion. A list is complete only when every continuation is accounted for,
 * so any short, repeated, unbounded or unprovable walk fails closed and commits nothing.
 */
class FinitePlaylistQueueCompletionTest {
    @Test
    fun requestBudgetAndTimeoutMatchTheShippedLoader() {
        assertEquals(200, FinitePlaylistCompletion.MAX_REQUESTS)
        assertEquals(120_000L, FinitePlaylistCompletion.TIMEOUT_MS)
    }

    @Test
    fun blankOrRepeatedContinuationIsIncomplete() {
        assertFailsWith<IncompleteFinitePlaylistException> {
            FinitePlaylistCompletion.validateNext("  ", emptySet(), songCount = 3)
        }
        assertFailsWith<IncompleteFinitePlaylistException> {
            FinitePlaylistCompletion.validateNext("t1", setOf("t1"), songCount = 3)
        }
    }

    @Test
    fun emptyPageWithoutContinuationIsIncompleteExceptAsTheFirstPage() {
        assertFailsWith<IncompleteFinitePlaylistException> {
            FinitePlaylistCompletion.validateNext(null, emptySet(), songCount = 0)
        }
        FinitePlaylistCompletion.validateNext(null, emptySet(), songCount = 0, initial = true)
    }

    @Test
    fun emptyPageThatStillCarriesAContinuationIsAccepted() {
        FinitePlaylistCompletion.validateNext("t1", emptySet(), songCount = 0)
    }

    @Test
    fun emptyFirstPageIsConfirmedOnlyByAnExplicitZeroCount() {
        FinitePlaylistCompletion.requireConfirmedInitial(page(songCountText = "0 songs"))

        for (text in listOf(null, "12 songs", "Keine Titel", "0 songs, 3 more")) {
            assertFailsWith<IncompleteFinitePlaylistException>(text.toString()) {
                FinitePlaylistCompletion.requireConfirmedInitial(page(songCountText = text))
            }
        }
    }

    @Test
    fun populatedOrPagedFirstPageNeedsNoCountText() {
        FinitePlaylistCompletion.requireConfirmedInitial(page(song("A")))
        FinitePlaylistCompletion.requireConfirmedInitial(page(continuation = "t1"))
    }

    @Test
    fun collectorWalksEveryPageAndCompletesInOrder() {
        val collector = FinitePlaylistCollector(page(song("A"), continuation = "t1"))

        assertEquals("t1", collector.nextRequestToken())
        collector.accept(page(song("B"), continuation = "t2"))
        assertEquals("t2", collector.nextRequestToken())
        collector.accept(page(song("C")))
        assertNull(collector.nextRequestToken())

        val complete = collector.complete()
        assertEquals("Playlist", complete.title)
        assertEquals(listOf("A", "B", "C"), complete.songs.map { it.videoId })
    }

    @Test
    fun collectorRefusesToCompleteWhileAContinuationIsStillPending() {
        val collector = FinitePlaylistCollector(page(song("A"), continuation = "t1"))

        assertFailsWith<IncompleteFinitePlaylistException> { collector.complete() }
    }

    @Test
    fun collectorDropsRepeatedSetVideoIdsAcrossPages() {
        val collector = FinitePlaylistCollector(page(song("A", "s1"), continuation = "t1"))
        collector.nextRequestToken()
        collector.accept(page(song("A", "s1"), song("B", "s2")))

        assertEquals(listOf("A", "B"), collector.complete().songs.map { it.videoId })
    }

    @Test
    fun collectorKeepsSongsWithoutASetVideoIdEvenWhenRepeated() {
        val collector = FinitePlaylistCollector(page(song("A"), song("A")))

        assertEquals(2, collector.complete().songs.size)
    }

    @Test
    fun collectorDropsRepeatedSetVideoIdsWithinTheFirstPage() {
        val collector = FinitePlaylistCollector(page(song("A", "s1"), song("A", "s1")))

        assertEquals(1, collector.complete().songs.size)
    }

    @Test
    fun collectorRejectsAContinuationThatRepeatsAnEarlierOne() {
        val collector = FinitePlaylistCollector(page(song("A"), continuation = "t1"))
        collector.nextRequestToken()

        assertFailsWith<IncompleteFinitePlaylistException> {
            collector.accept(page(song("B"), continuation = "t1"))
        }
    }

    @Test
    fun collectorRejectsAnEmptyTerminalPage() {
        val collector = FinitePlaylistCollector(page(song("A"), continuation = "t1"))
        collector.nextRequestToken()

        assertFailsWith<IncompleteFinitePlaylistException> { collector.accept(page()) }
    }

    @Test
    fun acceptWithoutAPendingTokenIsAProgrammingError() {
        val collector = FinitePlaylistCollector(page(song("A")))

        assertFailsWith<IllegalStateException> { collector.accept(page(song("B"))) }
    }

    @Test
    fun collectorStopsAtTheRequestBudgetIncludingTheFirstPage() {
        val collector = FinitePlaylistCollector(page(song("s0"), continuation = "t0"))

        repeat(FinitePlaylistCompletion.MAX_REQUESTS - 1) { index ->
            assertEquals("t$index", collector.nextRequestToken())
            collector.accept(page(song("s${index + 1}"), continuation = "t${index + 1}"))
        }

        assertFailsWith<IncompleteFinitePlaylistException> { collector.nextRequestToken() }
    }

    @Test
    fun collectorRefusesAnUnconfirmedEmptyFirstPage() {
        assertFailsWith<IncompleteFinitePlaylistException> { FinitePlaylistCollector(page()) }
    }

    @Test
    fun confirmedEmptyPlaylistCompletesEmpty() {
        val complete = FinitePlaylistCollector(page(songCountText = "0")).complete()

        assertEquals(emptyList(), complete.songs)
    }

    @Test
    fun blankFirstContinuationIsIncomplete() {
        assertFailsWith<IncompleteFinitePlaylistException> {
            FinitePlaylistCollector(page(song("A"), continuation = " "))
        }
    }
}
