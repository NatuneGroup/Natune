package moe.rukamori.archivetune.shared.ui.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
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

    /**
     * The rail costs the page 104 dp at a desktop window, and the page surface starts exactly where the rail ends, so
     * the sidebar's footprint is what the user sees. The items keep the reference's pitch and pill inside it.
     */
    @Test
    fun theRailIsTheReferenceWidthAndThePageStartsWhereItEnds() = runComposeUiTest {
        setContent { Shell(1440.dp to 900.dp, ShellNavigation(), DesktopShellState()) }
        val rail = onNodeWithTag("desktop-rail").getBoundsInRoot()
        assertEquals(104f, (rail.right - rail.left).value, 0.5f)
        val page = onNodeWithTag("desktop-content-surface").getBoundsInRoot()
        assertEquals(rail.right.value, page.left.value, 0.5f)
        // Items sit on the reference's 68 dp pitch, and the pill is 56 x 32 centred on the 96 dp item column. The pill
        // and the label live inside the item's merged semantics, so they are only addressable in the unmerged tree.
        val home = onNodeWithTag("desktop-rail-home").getBoundsInRoot()
        val library = onNodeWithTag("desktop-rail-library").getBoundsInRoot()
        assertEquals(68f, (library.top - home.top).value, 0.5f)
        val pill = onNodeWithTag("desktop-rail-indicator-home", useUnmergedTree = true).getBoundsInRoot()
        assertEquals(56f, (pill.right - pill.left).value, 0.5f)
        assertEquals(32f, (pill.bottom - pill.top).value, 0.5f)
        // The item column is 96 dp inside the rail, so the 56 dp pill starts 20 dp in and is centred on 48 dp.
        assertEquals(48f, ((pill.left + pill.right) / 2 - rail.left).value, 0.5f)
        assertEquals(20f, (pill.left - rail.left).value, 0.5f)
        // One label line, never two: a longer name ellipsizes inside the item instead of growing it.
        val label = onNodeWithTag("desktop-rail-label-home", useUnmergedTree = true)
            .assertExists()
            .getBoundsInRoot()
        assertTrue((label.bottom - label.top).value <= 20f, "the label wrapped: $label")
    }

    /** The minimum window drops the labels, and with them the 16 dp they cost the page. */
    @Test
    fun theMinimumWindowKeepsOnlyTheIndicatorPills() = runComposeUiTest {
        setContent { Shell(800.dp to 500.dp, ShellNavigation(), DesktopShellState()) }
        val rail = onNodeWithTag("desktop-rail").getBoundsInRoot()
        assertEquals(88f, (rail.right - rail.left).value, 0.5f)
        onNodeWithTag("desktop-rail-label-home", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag("desktop-rail-indicator-home", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun theMinimumWindowShowsAPaneOverThePageInsteadOfSqueezingIt() = runComposeUiTest {
        val navigation = ShellNavigation()
        val state = DesktopShellState()
        setContent { Shell(800.dp to 500.dp, navigation, state) }
        runOnIdle { state.open(DesktopPane.Queue) }
        onNodeWithTag("desktop-pane-overlay").assertIsDisplayed()
        onNodeWithTag("desktop-pane-queue").assertIsDisplayed()
        // The overlay pane sits at the end of the surface, so at this width it covers the scrim's centre; the part of
        // the scrim a user can actually hit is the strip to its left.
        onNodeWithTag("desktop-pane-scrim").performTouchInput { click(Offset(8f, height / 2f)) }
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

    /** 900 dp of window cannot carry a 280 dp pane beside the 104 dp rail and the 560 dp page. */
    @Test
    fun aNarrowWindowKeepsOnlyTheNewestPane() = runComposeUiTest {
        val navigation = ShellNavigation()
        val state = DesktopShellState()
        setContent { Shell(900.dp to 720.dp, navigation, state) }
        runOnIdle { state.open(DesktopPane.Queue) }
        onNodeWithTag("desktop-pane-queue").assertIsDisplayed()
        runOnIdle { state.open(DesktopPane.Lyrics) }
        // No pane fits beside the page, so the layout keeps the newest over it and the state follows the layout.
        onNodeWithTag("desktop-pane-lyrics").assertIsDisplayed()
        runOnIdle { assertFalse(state.queueOpen) }
    }
}
