package moe.rukamori.archivetune.shared.domain.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamUrlCacheTest {
    @Test
    fun invalidationOfAnAbsentEntryAlsoRetiresInflightWriters() {
        val cache = StreamUrlCache<String, String>()
        val stale = cache.generation
        cache.remove("song")
        assertFalse(cache.putIfCurrent("song", "rejected-url", stale))
        assertNull(cache["song"])
        assertTrue(cache.putIfCurrent("song", "fresh-url", cache.generation))
    }

    @Test
    fun scopedInvalidationRemovesEveryQualityButKeepsOtherEntries() {
        val cache = StreamUrlCache<String, String>()
        val generation = cache.generation
        cache.putIfCurrent("song:flac", "flac-url", generation)
        cache.putIfCurrent("song:aac", "aac-url", generation)
        cache.putIfCurrent("other:flac", "other-url", generation)
        cache.invalidateWhere { it.startsWith("song:") }
        assertEquals(setOf("other:flac"), cache.keys)
        assertFalse(cache.putIfCurrent("song:flac", "late-url", generation))
    }

    @Test
    fun expirationEvictionDoesNotCancelAnotherSongOrRemoveANewerValue() {
        val cache = StreamUrlCache<String, String>()
        val generation = cache.generation
        cache.putIfCurrent("song", "old-url", generation)
        cache.putIfCurrent("song", "new-url", generation)
        assertFalse(cache.discard("song", "old-url"))
        assertEquals("new-url", cache["song"])
        assertTrue(cache.discard("song", "new-url"))
        assertTrue(cache.putIfCurrent("other", "other-url", generation))
        cache.retainKeys { true }
        assertTrue(cache.putIfCurrent("song", "fresh-url", generation))
    }

    @Test
    fun boundedCacheEvictsOldestWriteAndClearRejectsLateResults() {
        val cache = StreamUrlCache<String, Int>(capacity = 2)
        val generation = cache.generation
        cache.putIfCurrent("one", 1, generation)
        cache.putIfCurrent("two", 2, generation)
        cache.putIfCurrent("one", 3, generation)
        cache.putIfCurrent("three", 4, generation)
        assertEquals(setOf("one", "three"), cache.keys)
        assertEquals(2, cache.size)
        cache.clear()
        assertFalse(cache.putIfCurrent("one", 5, generation))
        assertEquals(0, cache.size)
    }
}
