package moe.rukamori.archivetune.shared.data.listentogether

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The drift math every host applies, whatever codec the room speaks: server-stamped positions only
 * accumulate real elapsed time, and tolerances decide whether a seek is worth doing at all.
 */
class ListenTogetherSyncTest {

    @Test
    fun `a play position accumulates the time since the server saw it`() {
        val target = ListenTogetherSync.adjustedPlayPosition(
            positionMs = 60_000L,
            serverTimeMs = 1_000_000L,
            nowMs = 1_002_500L,
        )
        assertEquals(62_500L, target)
    }

    @Test
    fun `a missing server stamp leaves the position untouched`() {
        assertEquals(60_000L, ListenTogetherSync.adjustedPlayPosition(60_000L, null, 1_002_500L))
    }

    @Test
    fun `a server clock ahead of the device adds nothing`() {
        // Clock skew must never move a play backwards or freeze it early.
        assertEquals(60_000L, ListenTogetherSync.adjustedPlayPosition(60_000L, 2_000_000L, 1_002_500L))
    }

    @Test
    fun `a sync-state position only moves while the room is playing`() {
        val playing = ListenTogetherSync.adjustedSyncPosition(60_000L, isPlaying = true, lastUpdateMs = 1_000_000L, nowMs = 1_003_000L)
        assertEquals(63_000L, playing)
        val paused = ListenTogetherSync.adjustedSyncPosition(60_000L, isPlaying = false, lastUpdateMs = 1_000_000L, nowMs = 1_003_000L)
        assertEquals(60_000L, paused)
    }

    @Test
    fun `a buffered position only accumulates while the room is playing`() {
        val playing = ListenTogetherSync.adjustedPendingPosition(60_000L, playing = true, receivedAtMs = 1_000_000L, nowMs = 1_004_000L)
        assertEquals(64_000L, playing)
        val paused = ListenTogetherSync.adjustedPendingPosition(60_000L, playing = false, receivedAtMs = 1_000_000L, nowMs = 1_004_000L)
        assertEquals(60_000L, paused)
    }

    @Test
    fun `an unread position always counts as outside tolerance`() {
        assertTrue(ListenTogetherSync.outsideTolerance(null, 60_000L, ListenTogetherSync.POSITION_TOLERANCE_MS))
    }

    @Test
    fun `drift at and inside tolerance does not seek`() {
        assertFalse(ListenTogetherSync.outsideTolerance(61_500L, 60_000L, ListenTogetherSync.POSITION_TOLERANCE_MS))
        assertFalse(ListenTogetherSync.outsideTolerance(62_000L, 60_000L, ListenTogetherSync.POSITION_TOLERANCE_MS))
        assertTrue(ListenTogetherSync.outsideTolerance(62_001L, 60_000L, ListenTogetherSync.POSITION_TOLERANCE_MS))
    }

    @Test
    fun `the playing tolerance is the looser of the two baselines`() {
        assertEquals(
            ListenTogetherSync.PLAYBACK_POSITION_TOLERANCE_MS,
            ListenTogetherSync.toleranceFor(willPlay = true, isPlayingNow = true),
        )
        assertEquals(
            ListenTogetherSync.POSITION_TOLERANCE_MS,
            ListenTogetherSync.toleranceFor(willPlay = true, isPlayingNow = false),
        )
        assertEquals(
            ListenTogetherSync.POSITION_TOLERANCE_MS,
            ListenTogetherSync.toleranceFor(willPlay = false, isPlayingNow = true),
        )
    }

    @Test
    fun `the room tolerances match the Android baseline`() {
        // The manager's numbers are the contract every member's seeks are judged against.
        assertEquals(1_000L, ListenTogetherSync.SYNC_DEBOUNCE_THRESHOLD_MS)
        assertEquals(2_000L, ListenTogetherSync.POSITION_TOLERANCE_MS)
        assertEquals(3_000L, ListenTogetherSync.PLAYBACK_POSITION_TOLERANCE_MS)
        assertEquals(10_000L, ListenTogetherSync.HOST_HEARTBEAT_INTERVAL_MS)
        assertEquals(1_000L, ListenTogetherSync.SMART_RESYNC_DELAY_MS)
    }
}
