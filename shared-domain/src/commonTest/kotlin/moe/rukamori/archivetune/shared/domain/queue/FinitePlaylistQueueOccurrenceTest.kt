package moe.rukamori.archivetune.shared.domain.queue

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Characterizes how a finite playlist keeps duplicate songs as separate occurrences while it pages.
 * The set video id is the identity that survives a repeat, and an ordinal key never collapses.
 */
class FinitePlaylistQueueOccurrenceTest {
    private fun appendAll(vararg songs: FinitePlaylistSong<String>): List<FinitePlaylistEntry<String>> =
        FinitePlaylistOccurrences.append("PL1", emptyList<FinitePlaylistEntry<String>>(), songs.toList())

    @Test
    fun browsePrefixIsStrippedTheSameWayTwice() {
        assertEquals("PL1", FinitePlaylistIds.normalize("PL1"))
        assertEquals("PL1", FinitePlaylistIds.normalize("VLPL1"))
        assertEquals("PL1", FinitePlaylistIds.normalize("VLVLPL1"))
    }

    @Test
    fun radioMixesAreNeverFiniteButOrdinaryPlaylistsAre() {
        assertTrue(FinitePlaylistIds.isFinite("PLabc"))
        assertTrue(FinitePlaylistIds.isFinite("VLOLAK5"))
        assertFalse(FinitePlaylistIds.isFinite("RDAMVMabc"))
        assertFalse(FinitePlaylistIds.isFinite("VLRDCLAK5"))
        assertFalse(FinitePlaylistIds.isFinite(null))
        assertFalse(FinitePlaylistIds.isFinite("  "))
    }

    @Test
    fun songsWithDistinctSetVideoIdsStaySeparateOccurrencesOfOneVideo() {
        val entries = appendAll(song("A", "set-1", 0), song("A", "set-2", 1))

        assertEquals(listOf("A", "A"), entries.map { it.videoId })
        assertEquals(listOf("set-1", "set-2"), entries.map { it.setVideoId })
        assertEquals(listOf("PL1:set:set-1", "PL1:set:set-2"), entries.map { it.key })
    }

    @Test
    fun aRepeatedSetVideoIdCollapsesWithinOnePageAndAcrossPages() {
        assertEquals(1, appendAll(song("A", "set-1", 0), song("A", "set-1", 0)).size)

        val first = appendAll(song("A", "set-1", 0))
        val second = FinitePlaylistOccurrences.append("PL1", first, listOf(song("A", "set-1", 0), song("B", "set-2", 1)))

        assertEquals(listOf("A", "B"), second.map { it.videoId })
        assertEquals(listOf("set-1", "set-2"), second.map { it.setVideoId })
    }

    @Test
    fun songsWithoutASetVideoIdAreNeverCollapsed() {
        val entries = appendAll(song("A"), song("A"))

        assertEquals(2, entries.size)
        assertEquals(listOf("PL1:ordinal:0", "PL1:ordinal:1"), entries.map { it.key })
    }

    @Test
    fun blankSetVideoIdCountsAsMissing() {
        val entries = appendAll(song("A", " "), song("A", " "))

        assertEquals(2, entries.size)
        assertEquals(listOf(null, null), entries.map { it.setVideoId })
        assertEquals(listOf("PL1:ordinal:0", "PL1:ordinal:1"), entries.map { it.key })
    }

    @Test
    fun mixedDuplicatesKeepEveryOrdinalAndOneSetKeyedCopy() {
        val entries = appendAll(song("A", "set-1"), song("A"), song("A"), song("A", "set-1"))

        assertEquals(listOf("PL1:set:set-1", "PL1:ordinal:1", "PL1:ordinal:2"), entries.map { it.key })
    }

    @Test
    fun occurrenceKeyPrefersSetVideoIdAndIsScopedToThePlaylist() {
        assertEquals("PL1:set:s9", FinitePlaylistOccurrences.key("PL1", "s9", 4))
        assertEquals("PL1:ordinal:4", FinitePlaylistOccurrences.key("PL1", null, 4))
        assertEquals("PL1:ordinal:4", FinitePlaylistOccurrences.key("PL1", "", 4))
        assertFalse(FinitePlaylistOccurrences.key("PL1", "s9", 0) == FinitePlaylistOccurrences.key("PL2", "s9", 0))
    }

