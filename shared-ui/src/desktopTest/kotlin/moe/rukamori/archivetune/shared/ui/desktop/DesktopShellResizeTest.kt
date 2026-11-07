package moe.rukamori.archivetune.shared.ui.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import moe.rukamori.archivetune.shared.ui.navigation.ShellNavigation
import moe.rukamori.archivetune.shared.ui.theme.Space

/**
 * The shell has no size of its own: the rail, the page surface and the player bar are all sized from the constraints
 * the window gives them. A real-PC defect showed a window whose bottom third was never painted, so the first question
 * was whether the scaffold had cached a height. This test grows and shrinks the window under a composed shell and
 * checks that the page and the player bar move to the new bottom edge.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopShellResizeTest {
    @Composable
    private fun Shell(size: Pair<Dp, Dp>) {
        MaterialTheme {
            Box(Modifier.requiredSize(size.first, size.second)) {
                DesktopShell(
                    navigation = ShellNavigation(),
                    state = DesktopShellState(),
                    titleBar = { Text("title-bar", Modifier.testTag("test-title-bar")) },
                    playerBar = {
                        // The real bar is exactly its metric tall, so the geometry below means what the window's does.
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(DesktopShellMetrics.playerBarHeight)
                                .testTag("test-player-bar"),
                        ) {}
                    },
                    queuePane = { modifier -> Box(modifier) },
                    lyricsPane = { modifier -> Box(modifier) },
                ) { pageModifier ->
                    Box(pageModifier.testTag("test-page")) {}
                }
            }
        }
    }

    /** The page surface's bottom edge, in window dp, for a window [height] tall. */
    private fun pageBottom(height: Dp): Dp = height - DesktopShellMetrics.playerBarHeight - Space.xs

    /** Every bottom edge in the window, measured against the window's own [height]. */
    private fun ComposeUiTest.assertWindowLayout(height: Dp) {
        onNodeWithTag("desktop-shell").getUnclippedBoundsInRoot().let { bounds ->
            assertEquals(height.value, bounds.bottom.value, 0.5f, "the scaffold is the window")
        }
        onNodeWithTag("desktop-content-surface").getUnclippedBoundsInRoot().let { bounds ->
            assertEquals(
                pageBottom(height).value,
                bounds.bottom.value,
                0.5f,
                "the page fills a ${height.value} dp window",
            )
        }
        onNodeWithTag("test-player-bar").getUnclippedBoundsInRoot().let { bounds ->
            assertEquals(height.value, bounds.bottom.value, 0.5f, "the player bar sits on the window's bottom edge")
        }
    }

    @Test
    fun thePageAndPlayerFollowTheWindowWhenItGrows() = runComposeUiTest {
        var window by mutableStateOf(955.dp to 700.dp)
        setContent { Shell(window) }
        assertWindowLayout(700.dp)

        // The size the real-PC report had: the content must not stay at the old bottom edge.
        runOnIdle { window = 955.dp to 1030.dp }
        waitForIdle()
        assertWindowLayout(1030.dp)
    }

    @Test
    fun thePageAndPlayerFollowTheWindowWhenItShrinks() = runComposeUiTest {
        var window by mutableStateOf(1400.dp to 1030.dp)
        setContent { Shell(window) }
        assertWindowLayout(1030.dp)

        runOnIdle { window = 955.dp to 700.dp }
        waitForIdle()
        assertWindowLayout(700.dp)
    }
}
