package moe.rukamori.archivetune.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BoundedArtworkMemoTest {
    private data class ArtworkKey(
        val url: String,
        val fallbackArgb: Int,
    )

    @Test
    fun storesAndReturnsTheValueForItsOwnKey() {
        val memo = BoundedArtworkMemo<ArtworkKey, List<Int>>(maxEntries = 4)
        val key = ArtworkKey("https://art/1.jpg", fallbackArgb = 0xFF202020.toInt())

        assertNull(memo.get(key))
        memo.put(key, listOf(1, 2, 3))

        assertEquals(listOf(1, 2, 3), memo.get(key))
        assertNull(memo.get(key.copy(fallbackArgb = 0xFF000000.toInt())))
    }

    @Test
    fun evictsTheLeastRecentlyUsedEntryWhenFull() {
        val memo = BoundedArtworkMemo<String, Int>(maxEntries = 2)
        memo.put("first", 1)
        memo.put("second", 2)

        // Reading "first" must protect it: insertion order alone would have evicted it.
        assertEquals(1, memo.get("first"))
        memo.put("third", 3)

        assertEquals(1, memo.get("first"))
        assertEquals(3, memo.get("third"))
        assertNull(memo.get("second"))
    }

    @Test
    fun replacingAKeyKeepsTheNewValueWithoutGrowingTheMemo() {
        val memo = BoundedArtworkMemo<String, Int>(maxEntries = 1)
        memo.put("cover", 1)
        memo.put("cover", 2)

        assertEquals(2, memo.get("cover"))
        memo.put("other", 3)

        assertNull(memo.get("cover"))
        assertEquals(3, memo.get("other"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAZeroSizedMemo() {
        BoundedArtworkMemo<String, Int>(maxEntries = 0)
    }
}
