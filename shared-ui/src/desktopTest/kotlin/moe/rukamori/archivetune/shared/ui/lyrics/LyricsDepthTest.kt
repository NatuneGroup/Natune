package moe.rukamori.archivetune.shared.ui.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LyricsDepthTest {
    @Test
    fun theSungRowAndRowsBeforeAnyLineStayFlat() {
        assertNull(LyricsDepth.distance(index = 3, primary = 3, active = true))
        assertNull(LyricsDepth.distance(index = 0, primary = -1, active = false))
        assertEquals(LyricsDepth.Flat, LyricsDepth.of(null, reduceMotion = false))
    }

    @Test
    fun furtherRowsAreSmallerFainterAndSofterUpToTheLastStep() {
        val near = LyricsDepth.of(1, reduceMotion = false)
        val far = LyricsDepth.of(LyricsDepth.MAX_STEPS, reduceMotion = false)
        assertTrue(near.scale < 1f && far.scale < near.scale)
        assertTrue(near.alpha < 1f && far.alpha < near.alpha)
        assertTrue(near.blurDp > 0f && far.blurDp > near.blurDp)
        assertEquals(far, LyricsDepth.of(LyricsDepth.MAX_STEPS + 10, reduceMotion = false))
        assertEquals(2, LyricsDepth.distance(index = 1, primary = 3, active = false))
    }

    @Test
    fun reducedMotionKeepsFullSizeButStillFades() {
        val depth = LyricsDepth.of(2, reduceMotion = true)
        assertEquals(1f, depth.scale)
        assertTrue(depth.alpha < 1f)
    }
}
