package moe.rukamori.archivetune.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lyrics header's width budget: the title and artist take every dp the artwork and the three
 * buttons do not, so the marquee viewport is a readable title instead of the six characters three
 * fixed 48 dp targets left over on a phone, and the artwork is a tile the title sits beside rather
 * than a thumbnail of it.
 */
class LyricsHeaderMetricsTest {
    /** A 360 dp phone row inside the sheet's 20 dp side padding. */
    private val phoneRowDp = 360f - 20f * 2

    @Test
    fun theTitleAndArtistKeepTheRestOfAPhoneRow() {
        val text = LyricsHeaderMetrics.textWidth(phoneRowDp)
        // Fixed 72 dp artwork plus three 48 dp targets and wider gaps left 58 dp of this row; the
        // header now keeps the title's own line for a phone-sized row.
        assertTrue("text width was " + text + " dp", text >= 96f)
        assertTrue(text < phoneRowDp)
    }

    @Test
    fun theRowIsPartitionedExactlySoNothingOverlaps() {
        for (width in listOf(240f, 320f, 355f, 420f, 544f, 744f)) {
            val occupied = LyricsHeaderMetrics.artworkSize(width) + LyricsHeaderMetrics.CHROME_DP + LyricsHeaderMetrics.textWidth(width)
            assertEquals(width, occupied, 0.001f)
        }
    }

    @Test
    fun theArtworkIsATileTheTitleSitsBeside() {
        // The maintainer's call: the tile was a 40 dp thumbnail on a phone. It holds at least 56 dp
        // there and keeps growing with the row until it stops being a thumbnail at all.
        assertTrue(
            "artwork was " + LyricsHeaderMetrics.artworkSize(phoneRowDp) + " dp",
            LyricsHeaderMetrics.artworkSize(phoneRowDp) >= 56f,
        )
        assertTrue(LyricsHeaderMetrics.artworkSize(240f) < LyricsHeaderMetrics.artworkSize(744f))
        assertEquals(LyricsHeaderMetrics.ARTWORK_MIN_DP, LyricsHeaderMetrics.artworkSize(200f), 0.001f)
        assertEquals(LyricsHeaderMetrics.ARTWORK_MAX_DP, LyricsHeaderMetrics.artworkSize(1_000f), 0.001f)
    }

    @Test
    fun theTapTargetsKeepTheAccessibilityFloor() {
        // A 48 dp target is the accessibility floor, so the artwork and the gaps give way instead.
        assertTrue(LyricsHeaderMetrics.ICON_TARGET_DP >= 48f)
    }

    @Test
    fun theButtonsReadAsOneGroup() {
        // No gap between the three targets: the 48 dp hit areas already separate the icons, and a gap
        // is room the title cannot use.
        assertEquals(0f, LyricsHeaderMetrics.BUTTON_GAP_DP, 0.001f)
        assertTrue(LyricsHeaderMetrics.TRACK_GAP_DP < LyricsHeaderMetrics.ARTWORK_GAP_DP)
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
