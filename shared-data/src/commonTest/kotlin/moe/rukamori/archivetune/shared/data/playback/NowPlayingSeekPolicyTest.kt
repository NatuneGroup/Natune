package moe.rukamori.archivetune.shared.data.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NowPlayingSeekPolicyTest {
    @Test
    fun absoluteScrubberPositionBecomesMillisAndClampsToDuration() {
        // A Lock Screen scrubber sends an absolute position in seconds, relative to zero.
        assertEquals(80_000L, nowPlayingSeekTargetMs(0L, 80.0, 180_000L))
        assertEquals(180_000L, nowPlayingSeekTargetMs(0L, 250.0, 180_000L))
        assertEquals(0L, nowPlayingSeekTargetMs(0L, 0.0, 180_000L))
    }

    @Test
    fun intervalSkipMovesFromTheInterpolatedPositionAndStaysInsideTheTrack() {
        assertEquals(35_000L, nowPlayingSeekTargetMs(30_000L, 5.0, 180_000L))
        assertEquals(0L, nowPlayingSeekTargetMs(2_000L, -5.0, 180_000L))
        assertEquals(180_000L, nowPlayingSeekTargetMs(178_000L, 5.0, 180_000L))
    }

    @Test
    fun anUnknownOrZeroDurationIsUnboundedNotClampedToZero() {
        // A live stream with no reported length: a forward skip must advance, not seek back to the
        // start.
        assertEquals(35_000L, nowPlayingSeekTargetMs(30_000L, 5.0, null))
        assertEquals(35_000L, nowPlayingSeekTargetMs(30_000L, 5.0, 0L))
        assertEquals(35_000L, nowPlayingSeekTargetMs(30_000L, 5.0, -1L))
        assertEquals(25_000L, nowPlayingSeekTargetMs(30_000L, -5.0, null))
        assertEquals(0L, nowPlayingSeekTargetMs(2_000L, -5.0, null))
    }

    @Test
    fun aNegativeBaseIsTreatedAsZero() {
        assertEquals(5_000L, nowPlayingSeekTargetMs(-10_000L, 5.0, 180_000L))
        assertEquals(0L, nowPlayingSeekTargetMs(-10_000L, -5.0, 180_000L))
    }

    @Test
    fun nonFiniteRequestsAreRejectedAndHugeFiniteOnesSaturate() {
        assertNull(nowPlayingSeekTargetMs(0L, Double.NaN, 180_000L))
        assertNull(nowPlayingSeekTargetMs(0L, Double.POSITIVE_INFINITY, 180_000L))
        assertNull(nowPlayingSeekTargetMs(0L, Double.NEGATIVE_INFINITY, 180_000L))
        assertNull(nowPlayingSeekTargetMs(0L, Double.MAX_VALUE, null))
        // Finite seconds that stay finite after the millisecond scaling, but exceed the known
        // duration.
        assertEquals(180_000L, nowPlayingSeekTargetMs(0L, 1.0e15, 180_000L))
        // With no duration bound, saturate instead of wrapping the Long conversion.
        assertEquals(Long.MAX_VALUE, nowPlayingSeekTargetMs(0L, 1.0e16, null))
    }
}
