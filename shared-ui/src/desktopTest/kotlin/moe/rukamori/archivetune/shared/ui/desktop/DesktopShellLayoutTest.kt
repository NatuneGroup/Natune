package moe.rukamori.archivetune.shared.ui.desktop

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The window-size decisions of the desktop shell, without a composition: they are what keeps 800 x 500 usable. */
class DesktopShellLayoutTest {
    @Test
    fun widthClassesFollowTheReferenceBreakpoints() {
        assertEquals(DesktopWidthClass.Compact, desktopWidthClass(800.dp))
        assertEquals(DesktopWidthClass.Medium, desktopWidthClass(600.dp))
        assertEquals(DesktopWidthClass.Medium, desktopWidthClass(839.dp))
        assertEquals(DesktopWidthClass.Expanded, desktopWidthClass(840.dp))
        assertEquals(DesktopWidthClass.Large, desktopWidthClass(1200.dp))
        assertEquals(DesktopWidthClass.ExtraLarge, desktopWidthClass(1600.dp))
    }

    @Test
    fun heightClassesFollowTheReferenceBreakpoints() {
        assertEquals(DesktopHeightClass.Compact, desktopHeightClass(500.dp))
        assertEquals(DesktopHeightClass.Medium, desktopHeightClass(480.dp))
        assertEquals(DesktopHeightClass.Medium, desktopHeightClass(899.dp))
        assertEquals(DesktopHeightClass.Expanded, desktopHeightClass(900.dp))
    }

    @Test
    fun theRailCollapsesAtTheMinimumWindowAndExpandsWhenWide() {
        assertFalse(desktopRailExpanded(800.dp, 500.dp, userExpanded = true), "the minimum window keeps the icon rail")
        assertFalse(desktopRailExpanded(1400.dp, 400.dp, userExpanded = true), "a short window drops the labels")
        assertFalse(desktopRailExpanded(1400.dp, 900.dp, userExpanded = false), "the user's collapse still wins")
        assertTrue(desktopRailExpanded(1400.dp, 900.dp, userExpanded = true))
    }

    @Test
    fun theMinimumWindowShowsAPaneOverThePage() {
        assertEquals(DesktopPaneMode.Hidden, desktopPaneMode(800.dp, 500.dp, requested = false))
        assertEquals(DesktopPaneMode.Overlay, desktopPaneMode(800.dp, 500.dp, requested = true))
        assertEquals(DesktopPaneMode.Overlay, desktopPaneMode(1920.dp, 420.dp, requested = true))
    }

    @Test
    fun aWideWindowDocksAPaneAndOnlyThenTwo() {
        assertEquals(DesktopPaneMode.Docked, desktopPaneMode(1400.dp, 900.dp, requested = true))
        assertEquals(1, desktopPaneCapacity(1024.dp, 720.dp))
        assertEquals(2, desktopPaneCapacity(1400.dp, 900.dp))
        assertEquals(2, desktopPaneCapacity(1700.dp, 900.dp))
        assertEquals(1, desktopPaneCapacity(1700.dp, 420.dp), "a short window carries one pane at most")
    }

    @Test
    fun aPaneNeverSqueezesThePageBelowItsMinimum() {
        val clamped = clampPaneWidth(700.dp, 1400.dp, DesktopShellMetrics.railExpandedWidth, otherPanes = 0)
        assertEquals(1400.dp.value - DesktopShellMetrics.railExpandedWidth.value - DesktopShellMetrics.primaryPaneMinWidth.value, clamped.value)
        val floor = clampPaneWidth(10.dp, 1400.dp, DesktopShellMetrics.railExpandedWidth, otherPanes = 0)
        assertEquals(DesktopShellMetrics.paneMinWidth, floor)
    }

    @Test
    fun theSearchFieldNarrowsAtTightWidths() {
        assertEquals(DesktopShellMetrics.searchFieldNarrowWidth, desktopSearchFieldWidth(900.dp))
        assertEquals(DesktopShellMetrics.searchFieldWideWidth, desktopSearchFieldWidth(1280.dp))
    }

    @Test
    fun panesToggleOneAtATimeAndKeepTheirWidth() {
        val state = DesktopShellState()
        state.toggle(DesktopPane.Queue)
        assertTrue(state.queueOpen)
        assertEquals(DesktopPane.Queue, state.activePane)
        state.resize(DesktopPane.Queue, 420.dp)
        assertEquals(420.dp, state.width(DesktopPane.Queue))
        state.toggle(DesktopPane.Queue)
        assertFalse(state.queueOpen)
        state.open(DesktopPane.Lyrics)
        state.open(DesktopPane.Queue)
        assertEquals(DesktopPane.Queue, state.activePane, "the newest pane is the active one")
        state.close(DesktopPane.Queue)
        assertEquals(DesktopPane.Lyrics, state.activePane)
        state.closePanes()
        assertFalse(state.queueOpen || state.lyricsOpen)
    }
}
