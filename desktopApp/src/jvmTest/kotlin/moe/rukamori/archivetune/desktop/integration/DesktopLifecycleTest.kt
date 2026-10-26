/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.desktop.integration

import kotlin.test.*

class DesktopLifecycleTest {
    @Test fun hiddenMinimizedOccludedAndSuspendedStopBothClocksNotAudio() {
        assertTrue(DesktopWindowActivity(true, false, false, true).animate)
        assertFalse(DesktopWindowActivity(false, false, false, true).animate)
        assertFalse(DesktopWindowActivity(true, true, false, true).animate)
        assertFalse(DesktopWindowActivity(true, false, true, true).animate)
        assertFalse(DesktopWindowActivity(true, false, false, false).animate)
    }

    @Test fun closingRequiresReachableTrayBeforeHiding() {
        assertTrue(shouldCloseToTray(preferTray = true, trayAvailable = true))
        assertFalse(shouldCloseToTray(preferTray = true, trayAvailable = false))
        assertFalse(shouldCloseToTray(preferTray = false, trayAvailable = true))
    }
}
