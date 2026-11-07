package moe.rukamori.archivetune.shared.ui.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LyricsLineSettleTest {
    private val displacement = 120f
    private val primary = 5

    private fun offsetOf(row: Int, elapsedMs: Long, displacementPx: Float = displacement): Float {
        val stagger = LyricsDepth.staggerMs(LyricsDepth.distance(row, primary, active = row == primary))
        val delay = LyricsLineSettleMotion.delayFor(row, primary, displacementPx, stagger)
        return LyricsLineSettleMotion.offsetPx(displacementPx, elapsedMs, delay)
    }

    @Test
    fun aLineChangeNeverClosesTheGapBetweenTwoRows() {
        for (elapsed in 0L..LyricsLineSettleMotion.DURATION_MS) {
            for (row in 0 until 11) {
                // Row r ends where row r + 1 begins, so the gap between them is the laid-out gap plus
                // offset(r + 1) - offset(r): the pair closes whenever the upper row is drawn further down.
                val closing = offsetOf(row, elapsed) - offsetOf(row + 1, elapsed)
                assertTrue(closing <= 0.001f, "row ${row + 1} closed on row $row by $closing px at $elapsed ms")
            }
        }
    }

    @Test
    fun aBackwardMoveNeverClosesTheGapEither() {
        for (elapsed in 0L..LyricsLineSettleMotion.DURATION_MS) {
            for (row in 0 until 11) {
                val closing = offsetOf(row, elapsed, -displacement) - offsetOf(row + 1, elapsed, -displacement)
                assertTrue(closing <= 0.001f, "row $row closed on row ${row + 1} by $closing px at $elapsed ms")
            }
        }
    }

    @Test
    fun rowsAheadOfTheSungLineMoveWithItAndTrailingRowsStillRipple() {
        assertEquals(0L, LyricsLineSettleMotion.delayFor(rowIndex = 3, primaryIndex = 5, displacementPx = 80f, staggerMs = 90L))
        assertEquals(90L, LyricsLineSettleMotion.delayFor(rowIndex = 7, primaryIndex = 5, displacementPx = 80f, staggerMs = 90L))
        assertEquals(0L, LyricsLineSettleMotion.delayFor(rowIndex = 7, primaryIndex = 5, displacementPx = -80f, staggerMs = 90L))
        assertEquals(90L, LyricsLineSettleMotion.delayFor(rowIndex = 3, primaryIndex = 5, displacementPx = -80f, staggerMs = 90L))
    }
}