    @Test
    fun finalPageExhaustsTheListAndReportsOnlyTheAddedOccurrences() {
        val snapshot =
            FinitePlaylistSnapshot<String>(
                title = "Playlist",
                entries = appendAll(song("A", "s1", 0)),
                continuation = "t1",
                phase = FinitePlaylistPhase.PAGEABLE,
            )

        val paging =
            FinitePlaylistPagingPolicy.applyNextPage(
                "PL1",
                snapshot,
                page(song("B", "s2", 1), song("A", "s3", 2)),
            )

        assertEquals(FinitePlaylistPhase.EXHAUSTED, paging.snapshot.phase)
        assertNull(paging.snapshot.continuation)
        assertEquals(setOf("t1"), paging.snapshot.consumedTokens)
        assertEquals(listOf("A", "B", "A"), paging.snapshot.entries.map { it.videoId })
        assertEquals(listOf("PL1:set:s2", "PL1:set:s3"), paging.added.map { it.key })
    }

    @Test
    fun pageWithAContinuationStaysPageable() {
        val snapshot = FinitePlaylistSnapshot<String>(continuation = "t1", phase = FinitePlaylistPhase.PAGEABLE)

        val paging = FinitePlaylistPagingPolicy.applyNextPage("PL1", snapshot, page(song("B"), continuation = "t2"))

        assertEquals(FinitePlaylistPhase.PAGEABLE, paging.snapshot.phase)
        assertEquals("t2", paging.snapshot.continuation)
        assertEquals(setOf("t1"), paging.snapshot.consumedTokens)
    }

    @Test
    fun consumedTokensGrowWithEveryPage() {
        val first =
            FinitePlaylistPagingPolicy.applyNextPage(
                "PL1",
                FinitePlaylistSnapshot<String>(continuation = "t1", phase = FinitePlaylistPhase.PAGEABLE),
                page(song("B"), continuation = "t2"),
            )
        val second = FinitePlaylistPagingPolicy.applyNextPage("PL1", first.snapshot, page(song("C")))

        assertEquals(setOf("t1", "t2"), second.snapshot.consumedTokens)
        assertEquals(listOf("B", "C"), second.snapshot.entries.map { it.videoId })
    }

    @Test
    fun pageWithoutAPendingTokenIsRefused() {
        val snapshot = FinitePlaylistSnapshot<String>(phase = FinitePlaylistPhase.PAGEABLE)

        assertFailsWith<IncompleteFinitePlaylistException> {
            FinitePlaylistPagingPolicy.applyNextPage("PL1", snapshot, page(song("B")))
        }
    }

    @Test
    fun pageThatRepeatsAConsumedTokenFails() {
        val snapshot = FinitePlaylistSnapshot<String>(continuation = "t1", phase = FinitePlaylistPhase.PAGEABLE)

        assertFailsWith<IncompleteFinitePlaylistException> {
            FinitePlaylistPagingPolicy.applyNextPage("PL1", snapshot, page(song("B"), continuation = "t1"))
        }
    }

    @Test
    fun emptyFinalPageIsRejectedAsAnUnrecognizedEnvelope() {
        val snapshot = FinitePlaylistSnapshot<String>(continuation = "t1", phase = FinitePlaylistPhase.PAGEABLE)

        assertFailsWith<IncompleteFinitePlaylistException> {
            FinitePlaylistPagingPolicy.applyNextPage("PL1", snapshot, page())
        }
    }

    @Test
    fun setVideoIdAlreadyLoadedIsNotAddedAgain() {
        val snapshot =
            FinitePlaylistSnapshot<String>(
                entries = appendAll(song("A", "s1", 0)),
                continuation = "t1",
                phase = FinitePlaylistPhase.PAGEABLE,
            )

        val paging = FinitePlaylistPagingPolicy.applyNextPage("PL1", snapshot, page(song("A", "s1", 0)))

        assertTrue(paging.added.isEmpty())
        assertEquals(1, paging.snapshot.entries.size)
        assertEquals(FinitePlaylistPhase.EXHAUSTED, paging.snapshot.phase)
    }

    @Test
    fun failedLoadKeepsItsEntriesAndStopsPaging() {
        val snapshot =
            FinitePlaylistSnapshot<String>(
                entries = appendAll(song("A", "s1", 0)),
                continuation = "t1",
                phase = FinitePlaylistPhase.PAGEABLE,
            )

        val failed = FinitePlaylistPagingPolicy.fail(snapshot)

        assertEquals(FinitePlaylistPhase.FAILED, failed.phase)
        assertEquals(snapshot.entries, failed.entries)
    }
}
