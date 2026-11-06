package moe.rukamori.archivetune.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lyrics header's width budget: the title and artist take every dp the artwork does not, because the
 * sheet's controls no longer sit in this row. Three 48 dp targets here left a phone about 100 dp for a
 * name — the complaint that there was no space for it, while the row still read as spaced out.
 */
class LyricsHeaderMetricsTest {
    /** A 360 dp phone row inside the sheet's 16 dp side padding. */
    private val phoneRowDp = 360f - 16f * 2

    @Test
    fun theNameKeepsMostOfAPhoneRow() {
        val text = LyricsHeaderMetrics.textWidth(phoneRowDp)
        // The three-target header left 100 dp of this row; the row has only the artwork and one gap now.
        assertTrue("text width was " + text + " dp", text >= 200f)
        assertTrue(text < phoneRowDp)
    }

    @Test
    fun theRowIsPartitionedExactlySoNothingOverlaps() {
        for (width in listOf(240f, 320f, 328f, 420f, 544f, 744f)) {
            val occupied =
                LyricsHeaderMetrics.artworkSize(width) +
                    LyricsHeaderMetrics.CHROME_DP +
                    LyricsHeaderMetrics.textWidth(width)
            assertEquals(width, occupied, 0.001f)
        }
    }

    @Test
    fun theArtworkIsATileNotAThumbnail() {
        // Not "a bit bigger": the tile was a 64 dp thumbnail on a phone, and it was called really small.
        val phone = LyricsHeaderMetrics.artworkSize(phoneRowDp)
        assertTrue("artwork was " + phone + " dp", phone >= 88f)
        assertTrue(phone > 64f)
        assertEquals(
            LyricsHeaderMetrics.ARTWORK_MIN_DP,
            LyricsHeaderMetrics.artworkSize(200f),
            0.001f,
        )
        assertEquals(
            LyricsHeaderMetrics.ARTWORK_MAX_DP,
            LyricsHeaderMetrics.artworkSize(1_000f),
            0.001f,
        )
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

    @Test
    fun theOnlyChromeInTheNameRowIsTheArtworkGap() {
        // The controls live in the sheet's chrome row (LyricsScreenFooter), which is outside the
        // auto-hiding transport, so this row spends nothing on tap targets.
        assertEquals(LyricsHeaderMetrics.ARTWORK_GAP_DP, LyricsHeaderMetrics.CHROME_DP, 0.001f)
    }
}
