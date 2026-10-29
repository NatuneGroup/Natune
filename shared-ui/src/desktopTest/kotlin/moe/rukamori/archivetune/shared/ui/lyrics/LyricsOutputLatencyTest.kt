package moe.rukamori.archivetune.shared.ui.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals

class LyricsOutputLatencyTest {
    @Test
    fun theOffsetHoldsLyricsBackByTheRouteLatencyAndBuffer() {
        assertEquals(-25L, LyricsFeedTiming.outputLatencyOffsetMs(0.020, 0.005))
    }

    @Test
    fun missingOrNonsensicalReadingsCountAsNone() {
        assertEquals(0L, LyricsFeedTiming.outputLatencyOffsetMs(Double.NaN, -1.0))
        assertEquals(-5L, LyricsFeedTiming.outputLatencyOffsetMs(Double.POSITIVE_INFINITY, 0.005))
    }

    @Test
    fun theOffsetIsBounded() {
        assertEquals(-LyricsFeedTiming.MAX_OUTPUT_LATENCY_MS, LyricsFeedTiming.outputLatencyOffsetMs(9.0, 0.0))
    }
}
