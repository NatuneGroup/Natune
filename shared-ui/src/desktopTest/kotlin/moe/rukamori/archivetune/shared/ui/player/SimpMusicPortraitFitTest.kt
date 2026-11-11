package moe.rukamori.archivetune.shared.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.ui.LocalHostSafeArea
import moe.rukamori.archivetune.shared.ui.SafeAreaPadding
import kotlin.test.*

/**
 * The SimpMusic portrait controls must stay on screen at phone size. The transport and bottom action rows have to sit
 * inside the player's scroll viewport, and that viewport has to sit inside the window box. The 393 x 852 case uses the
 * safe-area insets of the native iOS render. Density is pinned to 1 so bounds in pixels equal dp.
 */
@OptIn(ExperimentalTestApi::class)
class SimpMusicPortraitFitTest {
    @Test fun iPhoneSizeKeepsTransportAndActionRowsInsideTheViewport() = runComposeUiTest {
        renderSimpMusic(widthDp = 393f, heightDp = 852f, insets = SafeAreaPadding(top = 59.0, bottom = 34.0))
        assertControlsInsideViewport()
    }

    @Test fun smallPhoneKeepsTransportAndActionRowsInsideTheViewport() = runComposeUiTest {
        renderSimpMusic(widthDp = 360f, heightDp = 640f, insets = SafeAreaPadding.None)
        assertControlsInsideViewport()
    }

    @Test fun phonePortraitHasOneCollapseControl() = runComposeUiTest {
        renderSimpMusic(widthDp = 393f, heightDp = 852f, insets = SafeAreaPadding(top = 59.0, bottom = 34.0))
        // The shared close control is the only collapse. The style must not add a second chevron to its top row.
        onAllNodesWithContentDescription("Collapse player").assertCountEquals(0)
        onAllNodesWithContentDescription("Back to player").assertCountEquals(1)
    }

    private fun ComposeUiTest.renderSimpMusic(widthDp: Float, heightDp: Float, insets: SafeAreaPadding) {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f), LocalHostSafeArea provides insets) {
                MaterialTheme {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.requiredSize(widthDp.dp, heightDp.dp).testTag("simpmusic-window")) {
                            BottomSheetPlayer(playerFixture().copy(formatLabel = "Lossless", formatLossless = true),
                                PlayerTestActions().callbacks,
                                PreferenceSnapshot.of(PlayerDesignStyleKey to PlayerDesignStyle.SIMPMUSIC.name), playerSlots(), true)
                        }
                    }
                }
            }
        }
    }

    private fun ComposeUiTest.assertControlsInsideViewport() {
        val window = onNodeWithTag("simpmusic-window").fetchSemanticsNode().layoutBounds()
        val viewport = onNodeWithTag("simpmusic-content").fetchSemanticsNode().layoutBounds()
        assertTrue(viewport.within(window), "player viewport $viewport is outside the window $window")
        // The sleeve has to survive the fit: a clamp that collapsed it to nothing would still pass the row checks.
        val sleeve = onNodeWithTag("horizontal-artwork-pager").fetchSemanticsNode().layoutBounds()
        assertTrue(kotlin.math.abs(sleeve.width - sleeve.height) <= 1f && sleeve.height >= 100f, "sleeve $sleeve is not a usable square")
        assertTrue(sleeve.within(viewport), "sleeve $sleeve is outside the viewport $viewport")
        // The transport row, then the bottom action row. "More" also sits in the top row, so every match is checked.
        val labels = listOf("Shuffle", "Previous track", "Pause", "Next track", "Repeat", "Lyrics", "Queue", "Sleep timer", "More")
        labels.forEach { label ->
            val nodes = onAllNodes(hasContentDescription(label) and hasAnyAncestor(hasTestTag("simpmusic-content")))
                .fetchSemanticsNodes()
            assertTrue(nodes.isNotEmpty(), "no control labelled $label inside the player")
            nodes.forEach { node ->
                val bounds = node.layoutBounds()
                assertTrue(bounds.within(viewport) && bounds.within(window),
                    "$label $bounds is not fully inside the viewport $viewport of the window $window")
            }
        }
    }

    // boundsInRoot is clipped by the scrolling column, so a control cut off there reports only its visible part, and one
    // cut off entirely reports nothing. The layout position and size give the unclipped rectangle that has to fit.
    private fun SemanticsNode.layoutBounds(): Rect = Rect(positionInRoot, Size(size.width.toFloat(), size.height.toFloat()))

    private fun Rect.within(outer: Rect): Boolean =
        left >= outer.left && top >= outer.top && right <= outer.right && bottom <= outer.bottom
}
