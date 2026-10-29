package moe.rukamori.archivetune.shared.data.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OutputLatencyTest {
    @Test
    fun reportsMillisecondsWithoutInventingAnOffsetForInvalidNativeValues() {
        assertEquals(0L, outputLatencyMillis(0.0))
        assertEquals(25L, outputLatencyMillis(0.025))
        assertEquals(2_000L, outputLatencyMillis(2.0))
        for (value in listOf(-1.0, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)) {
            assertNull(outputLatencyMillis(value))
        }
    }

    @Test
    fun roundsSubMillisecondsAndRefusesOverflow() {
        assertEquals(0L, outputLatencyMillis(0.0004))
        assertEquals(2L, outputLatencyMillis(0.0015))
        assertNull(outputLatencyMillis(Double.MAX_VALUE))
        assertNull(outputLatencyMillis(Long.MAX_VALUE.toDouble()))
    }
}
