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
        // The tile's 72 dp floor plus its gap is where the name row runs out: below that the text
        // column is flat zero rather than negative. 120 dp is past it — the floor leaves 36 dp of
        // name there, which the partition test above pins down.
        for (width in listOf(0f, 40f, 80f)) {
            assertEquals(0f, LyricsHeaderMetrics.textWidth(width), 0.001f)
        }
        assertEquals(36f, LyricsHeaderMetrics.textWidth(120f), 0.001f)
    }

    @Test
    fun theOnlyChromeInTheNameRowIsTheArtworkGap() {
        // The controls live in the sheet's chrome row (LyricsScreenFooter), which is outside the
        // auto-hiding transport, so this row spends nothing on tap targets.
        assertEquals(LyricsHeaderMetrics.ARTWORK_GAP_DP, LyricsHeaderMetrics.CHROME_DP, 0.001f)
    }

    @Test
    fun aShortWindowHeaderShrinksTheTileAndKeepsTheName() {
        // The same row in a pop-up window: the header must not eat the list's room, but the title
        // still has to read — so the tile gives way first.
        val compactTile = LyricsHeaderMetrics.artworkSize(phoneRowDp, compact = true)
        val fullTile = LyricsHeaderMetrics.artworkSize(phoneRowDp)
        assertTrue("compact tile was " + compactTile + " dp", compactTile < fullTile)
        assertTrue("compact tile was " + compactTile + " dp", compactTile >= 48f)
        assertTrue(
            "compact text width was " + LyricsHeaderMetrics.textWidth(phoneRowDp, compact = true),
            LyricsHeaderMetrics.textWidth(phoneRowDp, compact = true) >=
                LyricsHeaderMetrics.textWidth(phoneRowDp),
        )
    }

    @Test
    fun theCompactRowIsPartitionedExactlyToo() {
        for (width in listOf(160f, 240f, 328f, 420f, 744f)) {
            val occupied =
                LyricsHeaderMetrics.artworkSize(width, compact = true) +
                    LyricsHeaderMetrics.artworkGapDp(compact = true) +
                    LyricsHeaderMetrics.textWidth(width, compact = true)
            assertEquals(width, occupied, 0.001f)
        }
        assertEquals(8f, LyricsHeaderMetrics.artworkGapDp(compact = true), 0.001f)
        assertEquals(
            LyricsHeaderMetrics.ARTWORK_GAP_DP,
            LyricsHeaderMetrics.artworkGapDp(compact = false),
            0.001f,
        )
    }
}
