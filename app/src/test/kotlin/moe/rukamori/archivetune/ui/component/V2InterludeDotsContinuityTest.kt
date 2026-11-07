package moe.rukamori.archivetune.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression for the V2 interlude dots. They used a `rememberInfiniteTransition` composed only
 * while a break was active, so the pulse restarted at phase 0 on every break — a visible jump. They
 * now read `EnhancedInterludeMotion.breath`/`dotLight` from the absolute lyrics position in the
 * draw phase, like the Enhanced dots. These tests pin the property that makes that continuous: the
 * phase depends on the ABSOLUTE position, never on how far a particular break has progressed.
 */
class V2InterludeDotsContinuityTest {
    @Test
    fun aNewBreakDoesNotRestartThePulse() {
        // Two breaks whose starts are a whole number of breath AND pulse cycles apart. At the same
        // offset into each, every dot and the breath must show the same phase. A transition that
        // restarted per break would show the first-dot phase in both, regardless of absolute time.
        val step =
            EnhancedInterludeMotion.BREATH_CYCLE_MS * 3L // also a whole number of pulse cycles
        val firstBreakAt = 10_000L
        val laterBreakAt = firstBreakAt + step
        val offset = 350L
        for (index in 0 until EnhancedInterludeMotion.DOT_COUNT) {
            assertEquals(
                "dot $index restarted on the new break",
                EnhancedInterludeMotion.dotLight(
                    firstBreakAt + offset,
                    index,
                    reduceMotion = false,
                ),
                EnhancedInterludeMotion.dotLight(
                    laterBreakAt + offset,
                    index,
                    reduceMotion = false,
                ),
                0.0001f,
            )
        }
        assertEquals(
            "breath restarted on the new break",
            EnhancedInterludeMotion.breath(firstBreakAt + offset),
            EnhancedInterludeMotion.breath(laterBreakAt + offset),
            0.0001f,
        )
    }

    @Test
    fun thePhaseDependsOnAbsolutePositionNotBreakProgress() {
        // Two DIFFERENT absolute positions one frame apart must differ (the dots are moving), while
        // a
        // position expressed "x ms into break A" and "x ms into break B" is only equal when the
        // absolute positions share a phase. This is what a break-relative restart could not
        // satisfy.
        val at = 12_345L
        val next = at + 16L
        val moved =
            (0 until EnhancedInterludeMotion.DOT_COUNT).any { index ->
                EnhancedInterludeMotion.dotLight(at, index, reduceMotion = false) !=
                    EnhancedInterludeMotion.dotLight(next, index, reduceMotion = false)
            }
        assertTrue("a frame advances the travelling pulse", moved)

        // Backward and forward over one full cycle land on the same phase (no reset at the wrap).
        val cycle = EnhancedInterludeMotion.PULSE_CYCLE_MS
        for (index in 0 until EnhancedInterludeMotion.DOT_COUNT) {
            assertEquals(
                EnhancedInterludeMotion.dotLight(at, index, reduceMotion = false),
                EnhancedInterludeMotion.dotLight(at + cycle, index, reduceMotion = false),
                0.0001f,
            )
        }
    }
}
