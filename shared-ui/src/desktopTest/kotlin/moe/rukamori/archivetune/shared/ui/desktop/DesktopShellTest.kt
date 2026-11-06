package moe.rukamori.archivetune.shared.ui.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import moe.rukamori.archivetune.shared.ui.navigation.ShellNavigation
import moe.rukamori.archivetune.shared.ui.navigation.ShellTab

/**
 * The desktop scaffold at the two window sizes that matter: the 800 x 500 minimum and a comfortable desktop window.
 * The page is a test tag rather than a real screen, so the test measures the shell's own columns.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopShellTest {
    @Composable
    private fun Shell(size: Pair<Dp, Dp>, navigation: ShellNavigation, state: DesktopShellState) {
        MaterialTheme {
            Box(Modifier.requiredSize(size.first, size.second)) {
                DesktopShell(
                    navigation = navigation,
                    state = state,
                    titleBar = { Text("title-bar") },
                    playerBar = { Text("player-bar") },
                    queuePane = { modifier -> Box(modifier) { Text("queue-content") } },
                    lyricsPane = { modifier -> Box(modifier) { Text("lyrics-content") } },
                ) { pageModifier ->
                    Box(pageModifier) { Text("page") }
                }
            }
        }
    }

    @Test
    fun everyDestinationIsReachableInTheMinimumWindow() = runComposeUiTest {
        val navigation = ShellNavigation()
        val state = DesktopShellState()
        setContent { Shell(800.dp to 500.dp, navigation, state) }
        DesktopDestination.entries.forEach { destination ->
            onNodeWithTag("desktop-rail-${destination.name.lowercase()}").assertIsDisplayed()
        }
        onNodeWithTag("desktop-content-surface").assertIsDisplayed()
        onNodeWithTag("desktop-rail-history").performClick()
        runOnIdle {
            assertEquals(ShellTab.Library, navigation.selectedTab)
            assertEquals(HISTORY_ROUTE, navigation.route)
        }
    }

    @Test
    fun theMinimumWindowShowsAPaneOverThePageInsteadOfSqueezingIt() = runComposeUiTest {
        val navigation = ShellNavigation()
        val state = DesktopShellState()
        setContent { Shell(800.dp to 500.dp, navigation, state) }
        runOnIdle { state.open(DesktopPane.Queue) }
        onNodeWithTag("desktop-pane-overlay").assertIsDisplayed()
        onNodeWithTag("desktop-pane-queue").assertIsDisplayed()
        onNodeWithTag("desktop-pane-scrim").performClick()
        runOnIdle { assertFalse(state.queueOpen) }
    }

    @Test
    fun aWideWindowDocksThePaneBesideThePage() = runComposeUiTest {
        val navigation = ShellNavigation()
        val state = DesktopShellState()
        setContent { Shell(1500.dp to 950.dp, navigation, state) }
        runOnIdle { state.open(DesktopPane.Lyrics) }
        onNodeWithTag("desktop-pane-lyrics").assertIsDisplayed()
        onNodeWithTag("desktop-pane-divider-lyrics").assertIsDisplayed()
        assertTrue(state.lyricsOpen)
        runOnIdle { state.close(DesktopPane.Lyrics) }
        onNodeWithTag("desktop-pane-lyrics").assertDoesNotExist()
    }

    @Test
    fun aNarrowWindowKeepsOnlyTheNewestPane() = runComposeUiTest {
        val navigation = ShellNavigation()
        val state = DesktopShellState()
        setContent { Shell(1024.dp to 720.dp, navigation, state) }
        runOnIdle { state.open(DesktopPane.Queue) }
        onNodeWithTag("desktop-pane-queue").assertIsDisplayed()
        runOnIdle { state.open(DesktopPane.Lyrics) }
        // One pane fits, so the layout keeps the newest and the state follows the layout.
        onNodeWithTag("desktop-pane-lyrics").assertIsDisplayed()
        runOnIdle { assertFalse(state.queueOpen) }
    }
}
