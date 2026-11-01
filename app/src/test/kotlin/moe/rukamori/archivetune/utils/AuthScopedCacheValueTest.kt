package moe.rukamori.archivetune.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthScopedCacheValueTest {
    private fun entry(low: Boolean) =
        AuthScopedCacheValue(
            url = "https://example.test/stream",
            expiresAtMs = Long.MAX_VALUE,
            authFingerprint = "fp",
            low = low,
        )

    @Test
    fun `a rendition mismatch rejects the cached url`() {
        // The resolved spec keys bytes by rendition ("<mediaId>#low"), so a cached URL whose
        // stored rendition differs from the request's must be rejected and re-resolved.
        assertFalse(entry(low = false).matchesRendition(requestedLow = true))
        assertFalse(entry(low = true).matchesRendition(requestedLow = false))
        assertTrue(entry(low = true).matchesRendition(requestedLow = true))
        assertTrue(entry(low = false).matchesRendition(requestedLow = false))
    }

    @Test
    fun `rendition tagging does not weaken url validity`() {
        assertTrue(entry(low = true).isValidFor(authFingerprint = "fp"))
        assertFalse(entry(low = true).isValidFor(authFingerprint = "other"))
        assertFalse(entry(low = false).copy(expiresAtMs = 0L).isValidFor(authFingerprint = "fp"))
    }
}
