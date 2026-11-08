package moe.rukamori.archivetune.desktop

import moe.rukamori.archivetune.shared.ui.navigation.ArchiveDestination
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveRoutes
import moe.rukamori.archivetune.shared.ui.settings.DesktopQueueRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins which settings pages the desktop window mounts, so a phone-only page or a second entry to the same page cannot
 * come back silently. The pages themselves are shared with Android and iOS; only this host's route table changes.
 */
class DesktopSettingsRoutesTest {
    private val mounted =
        DESKTOP_SETTINGS_ROUTES + DESKTOP_GENERAL_SETTINGS_ROUTES + DESKTOP_EXTRA_SETTINGS_ROUTES

    @Test
    fun thePhoneNavigationStylePageAndTheDuplicatedPlayerPageAreNotMounted() {
        listOf("settings/appearance/navigation_bar", "settings/appearance/player").forEach { route ->
            assertFalse(route in mounted, "desktop still mounts $route")
        }
    }

    @Test
    fun theDesktopQueuePageIsMounted() {
        assertTrue(DesktopQueueRoute in mounted)
    }

    @Test
    fun theMergedAppearancePageAndItsSectionsStayMounted() {
        listOf(
            "settings/appearance", "settings/appearance/theme", "settings/appearance/interface",
            "settings/appearance/extras", "settings/appearance/lyrics_animations",
        ).forEach { route -> assertTrue(route in mounted, "desktop does not mount $route") }
    }

    @Test
    fun theLastFmDashboardTheAccountMenuOpensIsMounted() {
        // The shared account menu opens this value, so the resolver has to accept it and this host
        // has to mount it: otherwise the row throws instead of showing the dashboard.
        val route = assertNotNull(ArchiveRoutes.resolve(ArchiveRoutes.LASTFM_DASHBOARD_ROUTE))
        assertEquals(ArchiveDestination.SETTINGS, route.destination)
        assertTrue(desktopMountsSettings(route), "desktop does not mount ${route.value}")
        assertTrue(route.value in mounted, "desktop does not mount ${route.value}")
    }
}
