package moe.rukamori.archivetune.playback.preload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NextTrackPrefetchPolicyTest {
    @Test
    fun onlyTheLastTenSecondsOfAKnownDurationArmsThePrefetch() {
        assertFalse(NextTrackPrefetchPolicy.shouldPrefetch(180_000L, 169_999L))
        assertTrue(NextTrackPrefetchPolicy.shouldPrefetch(180_000L, 170_000L))
        assertTrue(NextTrackPrefetchPolicy.shouldPrefetch(180_000L, 180_000L))
    }

    @Test
    fun unknownOrUnsetDurationsNeverTrigger() {
        assertFalse(NextTrackPrefetchPolicy.shouldPrefetch(-1L, 170_000L))
        assertFalse(NextTrackPrefetchPolicy.shouldPrefetch(0L, 0L))
        assertFalse(NextTrackPrefetchPolicy.shouldPrefetch(180_000L, -1L))
    }

    @Test
    fun theWatcherPollsFinelyNearTheEndAndCoarselyOtherwise() {
        assertEquals(2_000L, NextTrackPrefetchPolicy.pollDelayMs(-1L, 0L))
        assertEquals(2_000L, NextTrackPrefetchPolicy.pollDelayMs(180_000L, -1L))
        assertEquals(2_000L, NextTrackPrefetchPolicy.pollDelayMs(180_000L, 0L))
        assertEquals(2_000L, NextTrackPrefetchPolicy.pollDelayMs(180_000L, 149_999L))
        assertEquals(500L, NextTrackPrefetchPolicy.pollDelayMs(180_000L, 150_000L))
        assertEquals(500L, NextTrackPrefetchPolicy.pollDelayMs(180_000L, 179_999L))
    }
}
