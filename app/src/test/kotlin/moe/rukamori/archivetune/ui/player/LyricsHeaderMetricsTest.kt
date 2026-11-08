package moe.rukamori.archivetune.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lyrics header matches upstream ArchiveTune: a fixed 72 dp tile and 14 dp gap, with the title and
 * artist taking the rest of the row. A tile that scaled with the row (up to 104 dp) made the header
 * dominate the lyrics on a phone.
 */
class LyricsHeaderMetricsTest {
    /** A 360 dp phone row inside the sheet's 16 dp side padding. */
    private val phoneRowDp = 360f - 16f * 2

    @Test
    fun theTileMatchesUpstreamAtEveryWidth() {
        for (width in listOf(240f, 328f, 420f, 744f, 1_000f)) {
            assertEquals(72f, LyricsHeaderMetrics.artworkSize(width), 0.001f)
            assertEquals(48f, LyricsHeaderMetrics.artworkSize(width, compact = true), 0.001f)
        }
        assertEquals(14f, LyricsHeaderMetrics.artworkGapDp(compact = false), 0.001f)
        assertEquals(8f, LyricsHeaderMetrics.artworkGapDp(compact = true), 0.001f)
    }

    @Test
    fun theNameKeepsMostOfAPhoneRow() {
        val text = LyricsHeaderMetrics.textWidth(phoneRowDp)
        assertTrue("text width was " + text + " dp", text >= 230f)
        assertTrue(text < phoneRowDp)
    }

    @Test
    fun theRowIsPartitionedExactlySoNothingOverlaps() {
        for (compact in listOf(false, true)) {
            for (width in listOf(160f, 240f, 320f, 328f, 420f, 544f, 744f)) {
                val occupied =
                    LyricsHeaderMetrics.artworkSize(width, compact) +
                        LyricsHeaderMetrics.artworkGapDp(compact) +
                        LyricsHeaderMetrics.textWidth(width, compact)
                assertEquals(width, occupied, 0.001f)
            }
        }
    }

    @Test
    fun aDegenerateRowNeverClaimsNegativeRoom() {
        for (width in listOf(0f, 40f, 80f)) {
            assertEquals(0f, LyricsHeaderMetrics.textWidth(width), 0.001f)
            assertTrue(LyricsHeaderMetrics.artworkSize(width) <= width)
        }
        assertEquals(34f, LyricsHeaderMetrics.textWidth(120f), 0.001f)
    }

    @Test
    fun theOnlyChromeInTheNameRowIsTheArtworkGap() {
        assertEquals(LyricsHeaderMetrics.ARTWORK_GAP_DP, LyricsHeaderMetrics.CHROME_DP, 0.001f)
    }
}
