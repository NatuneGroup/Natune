package moe.rukamori.archivetune.shared.domain.queue

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InfiniteQueuePolicyTest {
    @Test fun seedAndContinuationThresholdsIncludeTheCurrentSong() {
        assertFalse(InfiniteQueuePolicy.shouldAppend(true, true, 4, false))
        assertTrue(InfiniteQueuePolicy.shouldAppend(true, true, 3, false))
        assertFalse(InfiniteQueuePolicy.shouldAppend(true, true, 6, true))
        assertTrue(InfiniteQueuePolicy.shouldAppend(true, true, 5, true))
        assertFalse(InfiniteQueuePolicy.shouldAppend(true, true, 0, true))
        assertFalse(InfiniteQueuePolicy.shouldAppend(true, true, -1, false))
    }

    @Test fun offAndRepeatNeverAppend() {
        assertFalse(InfiniteQueuePolicy.shouldAppend(false, true, 1, true))
        assertFalse(InfiniteQueuePolicy.shouldAppend(true, false, 1, false))
    }

    @Test fun recommendationsDeduplicateAgainstEveryQueuedTrackAndWithinThePage() {
        data class Track(val media: String, val occurrence: String)
        val page = listOf(Track("old", "other"), Track("new", "a"), Track("new", "b"), Track("last", "c"))
        assertEquals(listOf(page[1], page[3]), InfiniteQueuePolicy.fresh(page, listOf("old")) { it.media })
    }

    @Test fun concurrentRequestsAreRefusedAndTheOffSwitchInvalidatesPendingResults() {
        val gate = InfiniteQueueLoadGate()
        val ticket = assertNotNull(gate.begin(true, true, 1, false, 0))
        assertNull(gate.begin(true, true, 1, false, 0))
        gate.invalidate()
        assertFalse(gate.isCurrent(ticket))
        assertFalse(gate.finish(ticket, 10, 0))
        assertNull(gate.begin(false, true, 1, false, 0))
    }

    @Test fun failureAndEmptyPagesBackOffExponentiallyAndCapAtFiveMinutes() {
        val gate = InfiniteQueueLoadGate()
        var now = 0L
        for (delay in listOf(30_000L, 60_000L, 120_000L, 240_000L, 300_000L, 300_000L)) {
            val ticket = assertNotNull(gate.begin(true, true, 1, true, now))
            assertTrue(gate.finish(ticket, 0, now))
            assertNull(gate.begin(true, true, 1, true, now + delay - 1))
            now += delay
        }
        assertNotNull(gate.begin(true, true, 1, true, now))
    }

    @Test fun togglingCannotBypassBackoffButSuccessAndQueueReplacementResetIt() {
        val gate = InfiniteQueueLoadGate()
        val failed = assertNotNull(gate.begin(true, true, 1, false, 0))
        gate.finish(failed, 0, 0)
        gate.invalidate()
        assertNull(gate.begin(true, true, 1, false, 1))
        val successful = assertNotNull(gate.begin(true, true, 1, false, 30_000))
        gate.finish(successful, 1, 30_000)
        assertNotNull(gate.begin(true, true, 1, true, 30_000))
        gate.invalidate(newQueue = true)
        assertNotNull(gate.begin(true, true, 1, true, 30_000))
    }

    @Test fun anOldCompletionCannotFinishANewerRequest() {
        val gate = InfiniteQueueLoadGate()
        val old = assertNotNull(gate.begin(true, true, 1, false, 0))
        gate.invalidate()
        val newer = assertNotNull(gate.begin(true, true, 1, false, 0))
        assertFalse(gate.finish(old, 0, 0))
        assertTrue(gate.isCurrent(newer))
    }
}
