package moe.rukamori.archivetune.shared.domain.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The micro-stall suppression Apple applies to its buffering broadcast, as a pure policy. */
class BufferingDebouncePolicyTest {
    private val policy = BufferingDebouncePolicy()

    @Test
    fun aStallShorterThanTheWindowNeverBecomesVisible() {
        val first = policy.onEvent(rawBuffering = true, alreadyPublished = false, nowMs = 1_000L)
        assertFalse(first.visibleBuffering)
        assertEquals(BufferingDebouncePolicy.DEBOUNCE_MS, first.retryAfterMs)

        // The stall resolves inside the window: recovery publishes immediately.
        val cleared = policy.onEvent(rawBuffering = false, alreadyPublished = false, nowMs = 1_100L)
        assertFalse(cleared.visibleBuffering)
        assertNull(cleared.retryAfterMs)
    }

    @Test
    fun aStallThatOutlivesTheWindowPublishesAsBuffering() {
        policy.onEvent(rawBuffering = true, alreadyPublished = false, nowMs = 1_000L)
        val stillStalled = policy.onEvent(rawBuffering = true, alreadyPublished = false, nowMs = 1_050L)
        assertFalse(stillStalled.visibleBuffering)
        assertEquals(100L, stillStalled.retryAfterMs)

        val flushed = policy.onEvent(rawBuffering = true, alreadyPublished = false, nowMs = 1_150L)
        assertTrue(flushed.visibleBuffering)
        assertNull(flushed.retryAfterMs)
    }

    @Test
    fun visibleStallsAndNonBufferingEventsPassThrough() {
        val ongoing = policy.onEvent(rawBuffering = true, alreadyPublished = true, nowMs = 5_000L)
        assertTrue(ongoing.visibleBuffering)
        assertNull(ongoing.retryAfterMs)

        val idle = policy.onEvent(rawBuffering = false, alreadyPublished = true, nowMs = 5_100L)
        assertFalse(idle.visibleBuffering)
    }

    @Test
    fun aNewStallAfterRecoveryStartsAFreshWindow() {
        policy.onEvent(rawBuffering = true, alreadyPublished = false, nowMs = 1_000L)
        policy.onEvent(rawBuffering = false, alreadyPublished = false, nowMs = 1_100L)

        val second = policy.onEvent(rawBuffering = true, alreadyPublished = false, nowMs = 1_200L)
        assertFalse(second.visibleBuffering)
        assertEquals(BufferingDebouncePolicy.DEBOUNCE_MS, second.retryAfterMs)
    }

    @Test
    fun resetDropsAHeldTransition() {
        policy.onEvent(rawBuffering = true, alreadyPublished = false, nowMs = 1_000L)
        policy.reset()

        val restarted = policy.onEvent(rawBuffering = true, alreadyPublished = false, nowMs = 1_120L)
        assertFalse(restarted.visibleBuffering)
        assertEquals(BufferingDebouncePolicy.DEBOUNCE_MS, restarted.retryAfterMs)
    }
}
