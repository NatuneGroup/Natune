package moe.rukamori.archivetune.playback

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutoBrowseChildrenCacheTest {
    @Test
    fun concurrentLoadsOfOneFolderShareASingleLoad() =
        runTest {
            val cache = AutoBrowseChildrenCache<Int>()
            val gate = CompletableDeferred<Unit>()
            var loads = 0
            val load: suspend () -> List<Int> = {
                loads++
                gate.await()
                listOf(loads)
            }

            val first = async { cache.children("home_quick_picks", load) }
            val second = async { cache.children("home_quick_picks", load) }
            val third = async { cache.children("home_quick_picks", load) }
            runCurrent()

            assertEquals(1, loads)
            assertFalse(first.isCompleted)
            assertFalse(second.isCompleted)
            assertFalse(third.isCompleted)

            gate.complete(Unit)
            runCurrent()

            assertEquals(listOf(1), first.await())
            assertEquals(listOf(1), second.await())
            assertEquals(listOf(1), third.await())
            assertEquals(1, loads)
        }

    @Test
    fun aPageAfterExplicitInvalidationAwaitsTheFreshSnapshot() =
        runTest {
            val cache = AutoBrowseChildrenCache<Int>()
            val gate = CompletableDeferred<Unit>()
            var loads = 0
            val load: suspend () -> List<Int> = {
                loads++
                if (loads == 1) listOf(1) else {
                    gate.await()
                    listOf(2)
                }
            }
            cache.children("liked", load) // cached snapshot = [1]

            cache.clear()
            val reopen = async { cache.children("liked", load) }
            val later = async { cache.children("liked", load) }
            runCurrent()
            assertFalse(later.isCompleted) // must not serve the stale [1]

            gate.complete(Unit)
            runCurrent()
            assertEquals(listOf(2), reopen.await())
            assertEquals(listOf(2), later.await())
            assertEquals(2, loads)
        }

    @Test
    fun aFailedSharedLoadThrowsForAwaitersAndCanBeRetried() =
        runTest {
            val cache = AutoBrowseChildrenCache<Int>()
            val gate = CompletableDeferred<Unit>()
            var loads = 0
            val load: suspend () -> List<Int> = {
                loads++
                if (loads == 1) {
                    gate.await()
                    throw IllegalStateException("network down")
                }
                listOf(loads)
            }

            val first = async { runCatching { cache.children("album/x", load) } }
            val second = async { runCatching { cache.children("album/x", load) } }
            runCurrent()
            assertEquals(1, loads)
            gate.complete(Unit)
            runCurrent()

            assertEquals(true, first.await().exceptionOrNull() is IllegalStateException)
            assertEquals(true, second.await().exceptionOrNull() is IllegalStateException)
            assertEquals(listOf(2), cache.children("album/x", load))
            assertEquals(2, loads)
        }

    @Test
    fun laterPagesSliceTheSnapshotTakenForPageZero() =
        runTest {
            val cache = AutoBrowseChildrenCache<Int>()
            var loads = 0
            val load: suspend () -> List<Int> = { loads++; List(10) { it + loads * 100 } }

            val first = cache.children("home_quick_picks", load)
            val second = cache.children("home_quick_picks", load)

            assertEquals(1, loads)
            assertEquals(first, second)
            assertEquals(listOf(105, 106, 107, 108, 109), second.autoBrowsePage(1, 5))
        }

    @Test
    fun repeatedPageZeroRequestsKeepTheSameSnapshot() =
        runTest {
            val cache = AutoBrowseChildrenCache<Int>()
            var loads = 0
            val load: suspend () -> List<Int> = { loads++; listOf(loads) }

            cache.children("liked", load)
            val reopened = cache.children("liked", load)

            assertEquals(1, loads)
            assertEquals(listOf(1), reopened)
        }

    @Test
    fun libraryRefreshAllowsPageZeroToSeeFreshContent() =
        runTest {
            val cache = AutoBrowseChildrenCache<Int>()
            var loads = 0
            val load: suspend () -> List<Int> = { loads++; listOf(loads) }

            cache.children("liked", load)
            cache.clear()

            assertEquals(listOf(2), cache.children("liked", load))
            assertEquals(2, loads)
        }

    @Test
    fun loadedMutableListsCannotChangeAnOpenFolder() =
        runTest {
            val cache = AutoBrowseChildrenCache<Int>()
            val source = mutableListOf(1, 2, 3)
            cache.children("playlist/x") { source }
            source.reverse()

            assertEquals(listOf(1, 2, 3), cache.children("playlist/x") { source })
        }

    @Test
    fun aLaterPageWithoutSnapshotLoadsInsteadOfReturningNothing() =
        runTest {
            val cache = AutoBrowseChildrenCache<Int>()
            assertEquals(listOf(1, 2), cache.children("album/x") { listOf(1, 2) })
        }

    @Test
    fun clearInvalidatesALoadAlreadyInFlight() =
        runTest {
            val cache = AutoBrowseChildrenCache<Int>()
            val gate = CompletableDeferred<Unit>()
            var loads = 0
            val load: suspend () -> List<Int> = {
                loads++
                if (loads == 1) gate.await() // the slow load is started before clear()
                listOf(loads)
            }

            val stale = async { cache.children("home_quick_picks", load) }
            runCurrent()
            assertEquals(1, loads)
            cache.clear()
            gate.complete(Unit)
            runCurrent()

            assertEquals(listOf(1), stale.await()) // its own caller still gets a result
            // ...but it did not repopulate the cache: a later page must reload under the new
            // generation instead of serving the pre-clear snapshot.
            assertEquals(listOf(2), cache.children("home_quick_picks", load))
            assertEquals(2, loads)
        }

    @Test
    fun targetedInvalidationCannotRepopulateFromAnOlderLoad() = runTest {
        val cache = AutoBrowseChildrenCache<Int>()
        val staleGate = CompletableDeferred<Unit>()
        val unaffectedGate = CompletableDeferred<Unit>()
        val stale = async { cache.children("liked") { staleGate.await(); listOf(1) } }
        val unaffected = async { cache.children("playlist/other") { unaffectedGate.await(); listOf(7) } }
        runCurrent()

        assertEquals(setOf("liked"), cache.invalidate { it == "liked" })
        assertEquals(listOf(2), cache.children("liked") { listOf(2) })
        staleGate.complete(Unit)
        unaffectedGate.complete(Unit)
        assertEquals(listOf(1), stale.await())
        assertEquals(listOf(7), unaffected.await())
        assertEquals(listOf(2), cache.children("liked") { error("stale load overwrote the fresh snapshot") })
        assertEquals(listOf(7), cache.children("playlist/other") { error("unrelated folder was invalidated") })
    }

    @Test
    fun clearAndEvictionDropSnapshots() =
        runTest {
            val cache = AutoBrowseChildrenCache<Int>(maxEntries = 1)
            var loads = 0
            val load: suspend () -> List<Int> = { loads++; listOf(loads) }
            cache.children("a", load)
            cache.children("b", load)
            cache.children("a", load) // "a" was evicted by "b"
            assertEquals(3, loads)
            cache.clear()
            cache.children("a", load)
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
