/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
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
