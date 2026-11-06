package moe.rukamori.archivetune.shared.ui.navigation

import moe.rukamori.archivetune.shared.domain.settings.*
import kotlin.test.*

/** Characterizations of MainActivity's compact/expanded/rail decisions, not a replacement preference owner. */
class ShellPolicyTest {
    @Test fun materialIsDefaultAndAppleLocksOneRow() {
        assertEquals(ShellLayout.BottomBar, shellLayout(400f, false, PreferenceSnapshot.EMPTY))
        val apple = PreferenceSnapshot.of(AppleMusicExperienceKey to true)
        assertEquals(ShellLayout.CompactRow, shellLayout(400f, false, apple))
        assertEquals(ShellLayout.CompactRow, shellLayout(1200f, false, apple))
    }
    @Test fun wideAndTvChooseRailWithoutChangingTheSelectedTab() {
        assertEquals(ShellLayout.Rail, shellLayout(900f, false, PreferenceSnapshot.EMPTY))
        assertEquals(ShellLayout.TvRail, shellLayout(400f, true, PreferenceSnapshot.EMPTY))
    }
    @Test fun compactAndScrollHiddenAreDifferentChoices() {
        val adaptive = PreferenceSnapshot.of(NavigationBarCompactBehaviorKey to "ADAPTIVE")
        assertEquals(ShellLayout.BottomBar, shellLayout(400f, false, adaptive, false))
        assertEquals(ShellLayout.CompactRow, shellLayout(400f, false, adaptive, true))
        // "Hide while scrolling" without a stored compact behaviour resolves to ADAPTIVE (PillStyle), so the
        // bar compacts into the one-row layout on scroll instead of sliding away.
        val hideOnly = PreferenceSnapshot.of(NavigationBarHideOnScrollKey to true)
        assertEquals(NavigationBarCompactBehavior.ADAPTIVE, hideOnly.navigationBarCompactBehavior())
        assertEquals(ShellLayout.CompactRow, shellLayout(400f, false, hideOnly, true))
        // Sliding away is the expanded-bar variant, as in the host MainActivity: it needs the explicit choice.
        val hidden = PreferenceSnapshot.of(
            NavigationBarHideOnScrollKey to true,
            NavigationBarCompactBehaviorKey to "ALWAYS_EXPANDED",
        )
        assertEquals(ShellLayout.BottomBar, shellLayout(400f, false, hidden, false))
        assertEquals(ShellLayout.MiniPlayerOnly, shellLayout(400f, false, hidden, true))
    }
    @Test fun searchStepsAsideAndDetailKeepsTheCompactRow() {
        assertEquals(ShellLayout.Hidden, shellLayout(400f, false, PreferenceSnapshot.EMPTY, searchActive = true))
        assertEquals(ShellLayout.MiniPlayerOnly, shellLayout(400f, false, PreferenceSnapshot.EMPTY, topLevel = false))
        val compact = PreferenceSnapshot.of(NavigationBarCompactBehaviorKey to "ALWAYS_COMPACT")
        assertEquals(ShellLayout.CompactRow, shellLayout(400f, false, compact, topLevel = false))
    }
    @Test fun desktopKeepsOneSidebarOnEveryRouteIncludingSettings() {
        // Phone hosts keep the settings page chrome-free; the desktop keeps the rail and the mini player beside it.
        assertEquals(ShellLayout.Hidden, shellLayout(1440f, false, PreferenceSnapshot.EMPTY, settingsRoute = true))
        assertEquals(ShellLayout.Rail, shellLayout(1440f, false, PreferenceSnapshot.EMPTY, settingsRoute = true, desktop = true))
        assertEquals(ShellLayout.Rail, shellLayout(1024f, false, PreferenceSnapshot.EMPTY, settingsRoute = true, desktop = true))
        // Desktop keeps the sidebar whichever phone pill style is stored.
        val apple = PreferenceSnapshot.of(AppleMusicExperienceKey to true)
        assertEquals(ShellLayout.CompactRow, shellLayout(1440f, false, apple))
        assertEquals(ShellLayout.Rail, shellLayout(1440f, false, apple, desktop = true))
        // A full-screen search still steps aside on desktop.
        assertEquals(ShellLayout.Hidden, shellLayout(1440f, false, PreferenceSnapshot.EMPTY, searchActive = true, desktop = true))
    }
    @Test fun navigatingBackRestoresTheExactDestinationAndTab() {
        val nav = ShellNavigation(ShellTab.Home)
        nav.open("online_playlist/PL1")
        nav.open("album/MPRE1")
        assertTrue(nav.back())
        assertEquals("online_playlist/PL1", nav.route)
        nav.select(ShellTab.Library)
        nav.open("local_playlist/LP1")
        nav.select(ShellTab.Home)
        assertEquals("online_playlist/PL1", nav.route)
        nav.select(ShellTab.Library)
        assertEquals("local_playlist/LP1", nav.route)
    }
    @Test fun selectingCurrentTabReturnsToItsRootAndRootBackIsNotConsumed() {
        val nav = ShellNavigation(ShellTab.Home)
        nav.open("album/a")
        nav.select(ShellTab.Home)
        assertEquals("home", nav.route)
        assertFalse(nav.back())
    }
    @Test fun compactSearchRetainsBaselineConditionalRoutes() {
        assertTrue(compactLeftIsLibrary("local_playlist/LP1"))
        assertTrue(compactLeftIsLibrary("history"))
        assertFalse(compactLeftIsLibrary("album/a"))
        assertFalse(compactSearchVisible("artist/a"))
        assertTrue(compactSearchVisible("history"))
        assertFalse(compactSearchVisible("local_playlist/LP1"))
    }
}
