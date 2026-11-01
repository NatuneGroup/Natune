package moe.rukamori.archivetune.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun activationLeadsOpenEachLineEarly() {
        val activation = enhancedActivationStarts(
            starts = intArrayOf(1_000, 4_000, 4_000, 20_000),
            ends = intArrayOf(3_000, 5_000, 5_000, 24_000),
        )
        // The first line and gaps past the band get the default 250ms lead; the two shared-start
        // lines reuse the same lead so a duet lights together.
        assertEquals(intArrayOf(750, 3_750, 3_750, 19_750).toList(), activation.toList())
    }

    @Test
    fun aTightHandOffGetsTheScaledLead() {
        val activation = enhancedActivationStarts(
            starts = intArrayOf(1_000, 4_300),
            ends = intArrayOf(4_000, 8_000),
        )
        // A 300ms gap sits inside the 200..750ms band: lead = 480 + (300-200) * 270/550 = 529.
        assertEquals(750, activation[0])
        assertEquals(4_300 - 529, activation[1])
    }

    @Test
    fun theInterludeSwellRisesOverTheLast1500ms() {
        val interlude = EnhancedInterlude(0L, 10_000L)
        assertEquals(0f, EnhancedInterludeMotion.endSwell(interlude, 8_400))
        assertEquals(
            EnhancedInterludeMotion.END_SWELL_FROM,
            EnhancedInterludeMotion.endSwell(interlude, 8_500),
            0.001f,
        )
        val middle = EnhancedInterludeMotion.endSwell(interlude, 9_250)
        assertTrue(
            "the swell lerps through the window, got $middle",
            middle > EnhancedInterludeMotion.END_SWELL_FROM &&
                middle < EnhancedInterludeMotion.END_SWELL_TO,
        )
        assertEquals(0f, EnhancedInterludeMotion.endSwell(interlude, 10_000))
    }
}
