package moe.rukamori.archivetune.playback

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AutoBrowseChildrenCacheTest {
    @Test
    fun laterPagesSliceTheSnapshotTakenForPageZero() =
        runTest {
            val cache = AutoBrowseChildrenCache<Int>()
            var loads = 0
            val load: suspend () -> List<Int> = { loads++; List(10) { it + loads * 100 } }

            val first = cache.children("home_quick_picks", 0, load)
            val second = cache.children("home_quick_picks", 1, load)

            assertEquals(1, loads)
            assertEquals(first, second)
            assertEquals(listOf(105, 106, 107, 108, 109), second.autoBrowsePage(1, 5))
        }

    @Test
    fun pageZeroReloadsSoReopeningAFolderSeesFreshContent() =
        runTest {
            val cache = AutoBrowseChildrenCache<Int>()
            var loads = 0
            val load: suspend () -> List<Int> = { loads++; listOf(loads) }

            cache.children("liked", 0, load)
            val reopened = cache.children("liked", 0, load)

            assertEquals(2, loads)
            assertEquals(listOf(2), reopened)
        }

    @Test
    fun aLaterPageWithoutSnapshotLoadsInsteadOfReturningNothing() =
        runTest {
            val cache = AutoBrowseChildrenCache<Int>()
            assertEquals(listOf(1, 2), cache.children("album/x", 3) { listOf(1, 2) })
        }

    @Test
    fun clearAndEvictionDropSnapshots() =
        runTest {
            val cache = AutoBrowseChildrenCache<Int>(maxEntries = 1)
            var loads = 0
            val load: suspend () -> List<Int> = { loads++; listOf(loads) }
            cache.children("a", 0, load)
            cache.children("b", 0, load)
            cache.children("a", 1, load) // "a" was evicted by "b"
            assertEquals(3, loads)
            cache.clear()
            cache.children("a", 1, load)
            assertEquals(4, loads)
        }

    @Test
    fun pagingBounds() {
        val list = List(7) { it }
        assertEquals(list, list.autoBrowsePage(-1, 5))
        assertEquals(list, list.autoBrowsePage(0, 0))
        assertEquals(listOf(5, 6), list.autoBrowsePage(1, 5))
        assertEquals(emptyList<Int>(), list.autoBrowsePage(2, 5))
        assertEquals(emptyList<Int>(), list.autoBrowsePage(Int.MAX_VALUE, Int.MAX_VALUE))
    }
}
