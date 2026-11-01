package moe.rukamori.archivetune.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthScopedCacheValueTest {
    private fun entry(
        low: Boolean = false,
        itag: Int? = null,
    ) = AuthScopedCacheValue(
        url = "https://example.test/stream",
        expiresAtMs = Long.MAX_VALUE,
        authFingerprint = "fp",
        low = low,
        itag = itag,
    )

    @Test
    fun `a rendition mismatch rejects the cached url`() {
        // The byte cache keys by the resolved itag, so a cached URL whose stored rendition
        // class differs from the request's must be rejected and re-resolved.
        assertFalse(entry(low = false, itag = 140).matchesRendition(requestedLow = true))
        assertFalse(entry(low = true, itag = 249).matchesRendition(requestedLow = false))
        assertTrue(entry(low = true, itag = 249).matchesRendition(requestedLow = true))
        assertTrue(entry(low = false, itag = 140).matchesRendition(requestedLow = false))
    }

    @Test
    fun `a fallback that returns a different itag gets a different key`() {
        // YTPlayerUtils may fall back to another format; the byte cache must never let the two
        // renditions share a key.
        assertEquals("song#i140", entry(itag = 140).playbackCacheKey("song"))
        assertEquals("song#i249", entry(itag = 249).playbackCacheKey("song"))
        assertNotEquals(
            entry(itag = 140).playbackCacheKey("song"),
            entry(itag = 249).playbackCacheKey("song"),
        )
    }

    @Test
    fun `an untagged legacy entry keeps the bare cache key`() {
        // Entries without an itag can only ever read and write under the legacy bare key, so
        // partial spans of a different rendition are never stitched in.
        assertEquals("song", entry().playbackCacheKey("song"))
        assertEquals("song", entry(low = true).playbackCacheKey("song"))
    }

    @Test
    fun `rendition tagging does not weaken url validity`() {
        assertTrue(entry(low = true, itag = 249).isValidFor(authFingerprint = "fp"))
        assertFalse(entry(low = true, itag = 249).isValidFor(authFingerprint = "other"))
        assertFalse(entry().copy(expiresAtMs = 0L).isValidFor(authFingerprint = "fp"))
    }
}
