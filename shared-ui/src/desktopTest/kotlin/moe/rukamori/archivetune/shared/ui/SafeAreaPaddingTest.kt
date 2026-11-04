package moe.rukamori.archivetune.shared.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The window's safe area is physical: UIKit reports the notch and the home indicator as left/right points, so the
 * composer must reserve those exact sides even when the interface is mirrored. These assertions measure the layout, so
 * swapping to direction-relative start/end fails here rather than on a phone.
 */
@OptIn(ExperimentalTestApi::class)
class SafeAreaPaddingTest {
    private val asymmetric = SafeAreaPadding(left = 44.0, top = 10.0, right = 4.0, bottom = 20.0)

    @Test fun reservesEachPhysicalEdgeUnderLeftToRight() = assertEdges(LayoutDirection.Ltr)

    @Test fun reservesEachPhysicalEdgeUnderRightToLeft() = assertEdges(LayoutDirection.Rtl)

    @Test fun anEmptySafeAreaChangesNothing() = runComposeUiTest {
        setContent { Box(Modifier.size(400.dp, 400.dp)) { Box(Modifier.fillMaxSize().safeAreaPadding(SafeAreaPadding.None).testTag("content")) } }
        val root = onRoot().fetchSemanticsNode().boundsInRoot
        assertEquals(root.width, onNodeWithTag("content").fetchSemanticsNode().boundsInRoot.width)
        assertEquals(root.height, onNodeWithTag("content").fetchSemanticsNode().boundsInRoot.height)
    }

    private fun assertEdges(direction: LayoutDirection) = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                Box(Modifier.size(400.dp, 400.dp)) {
                    Box(Modifier.fillMaxSize().safeAreaPadding(asymmetric).testTag("content"))
                }
            }
        }
        val outer = onRoot().fetchSemanticsNode().boundsInRoot
        val content = onNodeWithTag("content").fetchSemanticsNode().boundsInRoot
        assertEquals(44.0f, content.left.value, 0.5f)
        assertEquals(10.0f, content.top.value, 0.5f)
        assertEquals(outer.right.value - 4.0f, content.right.value, 0.5f)
        assertEquals(outer.bottom.value - 20.0f, content.bottom.value, 0.5f)
    }
}
