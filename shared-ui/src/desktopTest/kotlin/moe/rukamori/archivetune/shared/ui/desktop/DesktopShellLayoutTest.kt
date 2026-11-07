package moe.rukamori.archivetune.shared.ui.desktop

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The window-size decisions of the desktop shell, without a composition: they are what keeps 800 x 500 usable. */
class DesktopShellLayoutTest {
    /** The slots the shell would draw for [open], with the rail the shell would give that window. */
    private fun slots(windowWidth: Dp, windowHeight: Dp, vararg open: DesktopPane): List<DesktopPaneSlot> {
        val railWidth = if (desktopRailExpanded(windowWidth, windowHeight, userExpanded = true)) {
            DesktopShellMetrics.railExpandedWidth
        } else {
            DesktopShellMetrics.railCollapsedWidth
        }
        return desktopPaneSlots(
            windowWidth = windowWidth,
            windowHeight = windowHeight,
            railWidth = railWidth,
            open = open.map { DesktopPaneRequest(it, DesktopShellMetrics.paneDefaultWidth) },
            active = open.lastOrNull(),
        )
    }

    /**
     * The reference app's breakpoints: 600 / 840 / 1200 / 1600 dp. A 800 dp window is the minimum window and is
     * therefore *Medium*, not Compact; only a window narrower than 600 dp is Compact.
     */
    @Test
    fun widthClassesFollowTheReferenceBreakpoints() {
        assertEquals(DesktopWidthClass.Compact, desktopWidthClass(599.dp))
        assertEquals(DesktopWidthClass.Medium, desktopWidthClass(600.dp))
        assertEquals(DesktopWidthClass.Medium, desktopWidthClass(800.dp))
        assertEquals(DesktopWidthClass.Medium, desktopWidthClass(839.dp))
        assertEquals(DesktopWidthClass.Expanded, desktopWidthClass(840.dp))
        assertEquals(DesktopWidthClass.Expanded, desktopWidthClass(1199.dp))
        assertEquals(DesktopWidthClass.Large, desktopWidthClass(1200.dp))
        assertEquals(DesktopWidthClass.ExtraLarge, desktopWidthClass(1600.dp))
    }

    /** The reference app's height breakpoints: 480 / 900 dp. A 500 dp window is Medium, not Compact. */
    @Test
    fun heightClassesFollowTheReferenceBreakpoints() {
        assertEquals(DesktopHeightClass.Compact, desktopHeightClass(479.dp))
        assertEquals(DesktopHeightClass.Medium, desktopHeightClass(480.dp))
        assertEquals(DesktopHeightClass.Medium, desktopHeightClass(500.dp))
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
        assertEquals(
            DesktopPanePlacement.Overlay,
            slots(800.dp, 500.dp, DesktopPane.Queue).single().placement,
            "800 x 500 keeps only the rail and the page",
        )
        assertEquals(
            DesktopPanePlacement.Overlay,
            slots(1920.dp, 420.dp, DesktopPane.Queue).single().placement,
            "a short window covers the page instead of narrowing it",
        )
    }

    /** The budget spends the real widths: rail, page minimum, each pane and its divider. */
    @Test
    fun thePaneBudgetSpendsTheRealWidths() {
        // 800 x 500: 800 - 96 rail - 560 page leaves 144 dp, not enough for a 280 dp pane.
        assertEquals(
            listOf(DesktopPanePlacement.Overlay),
            slots(800.dp, 500.dp, DesktopPane.Queue, DesktopPane.Lyrics).map { it.placement },
        )

        // 1080 x 720: 1080 - 180 rail - 560 page = 340 dp, so the queue docks narrowed to 334 dp and the newest pane
        // (the lyrics) covers the page.
        val narrow = slots(1080.dp, 720.dp, DesktopPane.Queue, DesktopPane.Lyrics)
        assertEquals(listOf(DesktopPanePlacement.Docked, DesktopPanePlacement.Overlay), narrow.map { it.placement })
        assertEquals(334.dp, narrow.first().width)
        assertEquals(DesktopPane.Lyrics, narrow.last().pane)

        // 1200 x 720: the width class says two panes, the widths do not (180 + 560 + 6 + 360 leaves 94 dp).
        val desktop = slots(1200.dp, 720.dp, DesktopPane.Queue, DesktopPane.Lyrics)
        assertEquals(listOf(DesktopPanePlacement.Docked, DesktopPanePlacement.Overlay), desktop.map { it.placement })
        assertEquals(360.dp, desktop.first().width)

        // 1440 x 900: the lyrics narrow to 328 dp so both dock and the page keeps exactly its 560 dp.
        val comfortable = slots(1440.dp, 900.dp, DesktopPane.Queue, DesktopPane.Lyrics)
        assertEquals(listOf(DesktopPanePlacement.Docked, DesktopPanePlacement.Docked), comfortable.map { it.placement })
        assertEquals(listOf(360.dp, 328.dp), comfortable.map { it.width })

        // 1920 x 1080: both keep their default width and the page keeps 1008 dp.
        val wide = slots(1920.dp, 1080.dp, DesktopPane.Queue, DesktopPane.Lyrics)
        assertEquals(listOf(360.dp, 360.dp), wide.map { it.width })
        assertEquals(listOf(DesktopPanePlacement.Docked, DesktopPanePlacement.Docked), wide.map { it.placement })
    }

    @Test
    fun aDockedPaneNeverSqueezesThePageBelowItsMinimum() {
        val windows = listOf(
            800.dp to 500.dp,
            1080.dp to 720.dp,
            1200.dp to 720.dp,
            1440.dp to 900.dp,
            1920.dp to 1080.dp,
        )
        windows.forEach { (width, height) ->
            val railWidth = if (desktopRailExpanded(width, height, userExpanded = true)) {
                DesktopShellMetrics.railExpandedWidth
            } else {
                DesktopShellMetrics.railCollapsedWidth
            }
            val docked = slots(width, height, DesktopPane.Queue, DesktopPane.Lyrics)
                .filter { it.placement == DesktopPanePlacement.Docked }
            val used = docked.map { it.width.value }.sum() +
                docked.size * DesktopShellMetrics.paneDividerWidth.value
            val page = width.value - railWidth.value - used
            assertTrue(
                page >= DesktopShellMetrics.primaryPaneMinWidth.value,
                "the page fell to $page dp at $width x $height",
            )
        }
    }

    @Test
    fun theSearchFieldNarrowsAtTightWidths() {
        // The reference app's measures: 390 dp while the window has room for it, 280 dp below the tight width.
        assertEquals(390.dp, DesktopShellMetrics.searchFieldWideWidth)
        assertEquals(280.dp, DesktopShellMetrics.searchFieldNarrowWidth)
        assertEquals(280.dp, desktopSearchFieldWidth(900.dp))
        assertEquals(390.dp, desktopSearchFieldWidth(1280.dp))
        assertEquals(280.dp, desktopSearchFieldWidth(DesktopShellMetrics.searchTightWindowWidth - 1.dp))
        assertEquals(390.dp, desktopSearchFieldWidth(DesktopShellMetrics.searchTightWindowWidth))
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
