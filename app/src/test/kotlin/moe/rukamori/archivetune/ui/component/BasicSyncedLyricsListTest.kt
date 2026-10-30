package moe.rukamori.archivetune.ui.component

import org.junit.Assert.assertEquals
import org.junit.Test

class BasicSyncedLyricsListTest {
    private val starts = intArrayOf(1_000, 4_000, 4_000, 9_500)

    @Test
    fun beforeFirstLine_noLineIsActive() {
        assertEquals(-1, activeSyncedLineIndex(starts, 0))
        assertEquals(-1, activeSyncedLineIndex(starts, 999))
        assertEquals(-1, activeSyncedLineIndex(IntArray(0), 5_000))
    }

    @Test
    fun activeLineIsTheLastOneWhoseStartWasReached() {
        assertEquals(0, activeSyncedLineIndex(starts, 1_000))
        assertEquals(0, activeSyncedLineIndex(starts, 3_999))
        // Lines sharing a start time (duet or accompaniment) resolve to the later one.
        assertEquals(2, activeSyncedLineIndex(starts, 4_000))
        assertEquals(2, activeSyncedLineIndex(starts, 9_499))
        assertEquals(3, activeSyncedLineIndex(starts, 9_500))
        assertEquals(3, activeSyncedLineIndex(starts, Int.MAX_VALUE))
    }

    @Test
    fun autoScrollWaitsOnlyWhileTheLastDragIsRecent() {
        assertEquals(0L, syncedLyricsAutoScrollDelayMs(nowMs = 10_000, lastUserDragMs = 0, resumeAfterMs = 3_000))
        assertEquals(2_000L, syncedLyricsAutoScrollDelayMs(nowMs = 10_000, lastUserDragMs = 9_000, resumeAfterMs = 3_000))
        assertEquals(0L, syncedLyricsAutoScrollDelayMs(nowMs = 13_000, lastUserDragMs = 9_000, resumeAfterMs = 3_000))
        assertEquals(0L, syncedLyricsAutoScrollDelayMs(nowMs = 20_000, lastUserDragMs = 9_000, resumeAfterMs = 3_000))
    }
}
