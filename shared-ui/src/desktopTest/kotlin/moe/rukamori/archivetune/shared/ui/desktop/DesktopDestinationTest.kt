package moe.rukamori.archivetune.shared.ui.desktop

import moe.rukamori.archivetune.shared.ui.navigation.ShellNavigation
import moe.rukamori.archivetune.shared.ui.navigation.ShellTab
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure-logic regressions for the desktop rail. A rail click is a top-level navigation: it must pop the chosen
 * destination back to its root (leaving any playlist or search-result detail behind) rather than reselecting a stack
 * that still shows a detail. Before the fix every click was forced through `toRoot = false`, so reselecting the
 * current item only bumped the reselection counter and successive secondary destinations stacked up on Library.
 */
class DesktopDestinationTest {

    @Test
    fun reselectingTheCurrentHomeDestinationPopsItsDetailToTheHomeRoot() {
        val navigation = ShellNavigation(ShellTab.Home)
        navigation.open("online_playlist/PL1")
        assertEquals("online_playlist/PL1", navigation.route)
        assertEquals(DesktopDestination.Home, desktopSelectedDestination(navigation.selectedTab, navigation.route))

        DesktopDestination.Home.open(navigation)

        assertEquals("home", navigation.route)
        assertFalse(navigation.canGoBack)
        assertEquals(DesktopDestination.Home, desktopSelectedDestination(navigation.selectedTab, navigation.route))
    }

    @Test
    fun aRailClickLeavesADetailEvenWhenTheOtherTopLevelTabIsSelected() {
        val navigation = ShellNavigation(ShellTab.Library)
        navigation.open("online_playlist/PL1")
        // A detail page keeps its section lit, so the detail is reachable while Library is the selected tab.
        assertEquals(DesktopDestination.Library, desktopSelectedDestination(navigation.selectedTab, navigation.route))

        DesktopDestination.Home.open(navigation)

        assertEquals(ShellTab.Home, navigation.selectedTab)
        assertEquals("home", navigation.route)
        assertEquals(DesktopDestination.Home, desktopSelectedDestination(navigation.selectedTab, navigation.route))
        assertFalse(navigation.canGoBack)
        // The other tab keeps its saved detail; a rail click does not destroy the stack it is not showing.
        assertTrue(navigation.snapshot().stacks.getValue(ShellTab.Library).size == 2)
    }

    @Test
    fun reselectingLibraryAfterSecondaryDestinationsReturnsToTheLibraryRoot() {
        val navigation = ShellNavigation(ShellTab.Library)
        DesktopDestination.History.open(navigation)
        DesktopDestination.Statistics.open(navigation)
        DesktopDestination.Together.open(navigation)
        assertEquals(TOGETHER_ROUTE, navigation.route)

        DesktopDestination.Library.open(navigation)

        assertEquals("library", navigation.route)
        assertFalse(navigation.canGoBack)
        assertEquals(DesktopDestination.Library, desktopSelectedDestination(navigation.selectedTab, navigation.route))
    }

    @Test
    fun eachSecondaryDestinationIsOnlyOneBackAwayFromTheLibraryRoot() {
        val navigation = ShellNavigation(ShellTab.Home)
        DesktopDestination.Library.open(navigation)
        DesktopDestination.History.open(navigation)
        assertEquals(HISTORY_ROUTE, navigation.route)
        assertEquals(DesktopDestination.History, desktopSelectedDestination(navigation.selectedTab, navigation.route))

        DesktopDestination.Statistics.open(navigation)
        assertEquals(STATISTICS_ROUTE, navigation.route)
        assertEquals(2, navigation.snapshot().stacks.getValue(ShellTab.Library).size)
        assertEquals(DesktopDestination.Statistics, desktopSelectedDestination(navigation.selectedTab, navigation.route))

        DesktopDestination.Together.open(navigation)
        assertEquals(TOGETHER_ROUTE, navigation.route)
        // Successive rail destinations pop the previous one instead of accumulating entries above the library root.
        assertEquals(2, navigation.snapshot().stacks.getValue(ShellTab.Library).size)
        assertTrue(navigation.back())
        assertEquals("library", navigation.route)
        assertFalse(navigation.back())
    }

    @Test
    fun reselectingTheSameSecondaryDestinationDoesNotGrowTheStack() {
        val navigation = ShellNavigation(ShellTab.Library)
        DesktopDestination.History.open(navigation)
        assertEquals(2, navigation.snapshot().stacks.getValue(ShellTab.Library).size)

        DesktopDestination.History.open(navigation)

        assertEquals(HISTORY_ROUTE, navigation.route)
        assertEquals(2, navigation.snapshot().stacks.getValue(ShellTab.Library).size)
        assertTrue(navigation.back())
        assertEquals("library", navigation.route)
    }
}
