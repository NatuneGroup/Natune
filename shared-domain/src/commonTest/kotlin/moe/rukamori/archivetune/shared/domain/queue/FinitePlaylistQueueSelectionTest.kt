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
 * Characterizes how a queue starts on the song the user picked. The chosen occurrence is found by its set
 * video id, or by its endpoint index when no set id exists. Duplicates are never guessed at.
 */
class FinitePlaylistQueueSelectionTest {
    /** Index 0 is A (set s1, original 0), 1 is B (set s2, original 1), 2 is A again (set s3, original 2). */
    private val entries =
        FinitePlaylistOccurrences.append(
            "PL1",
            emptyList<FinitePlaylistEntry<String>>(),
            listOf(song("A", "s1", 0), song("B", "s2", 1), song("A", "s3", 2)),
        )

    @Test
    fun aSetVideoIdPicksExactlyThatOccurrence() {
        assertEquals(2, FinitePlaylistSelection.selectedIndex(entries, FinitePlaylistLocator("A", "s3", null)))
        assertEquals(0, FinitePlaylistSelection.selectedIndex(entries, FinitePlaylistLocator("A", "s1", null)))
    }

    @Test
    fun withoutASetVideoIdTheEndpointIndexTellsDuplicatesApart() {
        assertEquals(0, FinitePlaylistSelection.selectedIndex(entries, FinitePlaylistLocator("A", null, 0)))
        assertEquals(2, FinitePlaylistSelection.selectedIndex(entries, FinitePlaylistLocator("A", "  ", 2)))
    }

    @Test
    fun ambiguousOrMissingLocatorsReturnNullInsteadOfGuessing() {
        assertNull(FinitePlaylistSelection.selectedIndex(entries, FinitePlaylistLocator("A", null, null)))
        assertNull(FinitePlaylistSelection.selectedIndex(entries, FinitePlaylistLocator("A", "missing", null)))
        assertNull(FinitePlaylistSelection.selectedIndex(entries, FinitePlaylistLocator("B", null, 5)))
    }

    @Test
    fun twoSetlessCopiesWithTheSameEndpointIndexAreAmbiguous() {
        val copies =
            FinitePlaylistOccurrences.append(
                "PL1",
                emptyList<FinitePlaylistEntry<String>>(),
                listOf(song("A", null, 4), song("A", null, 4)),
            )

        assertNull(FinitePlaylistSelection.selectedIndex(copies, FinitePlaylistLocator("A", null, 4)))
    }

    @Test
    fun aMissingVideoIdStartsOnTheFirstEntry() {
        assertEquals(0, FinitePlaylistSelection.selectedIndex(entries, FinitePlaylistLocator(null, null, null)))
    }

    @Test
    fun whileTheListIsStillPagingAnUnmatchedLocatorWaitsForMoreSongs() {
        assertNull(FinitePlaylistSelection.initialSelection(entries, FinitePlaylistLocator("C", null, null), exhausted = false))
        assertNull(FinitePlaylistSelection.initialSelection(entries, FinitePlaylistLocator("A", "missing", null), exhausted = false))
    }

    @Test
    fun anExhaustedListFallsBackToAUniqueVideoIdOnly() {
        assertEquals(
            1,
            FinitePlaylistSelection.initialSelection(entries, FinitePlaylistLocator("B", "missing", null), exhausted = true),
        )
        assertEquals(0, FinitePlaylistSelection.initialSelection(entries, FinitePlaylistLocator(null, null, null), exhausted = true))
    }

    @Test
    fun anExhaustedListRefusesADuplicateFallbackOrAnAbsentVideo() {
        assertFailsWith<IncompleteFinitePlaylistException> {
            FinitePlaylistSelection.initialSelection(entries, FinitePlaylistLocator("A", "missing", null), exhausted = true)
        }
        assertFailsWith<IncompleteFinitePlaylistException> {
            FinitePlaylistSelection.initialSelection(entries, FinitePlaylistLocator("Z", null, null), exhausted = true)
        }
    }

    @Test
    fun splitKeepsEveryOtherSongAroundTheSelectedOne() {
        val split = FinitePlaylistSelection.splitAroundSelection(listOf("a", "b", "c", "d"), "c") { it }

        assertEquals(listOf("a", "b"), split.prefix)
        assertEquals(listOf("d"), split.suffix)
    }

    @Test
    fun splitWithTheFirstOrLastSelectedSongHasAnEmptySide() {
        assertEquals(emptyList(), FinitePlaylistSelection.splitAroundSelection(listOf("a", "b"), "a") { it }.prefix)
        assertEquals(emptyList(), FinitePlaylistSelection.splitAroundSelection(listOf("a", "b"), "b") { it }.suffix)
    }

    @Test
    fun splitUsesOccurrenceKeysSoADuplicateIsNotRestarted() {
        val selected = FinitePlaylistSelection.selectedIndex(entries, FinitePlaylistLocator("A", "s3", null))!!

        val split = FinitePlaylistSelection.splitAroundSelection(entries, entries[selected].key) { it.key }

        assertEquals(listOf("s1", "s2"), split.prefix.map { it.setVideoId })
        assertEquals(emptyList(), split.suffix)
    }

    @Test
    fun aPreloadThatNoLongerMatchesTheListIsIncomplete() {
        assertFailsWith<IncompleteFinitePlaylistException> {
            FinitePlaylistSelection.splitAroundSelection(listOf("a", "b"), "missing") { it }
        }
    }
}
