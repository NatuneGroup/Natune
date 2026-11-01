package moe.rukamori.archivetune.shared.data.provider

import kotlin.test.Test
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
}
