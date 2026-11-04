package moe.rukamori.archivetune.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lyrics header's width budget: the title and artist take every dp the artwork and the three
 * buttons do not, so the marquee viewport is a readable title instead of the six characters three
 * fixed 48 dp targets left over on a phone.
 */
class LyricsHeaderMetricsTest {
    /** A 360 dp phone row inside the sheet's 28 dp side padding. */
    private val phoneRowDp = 360f - 28f * 2

    @Test
    fun theTitleAndArtistKeepTheRestOfAPhoneRow() {
        val text = LyricsHeaderMetrics.textWidth(phoneRowDp)
        // Fixed 72 dp artwork plus three 48 dp targets left 58 dp of this row; the header now keeps
        // enough for the title itself, and the artist marquees the remainder.
        assertTrue("text width was " + text + " dp", text >= 95f)
        assertTrue(text < phoneRowDp)
    }

    @Test
    fun theRowIsPartitionedExactlySoNothingOverlaps() {
        for (width in listOf(240f, 304f, 355f, 420f, 544f, 744f)) {
            val occupied = LyricsHeaderMetrics.artworkSize(width) + LyricsHeaderMetrics.CHROME_DP + LyricsHeaderMetrics.textWidth(width)
            assertEquals(width, occupied, 0.001f)
        }
    }

    @Test
    fun theArtworkGivesWayBeforeTheTapTargetsDo() {
        // A 48 dp target is the accessibility floor, so the artwork shrinks instead.
        assertTrue(LyricsHeaderMetrics.ICON_TARGET_DP >= 48f)
        assertTrue(LyricsHeaderMetrics.artworkSize(240f) < LyricsHeaderMetrics.artworkSize(744f))
        assertEquals(LyricsHeaderMetrics.ARTWORK_MIN_DP, LyricsHeaderMetrics.artworkSize(200f), 0.001f)
        assertEquals(LyricsHeaderMetrics.ARTWORK_MAX_DP, LyricsHeaderMetrics.artworkSize(1_000f), 0.001f)
    }

    @Test
    fun aWiderRowIsOnlyEverMoreRoomForTheTitle() {
        var previous = LyricsHeaderMetrics.textWidth(200f)
        var width = 210f
        while (width <= 900f) {
            val text = LyricsHeaderMetrics.textWidth(width)
            assertTrue("text width shrank at " + width + " dp", text >= previous)
            previous = text
            width += 10f
        }
    }

    @Test
    fun aDegenerateRowNeverClaimsNegativeRoom() {
        for (width in listOf(0f, 40f, 120f)) {
            assertEquals(0f, LyricsHeaderMetrics.textWidth(width), 0.001f)
        }
    }
}
