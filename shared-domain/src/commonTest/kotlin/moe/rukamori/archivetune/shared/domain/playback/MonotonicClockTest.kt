package moe.rukamori.archivetune.shared.domain.playback

import kotlin.test.Test
import kotlin.test.assertTrue

class MonotonicClockTest {
    @Test
    fun systemClockNeverMovesBackwards() {
        val clock: MonotonicClock = SystemMonotonicClock
        var previous = clock.nowNanos()

        repeat(1_000) {
            val now = clock.nowNanos()
            assertTrue(now >= previous, "clock moved from $previous to $now")
            previous = now
        }
    }
}
