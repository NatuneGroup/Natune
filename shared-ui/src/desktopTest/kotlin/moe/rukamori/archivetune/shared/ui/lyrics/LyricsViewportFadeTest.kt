package moe.rukamori.archivetune.shared.ui.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LyricsViewportFadeTest {
    @Test
    fun rampsSitOnTheBoxEdgesAndTheMiddleStaysOpaque() {
        val stops = LyricsViewportFade.alphaStops(1000f, 100f, 200f, 0.22f)!!
        assertEquals(0f, stops.first().position)
        assertEquals(0f, stops.first().alpha)
        assertEquals(1f, stops.last().position, 1e-6f)
        assertEquals(0f, stops.last().alpha)
        val topEnd = stops.first { it.alpha == 1f }
        assertEquals(0.1f, topEnd.position, 1e-6f)
        val bottomStart = stops.last { it.alpha == 1f }
        assertEquals(0.8f, bottomStart.position, 1e-6f)
    }

    @Test
    fun stopsAreOrderedSoTheGradientIsWellFormed() {
        val stops = LyricsViewportFade.alphaStops(640f, 56f, 96f, 0.22f)!!
        stops.zipWithNext().forEach { (a, b) -> assertTrue(b.position >= a.position, "$a then $b") }
        assertTrue(stops.all { it.alpha in 0f..1f })
    }

    @Test
    fun shortBoxesGetProportionallyShorterRamps() {
        val (top, bottom) = LyricsViewportFade.edgeHeights(300f, 56f, 96f, 0.22f)
        assertEquals(56f, top)
        assertEquals(66f, bottom, 1e-4f)
        val (cappedTop, cappedBottom) = LyricsViewportFade.edgeHeights(100f, 80f, 80f, 0.9f)
        assertTrue(cappedTop + cappedBottom <= 100f)
    }

    @Test
    fun aMissingEdgeStaysOpaqueAndNothingToFadeYieldsNoMask() {
        val bottomOnly = LyricsViewportFade.alphaStops(500f, 0f, 50f, 0.22f)!!
        assertEquals(LyricsViewportFade.Stop(0f, 1f), bottomOnly.first())
        assertNull(LyricsViewportFade.alphaStops(500f, 0f, 0f, 0.22f))
        assertNull(LyricsViewportFade.alphaStops(0f, 56f, 96f, 0.22f))
    }
}
