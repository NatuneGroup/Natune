/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.queue

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Characterizes the bidirectional map between timeline indices and play positions. Duplicate songs are
 * separate timeline entries, so every occurrence must map back to its own play position.
 */
class QueueShuffleOrderTest {
    private fun assertBijective(order: ShuffleOrder) {
        for (position in 0 until order.size) {
            assertEquals(position, order.playPositionOf(order.timelineIndexAt(position)), "position $position")
        }
        for (timeline in 0 until order.size) {
            assertEquals(timeline, order.timelineIndexAt(order.playPositionOf(timeline)), "timeline $timeline")
        }
        assertEquals((0 until order.size).toList(), order.playOrder().sorted())
    }

    @Test
    fun playOrderMapsEachPlayPositionToItsTimelineIndexAndBack() {
        val order = ShuffleOrder.ofPlayOrder(listOf(2, 0, 1))

        assertEquals(2, order.timelineIndexAt(0))
        assertEquals(0, order.timelineIndexAt(1))
        assertEquals(1, order.timelineIndexAt(2))
        assertEquals(0, order.playPositionOf(2))
        assertEquals(1, order.playPositionOf(0))
        assertEquals(2, order.playPositionOf(1))
        assertBijective(order)
    }

    @Test
    fun duplicateSongsKeepDistinctPlayPositionsAcrossShuffleAndRestore() {
        // Timeline order is A, B, A. Each A is its own occurrence with its own set video id.
        val timeline =
            FinitePlaylistOccurrences.append(
                "PL1",
                emptyList<FinitePlaylistEntry<String>>(),
                listOf(song("A", "set-1", 0), song("B", "set-2", 1), song("A", "set-3", 2)),
            )
        val shuffled = ShuffleOrder.ofPlayOrder(listOf(2, 0, 1))

        assertEquals(
            listOf("PL1:set:set-3", "PL1:set:set-1", "PL1:set:set-2"),
            shuffled.playOrder().map { timeline[it].key },
        )
        // Restoring the timeline order asks each occurrence where it plays, and the two A's stay apart.
        assertEquals(listOf(1, 2, 0), (0 until 3).map { shuffled.playPositionOf(it) })
        assertTrue(shuffled.playPositionOf(0) != shuffled.playPositionOf(2))
    }

    @Test
    fun nextAndPreviousFollowPlayOrderAndStopAtTheEnds() {
        val order = ShuffleOrder.ofPlayOrder(listOf(2, 0, 1))

        assertEquals(0, order.nextTimelineIndex(2))
        assertEquals(1, order.nextTimelineIndex(0))
        assertEquals(ShuffleOrder.INDEX_UNSET, order.nextTimelineIndex(1))
        assertEquals(0, order.previousTimelineIndex(1))
        assertEquals(2, order.previousTimelineIndex(0))
        assertEquals(ShuffleOrder.INDEX_UNSET, order.previousTimelineIndex(2))
        assertEquals(ShuffleOrder.INDEX_UNSET, order.nextTimelineIndex(7))
        assertEquals(2, order.firstTimelineIndex())
        assertEquals(1, order.lastTimelineIndex())
    }

    @Test
    fun playPositionAfterPassesManualSongsThatAlreadyPlayNext() {
        val order = ShuffleOrder.ofPlayOrder(listOf(0, 1, 2, 3))

        assertEquals(2, order.playPositionAfter(1))
        assertEquals(4, order.playPositionAfter(1, skipUpcoming = 2))
        assertEquals(4, order.playPositionAfter(3, skipUpcoming = 5))
        assertEquals(2, order.playPositionAfter(1, skipUpcoming = -3))
    }

    @Test
    fun cloneAndInsertShiftsLaterTimelineEntriesAndPlacesTheNewOnesAtThePlayPosition() {
        val order = ShuffleOrder.ofPlayOrder(listOf(2, 0, 1))

        val inserted = order.cloneAndInsert(insertionIndex = 1, insertionCount = 2, playPosition = 1)

        assertEquals(listOf(4, 1, 2, 0, 3), inserted.playOrder())
        assertEquals(1, inserted.playPositionOf(1))
        assertEquals(2, inserted.playPositionOf(2))
        assertBijective(inserted)
        assertEquals(listOf(2, 0, 1), order.playOrder())
    }

    @Test
    fun cloneAndInsertAtTheEndAppendsInPlayOrder() {
        val order = ShuffleOrder.ofPlayOrder(listOf(1, 0))

        val appended = order.cloneAndInsert(insertionIndex = 2, insertionCount = 2, playPosition = order.size)

        assertEquals(listOf(1, 0, 2, 3), appended.playOrder())
        assertBijective(appended)
    }

