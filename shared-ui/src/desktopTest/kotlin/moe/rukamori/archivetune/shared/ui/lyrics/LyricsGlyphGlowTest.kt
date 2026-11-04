package moe.rukamori.archivetune.shared.ui.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The box a word's bloom and its own draw window are cut from: the word's seat on the band its glyphs
 * sit on, never the line band behind it — a bloom clipped to the band was what drew the sung word as a
 * filled tile on the background.
 */
class LyricsGlyphGlowTest {
    private val bands =
        LyricsLineBands(
            widthPx = 400f,
            tops = floatArrayOf(0f, 60f),
            bottoms = floatArrayOf(56f, 116f),
            overshootPx = 3f,
        )

    private val wrappedLastWord =
        LyricsWordBox(
            startMs = 1_000L,
            endMs = 2_000L,
            leftPx = 120f,
            rightPx = 210f,
            line = 1,
            wrapsLines = true,
        )

    @Test
    fun theSeatIsTheWordsOwnBandAndNeverTheWholeLine() {
        val seat = lyricsWordSeat(wrappedLastWord, bands)
        assertEquals(120f, seat.left)
        assertEquals(210f, seat.right)
        assertEquals(60f, seat.top)
        assertEquals(116f, seat.bottom)
        // Narrower than the line band: a bloom clipped to the seat cannot paint a tile across the row.
        assertTrue(seat.width < bands.widthPx)
    }

    @Test
    fun aWordThatWrappedOntoItsOwnLineBloomsOnThatLine() {
        val firstBand =
            LyricsWordBox(startMs = 500L, endMs = 900L, leftPx = 0f, rightPx = 60f, line = 0, wrapsLines = true)
        val first = lyricsWordSeat(firstBand, bands)
        assertEquals(0f, first.top)
        assertEquals(56f, first.bottom)
        val last = lyricsWordSeat(wrappedLastWord, bands)
        assertEquals(60f, last.top)
        assertEquals(116f, last.bottom)
        // The last line's word never blooms on the band above it.
        assertTrue(last.top >= bands.bottoms[0])
    }

    @Test
    fun theDrawWindowClearsTheOvershootAndTheLiftRoom() {
        val hole = lyricsWordHole(wrappedLastWord, bands, dipPx = 6f)
        assertEquals(bands.tops[1] - bands.overshootPx, hole.top)
        assertEquals(bands.bottoms[1] + bands.overshootPx + 6f, hole.bottom)
        // Accents above the band and a rising or lowered word below it stay inside the window.
        assertTrue(hole.top < bands.tops[1])
        assertTrue(hole.bottom > bands.bottoms[1])
    }

    @Test
    fun theBloomOnlyEverGrowsPastTheInkItBelongsTo() {
        // A bloom smaller than the ink would leave the letters untouched; it has to reach past them.
        assertTrue(GLYPH_GLOW_SCALE > 1f)
        val seat = lyricsWordSeat(wrappedLastWord, bands)
        val reach = maxOf(seat.width, seat.height) * (GLYPH_GLOW_SCALE - 1f) / 2f + 1f
        assertTrue(reach > 0f)
        assertTrue(seat.inflate(reach).contains(seat.center))
    }
}
