package moe.rukamori.archivetune.ui.component

import org.junit.Assert.assertTrue
import org.junit.Test

class EnhancedLineSettleTest {
    private val displacement = 120f
    private val primary = 5

    private fun offsetOf(row: Int, elapsedMs: Long, displacementPx: Float): Float {
        val stagger = EnhancedLyricsMotion.staggerDelayMs(EnhancedLyricsMotion.distance(row, primary))
        val delay = EnhancedLineSettleMotion.delayFor(row, primary, displacementPx, stagger)
        return EnhancedLineSettleMotion.offsetPx(displacementPx, elapsedMs, delay)
    }

    @Test
    fun aLineChangeNeverClosesTheGapBetweenTwoRows() {
        for (elapsed in 0L..EnhancedLineSettleMotion.DURATION_MS) {
            for (row in 0 until 11) {
                // The gap between row r and row r + 1 is the laid-out gap plus offset(r + 1) - offset(r).
                for (d in floatArrayOf(displacement, -displacement)) {
                    val closing = offsetOf(row, elapsed, d) - offsetOf(row + 1, elapsed, d)
                    assertTrue("rows $row/${row + 1} closed by $closing px at $elapsed ms (move $d)", closing <= 0.001f)
                }
            }
        }
    }
}
