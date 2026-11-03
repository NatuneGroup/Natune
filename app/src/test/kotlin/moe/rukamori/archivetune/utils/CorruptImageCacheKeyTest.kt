package moe.rukamori.archivetune.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CorruptImageCacheKeyTest {
    @Test
    fun `an explicit disk cache key wins over the url`() {
        assertEquals("custom", effectiveImageCacheKey("custom", "https://i.ytimg.com/vi/a/hq.jpg"))
    }

    @Test
    fun `a thumbnail without an explicit key is cached under its url`() {
        assertEquals(
            "https://i.ytimg.com/vi/a/hq.jpg",
            effectiveImageCacheKey(null, "https://i.ytimg.com/vi/a/hq.jpg"),
        )
        assertEquals("HTTP://example.test/a.png", effectiveImageCacheKey(null, "HTTP://example.test/a.png"))
    }

    @Test
    fun `non network data has no disk cache entry to drop`() {
        assertNull(effectiveImageCacheKey(null, "tgart://123"))
        assertNull(effectiveImageCacheKey(null, "file:///sdcard/a.png"))
        assertNull(effectiveImageCacheKey(null, 42))
        assertNull(effectiveImageCacheKey(null, null))
    }
}
