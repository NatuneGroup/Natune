package moe.rukamori.archivetune.playback

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRetryBackoffTest {
    @Test
    fun `the first retry is immediate`() {
        val backoff = PlaybackRetryBackoff(random = Random(7))
        assertEquals(0L, backoff.delayMs(1))
        assertEquals(0L, backoff.delayMs(0))
    }

    @Test
    fun `later retries double the base delay inside the jitter band`() {
        val backoff =
            PlaybackRetryBackoff(
                baseDelayMs = 600L,
                maxDelayMs = 6_000L,
                jitterFraction = 0.25,
                random = Random(7),
            )
        repeat(64) {
            assertTrue(backoff.delayMs(2) in 450L..750L)
            assertTrue(backoff.delayMs(3) in 900L..1_500L)
            assertTrue(backoff.delayMs(4) in 1_800L..3_000L)
        }
    }

    @Test
    fun `the delay never exceeds the cap and never goes negative`() {
        val backoff =
            PlaybackRetryBackoff(
                baseDelayMs = 600L,
                maxDelayMs = 6_000L,
                jitterFraction = 0.25,
                random = Random(11),
            )
        repeat(200) { attempt ->
            val delay = backoff.delayMs(attempt + 1)
            assertTrue(delay in 0L..6_000L)
        }
        assertTrue(backoff.delayMs(30) <= 6_000L)
    }

    @Test
    fun `attempts are bounded at maxAttempts`() {
        val backoff = PlaybackRetryBackoff(maxAttempts = 3)
        assertTrue(backoff.allows(1))
        assertTrue(backoff.allows(3))
        assertFalse(backoff.allows(4))
        assertFalse(backoff.allows(0))
    }

    @Test
    fun `the jitter is deterministic for a seeded random`() {
        val first = PlaybackRetryBackoff(random = Random(42))
        val second = PlaybackRetryBackoff(random = Random(42))
        assertEquals(first.delayMs(2), second.delayMs(2))
        assertEquals(first.delayMs(5), second.delayMs(5))
    }

    @Test
    fun `invalid configuration is refused`() {
        listOf(
            { PlaybackRetryBackoff(maxAttempts = 0) },
            { PlaybackRetryBackoff(baseDelayMs = 0L) },
            { PlaybackRetryBackoff(baseDelayMs = 10L, maxDelayMs = 5L) },
            { PlaybackRetryBackoff(jitterFraction = 1.5) },
        ).forEach { factory ->
            var threw = false
            try {
                factory()
            } catch (_: IllegalArgumentException) {
                threw = true
            }
            assertTrue(threw)
        }
    }
}
