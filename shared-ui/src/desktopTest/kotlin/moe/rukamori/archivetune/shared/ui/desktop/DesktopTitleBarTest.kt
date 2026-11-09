package moe.rukamori.archivetune.shared.ui.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import moe.rukamori.archivetune.shared.ui.component.ProfileMenuHost
import moe.rukamori.archivetune.shared.ui.component.ProfileMenuState
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveDestination
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveRoutes
import moe.rukamori.archivetune.shared.ui.navigation.DesktopSearchFocus
import moe.rukamori.archivetune.shared.ui.navigation.ShellNavigation
import moe.rukamori.archivetune.shared.ui.navigation.writeM4Screenshot

/**
 * The title bar's account area: the settings button and the account avatar the Android top bar
 * draws, and the shared profile menu the avatar opens. The menu is a dropdown anchored to the
 * avatar, so the test finds its rows in the same scene it clicked in.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopTitleBarTest {
    @Test
    fun onePointerClickOnSettingsDismissesTheOpenToolbarMenuAndNavigates() = runComposeUiTest {
        val rows = Rows()
        var settings = 0
        setContent { Bar(1440.dp, rows, { settings++ }) }
        onNodeWithTag("desktop-account-button").performClick()
        onNodeWithTag("desktop-account-menu").assertIsDisplayed()
        onNodeWithTag("desktop-settings-button").performMouseInput { click() }
        onNodeWithTag("desktop-account-menu").assertDoesNotExist()
        runOnIdle { assertEquals(1, settings) }
    }

    private class Rows : ProfileMenuHost {
        val opened = mutableListOf<String>()

        override fun openHistory() {
            opened += "history"
        }

        override fun openNews() {
            opened += "news"
        }

        override fun openNewReleases() {
            opened += "new_releases"
        }

        override fun openLastFmDashboard() {
            opened += "lastfm"
        }

        override fun openMusicRecognition() {
            opened += "recognition"
        }

        override fun openListenTogether() {
            opened += "listen_together"
        }

        override fun openSettings() {
            opened += "settings"
        }
    }

    @Composable
    private fun Bar(
        width: Dp,
        rows: ProfileMenuHost,
        settings: () -> Unit,
        windowControls: (@Composable () -> Unit)? = null,
    ) {
        MaterialTheme {
            CompositionLocalProvider(LocalDesktopWindowControls provides windowControls) {
                Box(Modifier.size(width, 48.dp)) {
                    DesktopTitleBar(
                        title = "Natune",
                        searchFocus = DesktopSearchFocus(),
                        railExpanded = true,
                        onToggleRail = {},
                        onSubmitSearch = {},
                        settingsBadge = true,
                        onOpenSettings = settings,
                        profileMenu =
                            DesktopProfileMenu(
                                state =
                                    ProfileMenuState(
                                        accountName = "Listener",
                                        hasUnreadNews = true,
                                        showListenTogether = true,
                                        showSettings = false,
                                    ),
                                host = rows,
                            ),
                    )
                }
            }
        }
    }

    @Test
    fun theTitleBarShowsSettingsAndTheAccountMenuOpens() = runComposeUiTest {
        val rows = Rows()
        var settings = 0
        setContent { Bar(1440.dp, rows, { settings++ }) }
        onNodeWithTag("desktop-settings-button").assertIsDisplayed()
        onNodeWithTag("desktop-settings-badge").assertIsDisplayed()
        onNodeWithTag("desktop-account-button").assertIsDisplayed()
        // Closed until the avatar is pressed.
        onNodeWithTag("desktop-account-menu").assertDoesNotExist()
        onNodeWithTag("desktop-account-button").performClick()
        onNodeWithTag("desktop-account-menu").assertIsDisplayed()
        onNodeWithTag("desktop-account-menu-name").assertIsDisplayed()
        onNodeWithTag("desktop-title-bar").writeM4Screenshot("desktop-title-bar-account.png")
        onNodeWithTag("desktop-account-menu")
            .writeM4Screenshot("desktop-title-bar-account-menu.png")
        onNodeWithTag("desktop-account-menu-history").assertIsDisplayed()
        onNodeWithTag("desktop-account-menu-news").assertIsDisplayed()
        onNodeWithTag("desktop-account-menu-listen_together").assertIsDisplayed()
        // The title bar's own settings button covers Settings, so the menu leaves that row out.
        onNodeWithTag("desktop-account-menu-settings").assertDoesNotExist()
        onNodeWithTag("desktop-account-menu-history").performClick()
        runOnIdle { assertEquals(listOf("history"), rows.opened) }
        // The row dismisses the menu before it navigates, as the shared profile rows do.
        onNodeWithTag("desktop-account-menu").assertDoesNotExist()
        onNodeWithTag("desktop-settings-button").performClick()
        runOnIdle { assertEquals(1, settings) }
    }

    @Test
    fun theDefaultWindowKeepsSettingsAndAccountBesideCustomCaptionButtons() = runComposeUiTest {
        setContent {
            Bar(
                width = 1080.dp,
                rows = Rows(),
                settings = {},
                windowControls = {
                    Row { Box(Modifier.width(138.dp).height(32.dp).testTag("caption-buttons")) }
                },
            )
        }

        onNodeWithTag("caption-buttons").assertIsDisplayed()
        onNodeWithTag("desktop-settings-button").assertIsDisplayed()
        onNodeWithTag("desktop-account-button").assertIsDisplayed()
    }

    @Test
    fun aNarrowWindowsTitleBarDropsTheActionsInsteadOfClippingThem() = runComposeUiTest {
        val rows = Rows()
        var controls by mutableStateOf<(@Composable () -> Unit)?>(null)
        setContent {
            Bar(
                width = 800.dp,
                rows = rows,
                settings = {},
                windowControls = controls,
            )
        }
        // Without window buttons the 800 dp bar still has room for both actions.
        onNodeWithTag("desktop-settings-button").assertIsDisplayed()
        onNodeWithTag("desktop-account-button").assertIsDisplayed()
        // A host that draws its own three caption buttons needs the room they take, so the bar
        // drops the actions.
        runOnIdle {
            controls = {
                Row { Box(Modifier.width(138.dp).height(32.dp).testTag("caption-buttons")) }
            }
        }
        onNodeWithTag("caption-buttons").assertIsDisplayed()
        onNodeWithTag("desktop-settings-button").assertDoesNotExist()
        onNodeWithTag("desktop-account-button").assertDoesNotExist()
    }

    @Test
    fun theAccountMenuRowsOpenTheDesktopRoutes() {
        val navigation = ShellNavigation()
        val host = desktopProfileMenuHost(navigation)
        // Every row, the route it opens and the destination that route has to resolve to. The
        // assertion runs the shell's own resolve(), so a row whose route the resolver rejects fails
        // here instead of throwing when the window renders the page.
        fun assertOpens(open: () -> Unit, route: String, destination: ArchiveDestination) {
            open()
            assertEquals(route, navigation.route)
            val resolved =
                requireNotNull(ArchiveRoutes.resolve(navigation.route)) {
                    "the account menu opened ${navigation.route}, which is not an ArchiveTune route"
                }
            assertEquals(destination, resolved.destination)
        }
        assertOpens(host::openHistory, HISTORY_ROUTE, ArchiveDestination.HISTORY)
        assertOpens(host::openNewReleases, "new_release", ArchiveDestination.RELEASES)
        assertOpens(host::openSettings, "settings", ArchiveDestination.SETTINGS)
        assertOpens(
            host::openListenTogether,
            "listen_together_from_topbar",
            ArchiveDestination.LISTEN_TOGETHER,
        )
        assertOpens(host::openNews, "news", ArchiveDestination.NEWS)
        // This host has no standalone Last.fm destination: it mounts the dashboard as a settings
        // page, so the row opens that settings route.
        assertOpens(
            host::openLastFmDashboard,
            ArchiveRoutes.LASTFM_DASHBOARD_ROUTE,
            ArchiveDestination.SETTINGS,
        )
        assertOpens(
            host::openMusicRecognition,
            ArchiveRoutes.musicRecognition(),
            ArchiveDestination.MUSIC_RECOGNITION,
        )
    }
}
