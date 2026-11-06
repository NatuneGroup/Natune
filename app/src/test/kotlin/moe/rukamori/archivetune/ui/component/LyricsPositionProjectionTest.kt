package moe.rukamori.archivetune.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The position the lyrics are drawn at. The loop anchors the player's position at the vsync instant it
 * read it, and the draw phase projects that anchor with its own clock, so a frame paints the position of
 * the instant it is painted at instead of the value the frame before it published.
 */
class LyricsPositionProjectionTest {
    private val oneMs = 1_000_000L

    @Test
    fun theProjectionAdvancesAtThePlaybackSpeed() {
        assertEquals(1_500L, LyricsPositionProjection.project(1_000, 500 * oneMs, 1f))
        assertEquals(2_000L, LyricsPositionProjection.project(1_000, 500 * oneMs, 2f))
        assertEquals(1_250L, LyricsPositionProjection.project(1_000, 500 * oneMs, 0.5f))
    }

    @Test
    fun theProjectionNeverGoesBehindTheAnchor() {
        assertEquals(1_000L, LyricsPositionProjection.project(1_000, -500 * oneMs, 1f))
        assertEquals(0L, LyricsPositionProjection.project(0, 0, 1f))
    }

    @Test
    fun driftInsideTheBoundsIsFollowedGradually() {
        // 40 ms ahead is inside the forward bound: only the correction share is taken, so the sweep
        // eases onto the player's clock instead of stepping.
        val projected = LyricsPositionProjection.project(1_000, 0, 1f)
        val corrected = LyricsPositionProjection.corrected(1_000, 0, 1f, playerPositionMs = 1_040)
        assertEquals(1_000L, projected)
        assertEquals(1_022L, corrected)
        assertEquals(40L * LyricsPositionProjection.DRIFT_CORRECTION, (corrected - projected).toFloat(), 0.6f)
    }

    @Test
    fun aJumpPastTheBoundsTakesThePlayersPositionOutright() {
        assertTrue(LyricsPositionProjection.shouldReanchor(playerPositionMs = 5_000, projectedPositionMs = 1_000))
        assertTrue(LyricsPositionProjection.shouldReanchor(playerPositionMs = 0, projectedPositionMs = 1_000))
        assertFalse(LyricsPositionProjection.shouldReanchor(playerPositionMs = 1_040, projectedPositionMs = 1_000))
        assertFalse(LyricsPositionProjection.shouldReanchor(playerPositionMs = 900, projectedPositionMs = 1_000))
        assertEquals(
            5_000L,
            LyricsPositionProjection.corrected(1_000, 0, 1f, playerPositionMs = 5_000),
        )
    }

    @Test
    fun aPausedClockHoldsItsAnchorAndARunningOneProjects() {
        val clock = EnhancedLyricsDrawClock()
        clock.anchor(positionMs = 2_000, atNanos = 100 * oneMs, speed = 1f, advancing = false)
        assertEquals(2_000L, clock.positionMs(nowNanos = 500 * oneMs))

        clock.anchor(positionMs = 2_000, atNanos = 100 * oneMs, speed = 1f, advancing = true)
        assertEquals(2_400L, clock.positionMs(nowNanos = 500 * oneMs))

        // Twice the speed covers twice the distance in the same wall time.
        clock.anchor(positionMs = 2_000, atNanos = 0, speed = 2f, advancing = true)
        assertEquals(3_000L, clock.positionMs(nowNanos = 500 * oneMs))
    }

    @Test
    fun aDrawBeforeTheAnchorKeepsTheAnchorItself() {
        val clock = EnhancedLyricsDrawClock()
        clock.anchor(positionMs = 4_000, atNanos = 1_000 * oneMs, speed = 1f, advancing = true)
        assertEquals(4_000L, clock.positionMs(nowNanos = 900 * oneMs))
    }
}
