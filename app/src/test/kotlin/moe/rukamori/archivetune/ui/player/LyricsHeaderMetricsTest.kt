package moe.rukamori.archivetune.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsHeaderMetricsTest {
    @Test fun artworkFollowsBothTextLinesAtEveryFontScale() {
        for (width in listOf(240f, 328f, 744f)) {
            for (height in listOf(40f, 48f, 64f, 96f)) {
                assertEquals(height, LyricsHeaderMetrics.artworkSize(width, height), 0.001f)
            }
        }
    }

    @Test fun narrowRowsNeverClaimNegativeOrExcessSpace() {
        for (width in listOf(0f, 20f, 40f)) {
            val edge = LyricsHeaderMetrics.artworkSize(width, 48f)
            assertTrue(edge >= 0f && edge <= width)
        }
        assertEquals(0f, LyricsHeaderMetrics.artworkSize(120f, -1f), 0.001f)
    }

    @Test fun compactWindowsRetainTheirTighterGap() {
        assertEquals(14f, LyricsHeaderMetrics.artworkGapDp(false), 0.001f)
        assertEquals(8f, LyricsHeaderMetrics.artworkGapDp(true), 0.001f)
    }
}