    @Test
    fun cloneAndInsertRejectsOutOfRangeArguments() {
        val order = ShuffleOrder.ofPlayOrder(listOf(0, 1, 2))

        assertFailsWith<IllegalArgumentException> { order.cloneAndInsert(4, 1, 0) }
        assertFailsWith<IllegalArgumentException> { order.cloneAndInsert(0, 1, 4) }
        assertFailsWith<IllegalArgumentException> { order.cloneAndInsert(0, -1, 0) }
    }

    @Test
    fun cloneAndRemoveDropsTheRangeAndRenumbersSurvivors() {
        val order = ShuffleOrder.ofPlayOrder(listOf(2, 0, 1))

        assertEquals(listOf(1, 0), order.cloneAndRemove(fromIndex = 0, count = 1).playOrder())
        assertEquals(listOf(0), order.cloneAndRemove(fromIndex = 1, count = 2).playOrder())
        assertEquals(listOf(2, 0, 1), order.cloneAndRemove(fromIndex = 3, count = 0).playOrder())
        assertEquals(listOf(2, 0, 1), order.playOrder())
    }

    @Test
    fun cloneAndRemoveRejectsARangeOutsideTheOrder() {
        val order = ShuffleOrder.ofPlayOrder(listOf(2, 0, 1))

        assertFailsWith<IllegalArgumentException> { order.cloneAndRemove(fromIndex = 2, count = 2) }
        assertFailsWith<IllegalArgumentException> { order.cloneAndRemove(fromIndex = -1, count = 1) }
    }

    @Test
    fun removingEverythingLeavesAnEmptyOrderThatStaysSafe() {
        val cleared = ShuffleOrder.ofPlayOrder(listOf(1, 0)).cloneAndRemove(0, 2)

        assertEquals(0, cleared.size)
        assertEquals(ShuffleOrder.INDEX_UNSET, cleared.firstTimelineIndex())
        assertEquals(ShuffleOrder.empty(), cleared)
    }

    @Test
    fun anEmptyOrderIsSafeAtEveryEntryPoint() {
        val empty = ShuffleOrder.empty()

        assertEquals(0, empty.size)
        assertEquals(emptyList(), empty.playOrder())
        assertEquals(ShuffleOrder.INDEX_UNSET, empty.firstTimelineIndex())
        assertEquals(ShuffleOrder.INDEX_UNSET, empty.lastTimelineIndex())
        assertEquals(ShuffleOrder.INDEX_UNSET, empty.nextTimelineIndex(0))
        assertEquals(ShuffleOrder.INDEX_UNSET, empty.previousTimelineIndex(0))
        assertEquals(empty, empty.cloneAndClear())
        assertFailsWith<IllegalArgumentException> { empty.timelineIndexAt(0) }
        assertFailsWith<IllegalArgumentException> { empty.playPositionOf(0) }
    }

    @Test
    fun ofPlayOrderRefusesAnythingThatIsNotAPermutation() {
        assertFailsWith<IllegalArgumentException> { ShuffleOrder.ofPlayOrder(listOf(0, 0, 1)) }
        assertFailsWith<IllegalArgumentException> { ShuffleOrder.ofPlayOrder(listOf(0, 3)) }
        assertFailsWith<IllegalArgumentException> { ShuffleOrder.ofPlayOrder(listOf(-1, 0)) }
    }

    @Test
    fun shuffledIsAPermutationForEverySizeAndKeepsTheStartSongFirst() {
        for (size in 0..40) {
            assertBijective(ShuffleOrder.shuffled(size, Random(size)))
        }

        val started = ShuffleOrder.shuffled(10, Random(7), startIndex = 4)

        assertEquals(4, started.firstTimelineIndex())
        assertBijective(started)
    }

    @Test
    fun shuffledWithTheSameSeedIsDeterministicAndStartIndexIsValidated() {
        assertEquals(ShuffleOrder.shuffled(12, Random(42)), ShuffleOrder.shuffled(12, Random(42)))
        assertFailsWith<IllegalArgumentException> { ShuffleOrder.shuffled(3, Random(1), startIndex = 3) }
        assertFailsWith<IllegalArgumentException> { ShuffleOrder.shuffled(-1) }
    }

    @Test
    fun equalPlayOrdersAreEqualAndDifferentOnesAreNot() {
        val a = ShuffleOrder.ofPlayOrder(listOf(1, 0))
        val b = ShuffleOrder.ofPlayOrder(listOf(1, 0))

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertFalse(a == ShuffleOrder.ofPlayOrder(listOf(0, 1)))
    }

    @Test
    fun appendingFiniteSongsKeepsTheExistingPlayOrderAndShufflesOnlyTheNewOnes() {
        val previous = listOf(2, 0, 1)

        val appended = appendFiniteShuffleOrder(previous, oldCount = 3, newCount = 6, random = Random(1))

        assertEquals(previous, appended.take(3))
        assertEquals(listOf(3, 4, 5), appended.drop(3).sorted())
        assertEquals(previous, appendFiniteShuffleOrder(previous, oldCount = 3, newCount = 3))
    }
}
