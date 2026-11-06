package moe.rukamori.archivetune.shared.data.provider

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PoolAuthBackoffTest {
    @Test fun emptyKeyHashStillCountsAsARealRefusalIdentity() {
        val backoff = PoolAuthBackoff()
        backoff.onAuthRejected(nowMs = 1_000L, keyHash = 0)
        // The empty key hashes to 0: a same-key fetch stays blocked…
        assertTrue(backoff.blocked(2_000L))
        assertFalse(backoff.clearsFor(0), "the refused empty key does not clear its own backoff")
        // …but a genuinely different key must clear it — 0 is a real identity, not "no key".
        assertTrue(backoff.clearsFor(42))
    }

    @Test fun noActiveBackoffClearsForNothing() {
        val backoff = PoolAuthBackoff()
        assertFalse(backoff.clearsFor(0))
        assertFalse(backoff.clearsFor(42))
        backoff.onAuthRejected(1_000L, 7)
        backoff.reset()
        assertFalse(backoff.clearsFor(99), "a reset backoff refuses nothing, so nothing clears it")
    }

    @Test fun restoredBackoffKeepsTheEmptyKeyIdentity() {
        val backoff = PoolAuthBackoff()
        backoff.restore(failedAtMs = 1_000L, windowMs = 60_000L, failedKeyHash = 0)
        assertTrue(backoff.blocked(2_000L))
        assertFalse(backoff.clearsFor(0))
        assertTrue(backoff.clearsFor(42))
    }

    @Test fun anUnauthorizedKeyBuysHoursNotMinutes() {
        val backoff = PoolAuthBackoff()
        val now = 1_000_000L
        backoff.onAuthRejected(now, keyHash = 7)
        val window = backoff.windowMs
        // 401/403 are configuration: the first quiet period is hours, jittered ±20% around six.
        assertTrue(window >= 4L * 60L * 60L * 1000L, "window=$window")
        assertTrue(window <= 8L * 60L * 60L * 1000L, "window=$window")
        assertTrue(backoff.blocked(now + 4L * 60L * 60L * 1000L))
        assertFalse(backoff.blocked(now + 24L * 60L * 60L * 1000L))
    }

    @Test fun repeatedUnauthorizedRefusalsDoubleTowardADay() {
        val backoff = PoolAuthBackoff()
        backoff.onAuthRejected(0L, 7)
        val first = backoff.windowMs
        backoff.onAuthRejected(0L, 7)
        assertTrue(backoff.windowMs > first)
        repeat(6) { backoff.onAuthRejected(0L, 7) }
        assertTrue(backoff.windowMs <= 24L * 60L * 60L * 1000L)
    }

    @Test fun aThrottledFeedHonoursRetryAfterWithoutJitter() {
        val backoff = PoolAuthBackoff()
        backoff.onThrottled(50_000L, keyHash = 7, retryAfterMs = 120_000L)
        assertEquals(120_000L, backoff.windowMs, "the server's own hint is used verbatim")
        assertEquals(170_000L, backoff.blockedUntilMs())
        assertTrue(backoff.blocked(169_999L))
        assertFalse(backoff.blocked(170_000L))
    }

    @Test fun throttlingWithoutAHintStartsAtFiveMinutesAndDoubles() {
        val backoff = PoolAuthBackoff()
        backoff.onThrottled(0L, keyHash = 7)
        assertTrue(backoff.windowMs in 4L * 60L * 1000L..6L * 60L * 1000L, "window=${backoff.windowMs}")
        val first = backoff.windowMs
        backoff.onThrottled(0L, keyHash = 7)
        assertTrue(backoff.windowMs > first)
    }

    @Test fun onRefusalRoutesEachStatusToItsOwnWindow() {
        val unauthorized = PoolAuthBackoff()
        unauthorized.onRefusal(0L, keyHash = 1, status = 401)
        assertTrue(unauthorized.windowMs >= 4L * 60L * 60L * 1000L)
        val forbidden = PoolAuthBackoff()
        forbidden.onRefusal(0L, keyHash = 1, status = 403)
        assertTrue(forbidden.windowMs >= 4L * 60L * 60L * 1000L)
        val throttled = PoolAuthBackoff()
        throttled.onRefusal(0L, keyHash = 1, status = 429, retryAfterMs = 90_000L)
        assertEquals(90_000L, throttled.windowMs)
        val unavailable = PoolAuthBackoff()
        unavailable.onRefusal(0L, keyHash = 1, status = 503, retryAfterMs = 60_000L)
        assertEquals(60_000L, unavailable.windowMs)
    }
}
