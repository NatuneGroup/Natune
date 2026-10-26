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
import kotlin.test.assertNull
import kotlin.test.fail

/**
 * Characterizes where manual "play next" and "add to queue" songs land. The first cases mirror the
 * Android QueueInsertionPolicy tests, so the shared rule and the shipped rule place the same songs.
 */
class UserQueuePolicyTest {
    private val playNext = UserQueuedKind.PLAY_NEXT
    private val addToQueue = UserQueuedKind.ADD_TO_QUEUE

    private fun place(
        kind: UserQueuedKind,
        upcoming: List<UserQueuedKind> = emptyList(),
        itemCount: Int = 5,
        currentIndex: Int? = 1,
        shuffle: Boolean = false,
        keepInOrder: Boolean = true,
    ): UserQueuePlacement? =
        UserQueuePolicy.placement(
            kind = kind,
            count = 1,
            keepInOrder = keepInOrder,
            itemCount = itemCount,
            currentIndex = currentIndex,
            shuffleEnabled = shuffle,
            upcoming = { upcoming },
        )

    @Test
    fun playNextWithoutHandQueuedSongsGoesDirectlyAfterCurrent() {
        assertEquals(UserQueuePlacement(2, UserQueuePlayOrder.Timeline), place(playNext))
    }

    @Test
    fun playNextGoesBehindEarlierPlayNextSongs() {
        assertEquals(
            UserQueuePlacement(4, UserQueuePlayOrder.Timeline),
            place(playNext, upcoming = listOf(playNext, playNext, addToQueue)),
        )
    }

    @Test
    fun playNextStaysAheadOfAddToQueueSongs() {
        assertEquals(
            UserQueuePlacement(2, UserQueuePlayOrder.Timeline),
            place(playNext, upcoming = listOf(addToQueue, addToQueue)),
        )
    }

    @Test
    fun addToQueueGoesBehindTheWholeHandQueuedBlock() {
        assertEquals(
            UserQueuePlacement(4, UserQueuePlayOrder.Timeline),
            place(addToQueue, upcoming = listOf(playNext, addToQueue)),
        )
    }

    @Test
    fun successiveAddToQueueSongsKeepSubmissionOrder() {
        // Each add lands after the block of earlier adds, so the timeline positions run 1, 2, 3.
        val placements = (0 until 3).map { added ->
            val upcoming = List(added) { addToQueue }
            place(addToQueue, upcoming = upcoming, itemCount = 3 + added, currentIndex = 0)?.timelineIndex
        }
        assertEquals(listOf(1, 2, 3), placements)
    }

    @Test
    fun timelineIndexNeverPassesTheEndOfTheQueue() {
        assertEquals(
            UserQueuePlacement(3, UserQueuePlayOrder.Timeline),
            place(playNext, upcoming = listOf(playNext, playNext, addToQueue), itemCount = 3),
        )
    }

    @Test
    fun withoutKeepInOrderAddToQueueAppendsToTheTimelineEnd() {
        val placement =
            UserQueuePolicy.placement(
                kind = addToQueue,
                count = 2,
                keepInOrder = false,
                itemCount = 5,
                currentIndex = 1,
                shuffleEnabled = false,
                upcoming = { fail("upcoming must not be read when it cannot change the answer") },
            )
        assertEquals(UserQueuePlacement(5, UserQueuePlayOrder.Timeline), placement)
    }

    @Test
    fun withoutKeepInOrderPlayNextStillFollowsTheCurrentSong() {
        val placement =
            UserQueuePolicy.placement(
                kind = playNext,
                count = 1,
                keepInOrder = false,
                itemCount = 5,
                currentIndex = 1,
                shuffleEnabled = false,
                upcoming = { fail("upcoming must not be read without keepInOrder") },
            )
        assertEquals(UserQueuePlacement(2, UserQueuePlayOrder.Timeline), placement)
    }

    @Test
    fun shuffledPlayNextLandsAfterCurrentInPlayOrderBehindEarlierManualSongs() {
        assertEquals(
            UserQueuePlacement(3, UserQueuePlayOrder.AfterCurrent(1)),
            place(playNext, upcoming = listOf(playNext), itemCount = 6, currentIndex = 2, shuffle = true),
        )
    }

    @Test
    fun shuffledAddToQueueAppendsToTheTimelineAndPlaysAfterTheHandQueuedBlock() {
        assertEquals(
            UserQueuePlacement(6, UserQueuePlayOrder.AfterCurrent(2)),
            place(
                addToQueue,
                upcoming = listOf(playNext, addToQueue),
                itemCount = 6,
                currentIndex = 2,
                shuffle = true,
            ),
        )
    }

    @Test
    fun shuffledAddToQueueWithoutKeepInOrderIsPlacedAtRandom() {
        assertEquals(
            UserQueuePlacement(6, UserQueuePlayOrder.RandomPosition),
            UserQueuePolicy.placement(
                kind = addToQueue,
                count = 1,
                keepInOrder = false,
                itemCount = 6,
                currentIndex = 2,
                shuffleEnabled = true,
                upcoming = { fail("upcoming must not be read without keepInOrder") },
            ),
        )
    }

    @Test
    fun shuffleWithoutACurrentSongPlacesTheSongsAtRandom() {
        assertEquals(
            UserQueuePlacement(5, UserQueuePlayOrder.RandomPosition),
            place(playNext, itemCount = 5, currentIndex = null, shuffle = true),
        )
    }

    @Test
    fun emptyQueueAppendsToTheEnd() {
        assertEquals(UserQueuePlacement(0, UserQueuePlayOrder.Timeline), place(playNext, itemCount = 0, currentIndex = 0))
    }

    @Test
    fun upcomingIsNotReadWhenNoCurrentSongExists() {
        val placement =
            UserQueuePolicy.placement(
                kind = playNext,
                count = 1,
                keepInOrder = true,
                itemCount = 5,
                currentIndex = null,
                shuffleEnabled = false,
                upcoming = { fail("upcoming must not be read without a current song") },
            )
        assertEquals(UserQueuePlacement(5, UserQueuePlayOrder.Timeline), placement)
    }

    @Test
    fun nothingToInsertReturnsNull() {
        for (count in listOf(0, -1)) {
            assertNull(
                UserQueuePolicy.placement(
                    kind = playNext,
                    count = count,
                    keepInOrder = true,
                    itemCount = 5,
                    currentIndex = 1,
                    shuffleEnabled = false,
                    upcoming = { fail("upcoming must not be read for an empty insertion") },
                ),
                "count=$count",
            )
        }
    }

    @Test
    fun skipCountCountsOnlyTheHandQueuedBlock() {
        assertEquals(0, UserQueuePolicy.skipCount(emptyList(), playNext))
        assertEquals(2, UserQueuePolicy.skipCount(listOf(playNext, playNext, addToQueue), playNext))
        assertEquals(0, UserQueuePolicy.skipCount(listOf(addToQueue, playNext), playNext))
        assertEquals(2, UserQueuePolicy.skipCount(listOf(playNext, addToQueue), addToQueue))
        assertEquals(0, UserQueuePolicy.skipCount(emptyList(), addToQueue))
    }
}
