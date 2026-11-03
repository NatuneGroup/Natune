package moe.rukamori.archivetune.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import moe.rukamori.archivetune.canvas.CanvasSource

class DesktopCanvasPrefetchCacheTest {
    private fun target(id: String, provider: CanvasSource = CanvasSource.TIDAL) =
        DesktopCanvasTarget(id, "https://video.example/$id.mp4", image = false, provider = provider)

    private val probe = listOf<Any?>(true, "ALL", "catalog-account")

    @Test
    fun aHitIsServedOnceAndTheOldestEntryLeavesAtCapacity() = runTest {
        val cache = DesktopCanvasPrefetchCache(capacity = 2)
        cache.retainProbe(probe)
        listOf("a", "b", "c").forEach { assertTrue(cache.put(target(it), probe)) }
        assertEquals(2, cache.size())
        assertNull(cache.take("a", probe) { true })
        assertEquals(target("b"), cache.take("b", probe) { true })
        assertNull(cache.take("b", probe) { true })
    }

    @Test
    fun aPreferenceOrAccountChangeDropsEveryEntry() = runTest {
        val cache = DesktopCanvasPrefetchCache(capacity = 8)
        cache.retainProbe(probe)
        assertTrue(cache.put(target("a"), probe))
        assertTrue(cache.put(target("b"), probe))
        val signedOut = listOf<Any?>(true, "ALL", null)
        cache.retainProbe(signedOut)
        assertEquals(0, cache.size())
        assertNull(cache.take("a", signedOut) { true })
        // A lookahead that began under the old account cannot land after the change.
        assertFalse(cache.put(target("c"), probe))
        assertNull(cache.take("c", signedOut) { true })
    }

    @Test
    fun aHitWhoseProviderIsNoLongerAllowedIsRefused() = runTest {
        val cache = DesktopCanvasPrefetchCache(capacity = 8)
        cache.retainProbe(probe)
        assertTrue(cache.put(target("a", CanvasSource.SPOTIFY), probe))
        assertTrue(cache.put(target("b", CanvasSource.TIDAL), probe))
        assertNull(cache.take("a", probe) { it != CanvasSource.SPOTIFY })
        assertEquals(target("b"), cache.take("b", probe) { it != CanvasSource.SPOTIFY })
    }

    @Test
    fun aCancelledLookaheadLeavesNoEntry() = runTest {
        val cache = DesktopCanvasPrefetchCache(capacity = 8)
        cache.retainProbe(probe)
        var stored: Boolean? = null
        launch {
            // The resolve returned just as collectLatest cancelled this generation; the store must refuse it.
            coroutineContext.job.cancel()
            stored = cache.put(target("a"), probe)
        }.join()
        assertTrue(stored != true)
        assertEquals(0, cache.size())
    }

    @Test
    fun concurrentWritersAndReadersKeepTheBound() = runTest {
        val cache = DesktopCanvasPrefetchCache(capacity = 8)
        cache.retainProbe(probe)
        coroutineScope {
            List(64) { index ->
                async(Dispatchers.Default) {
                    repeat(50) { round ->
                        val id = "o${(index * 50 + round) % 20}"
                        cache.put(target(id), probe)
                        cache.contains(id)
                        cache.take("o${round % 20}", probe) { true }
                    }
                }
            }.awaitAll()
        }
        assertTrue(cache.size() <= 8)
    }
}
