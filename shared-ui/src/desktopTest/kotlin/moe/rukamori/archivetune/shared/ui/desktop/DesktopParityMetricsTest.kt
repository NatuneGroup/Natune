package moe.rukamori.archivetune.shared.ui.desktop

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import moe.rukamori.archivetune.shared.ui.theme.RowHeights

/**
 * The window geometry the reference desktop app sets, pinned so a later layout change has to admit it moved the chrome:
 * a 56 dp top band, a 72 dp player bar and a 648 dp Now Playing column.
 */
class DesktopParityMetricsTest {
    /**
     * The top band is one row, not a caption strip above a search row: the mark, the search and the app's own window
     * buttons all live inside the same 56 dp, which is why the page starts where the reference page starts.
     */
    @Test
    fun theTopBandIsOneFiftySixDpRow() {
        assertEquals(56.dp, DesktopShellMetrics.titleBarHeight)
        assertEquals(RowHeights.topBar, DesktopShellMetrics.titleBarHeight)
        // Everything that sits inside it fits vertically: the search field, the rail toggle and the caption buttons.
        assertTrue(DesktopShellMetrics.searchFieldHeight <= DesktopShellMetrics.titleBarHeight)
        assertTrue(DesktopShellMetrics.captionButtonHeight <= DesktopShellMetrics.titleBarHeight)
        assertEquals(390.dp, DesktopShellMetrics.searchFieldWideWidth)
    }

    /** The player bar is the reference's 72 dp strip: a hairline seek band above a transport row that keeps a pointer-sized artwork. */
    @Test
    fun thePlayerBarIsSeventyTwoDpAndItsTwoRowsFillIt() {
        assertEquals(72.dp, DesktopShellMetrics.playerBarHeight)
        assertEquals(RowHeights.playerBar, DesktopShellMetrics.playerBarHeight)
        assertEquals(
            DesktopShellMetrics.playerBarHeight,
            DesktopShellMetrics.playerSeekTrackHeight + DesktopShellMetrics.playerControlsHeight,
        )
        assertTrue(DesktopShellMetrics.playerSeekTrackHeight < DesktopShellMetrics.playerControlsHeight)
        assertTrue(DesktopShellMetrics.playerArtworkSize < DesktopShellMetrics.playerControlsHeight)
    }

    /**
     * Now Playing is the reference's fixed measure, not a fluid split of whatever width is left: 648 dp at the 1430 px
     * capture and at a 1024 window, and only narrower when the lyrics would lose their own minimum.
     */
    @Test
    fun theNowPlayingColumnKeepsItsSixFortyEightMeasure() {
        assertEquals(648.dp, DesktopShellMetrics.expandedPlayerColumnWidth)
        // 1430 window: 32 dp padding each side leaves 1366 dp, so the measure is kept and the lyrics take the rest.
        assertEquals(648.dp, desktopExpandedPlayerColumnWidth(1430.dp - 64.dp))
        // 1024 window: 960 dp of content is exactly 648 + 32 gap + 280 dp of lyrics.
        assertEquals(648.dp, desktopExpandedPlayerColumnWidth(960.dp))
        // Below that the player column gives way first, but never under the artwork's own minimum.
        val narrow = desktopExpandedPlayerColumnWidth(800.dp - 64.dp)
        assertTrue(narrow < 648.dp, "a 800 dp window cannot carry the full measure: $narrow")
        assertEquals(736.dp - 32.dp - DesktopShellMetrics.expandedLyricsMinWidth, narrow)
        assertEquals(DesktopShellMetrics.expandedArtworkMin, desktopExpandedPlayerColumnWidth(200.dp))
    }

    /** The artwork on that column is the reference's 392 dp square, not a sleeve that grows with the window. */
    @Test
    fun theNowPlayingArtworkIsThreeNinetyTwo() {
        assertEquals(392.dp, DesktopShellMetrics.expandedArtworkMax)
        assertTrue(DesktopShellMetrics.expandedArtworkMax <= DesktopShellMetrics.expandedPlayerColumnWidth)
    }

    /**
     * The sidebar is the reference app's 104 dp column, not a second page: on its capture the page surface starts at
     * x = 104 and the first two indicator pills are centred on x = 48, which is the centre of the 96 dp item column the
     * 8 dp trailing gap leaves. Each item is the M3 pill with the glyph in it and a one-line label under it, on the
     * reference's 68 dp pitch.
     */
    @Test
    fun theRailIsTheReferenceOneHundredFourDpSidebar() {
        assertEquals(104.dp, DesktopShellMetrics.railExpandedWidth)
        assertEquals(104.dp, desktopRailWidth(expanded = true))
        assertEquals(88.dp, DesktopShellMetrics.railCollapsedWidth)
        assertEquals(88.dp, desktopRailWidth(expanded = false))
        assertEquals(8.dp, DesktopShellMetrics.railTrailingGap)
        // The item column the pills centre on: 104 - 8 = 96 dp.
        assertEquals(96.dp, DesktopShellMetrics.railExpandedWidth - DesktopShellMetrics.railTrailingGap)
        // The pill is the M3 rail indicator: 56 x 32 dp, fully rounded, around a 24 dp glyph.
        assertEquals(56.dp, DesktopShellMetrics.railItemIndicatorWidth)
        assertEquals(32.dp, DesktopShellMetrics.railItemIndicatorHeight)
        assertEquals(DesktopShellMetrics.railItemIndicatorHeight / 2, DesktopShellMetrics.railItemCorner)
        assertEquals(24.dp, DesktopShellMetrics.railItemIcon)
        assertTrue(
            DesktopShellMetrics.railItemIndicatorWidth <= DesktopShellMetrics.railExpandedWidth -
                DesktopShellMetrics.railTrailingGap,
            "the pill fits the item column",
        )
        // Pill, label gap and one 12 sp / 16 dp label line fit the 60 dp item, and consecutive pills are 68 dp apart.
        assertTrue(
            DesktopShellMetrics.railItemIndicatorHeight + DesktopShellMetrics.railItemLabelGap + 16.dp <=
                DesktopShellMetrics.railItemHeight,
        )
        assertEquals(
            68.dp,
            DesktopShellMetrics.railItemHeight + DesktopShellMetrics.railItemSpacing,
            "the reference's item pitch",
        )
        // Five items and their spacing fit the row the 800 x 500 minimum window leaves between the two bars.
        val railRow = 500.dp - DesktopShellMetrics.titleBarHeight - DesktopShellMetrics.playerBarHeight
        val items = DesktopShellMetrics.railItemHeight * DesktopDestination.entries.size +
            DesktopShellMetrics.railItemSpacing * (DesktopDestination.entries.size - 1) + 16.dp
        assertTrue(items <= railRow, "five destinations take $items dp of the $railRow dp rail row")
    }
}
