package moe.rukamori.archivetune.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeOAuthRefreshPolicyTest {
    private val now = 1_000_000_000L

    @Test
    fun aTokenInsideTheSkewWindowIsRefreshed() {
        assertTrue(YouTubeOAuthRefreshPolicy.needsRefresh(expiresAtMs = now + 60_000L, nowMs = now))
        assertTrue(YouTubeOAuthRefreshPolicy.needsRefresh(expiresAtMs = 0L, nowMs = now))
        assertFalse(YouTubeOAuthRefreshPolicy.needsRefresh(expiresAtMs = now + 60 * 60_000L, nowMs = now))
    }

    @Test
    fun theRefreshIsScheduledAheadOfExpiryAndNeverInThePast() {
        val expiresAt = now + 60 * 60_000L
        assertEquals(expiresAt - YouTubeOAuthRefreshPolicy.REFRESH_SKEW_MS - now, YouTubeOAuthRefreshPolicy.delayUntilRefreshMs(expiresAt, now))
        assertEquals(0L, YouTubeOAuthRefreshPolicy.delayUntilRefreshMs(now - 1L, now))
    }

    @Test
    fun onlyARevokedGrantEndsTheSignIn() {
        assertTrue(YouTubeOAuthRefreshPolicy.isGrantRevoked("invalid_grant"))
        assertFalse(YouTubeOAuthRefreshPolicy.isGrantRevoked(null))
        assertFalse(YouTubeOAuthRefreshPolicy.isGrantRevoked(""))
        assertFalse(YouTubeOAuthRefreshPolicy.isGrantRevoked("internal_failure"))
    }
}
