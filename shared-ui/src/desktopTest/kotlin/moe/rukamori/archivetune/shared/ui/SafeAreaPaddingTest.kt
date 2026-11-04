package moe.rukamori.archivetune.shared.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absolutePadding
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The window's safe area is physical: UIKit reports the notch and the home indicator as left/right points, so the
 * composer must reserve those exact sides even when the interface is mirrored. Each case measures the reserved content
 * against the same edges written with [absolutePadding], so a direction-relative start/end implementation fails here
 * rather than on a phone.
 */
@OptIn(ExperimentalTestApi::class)
class SafeAreaPaddingTest {
    private val asymmetric = SafeAreaPadding(left = 44.0, top = 10.0, right = 4.0, bottom = 20.0)

    @Test fun reservesEachPhysicalEdgeUnderLeftToRight() = assertEdges(LayoutDirection.Ltr)

    @Test fun reservesEachPhysicalEdgeUnderRightToLeft() = assertEdges(LayoutDirection.Rtl)

    @Test fun anEmptySafeAreaChangesNothing() = runComposeUiTest {
        setContent {
            Box(Modifier.size(400.dp, 400.dp)) {
                Box(Modifier.fillMaxSize().safeAreaPadding(SafeAreaPadding.None)) {
                    Box(Modifier.fillMaxSize().testTag("content"))
                }
            }
        }
        val outer = onRoot().fetchSemanticsNode().boundsInRoot
        val content = onNodeWithTag("content").fetchSemanticsNode().boundsInRoot
        assertEquals(outer.left, content.left)
        assertEquals(outer.top, content.top)
        assertEquals(outer.right, content.right)
        assertEquals(outer.bottom, content.bottom)
    }

    /**
     * The gate the other hosts depend on: with no window safe area the modifier must add no padding and never install
     * the bar consumption, so Android and desktop keep their own bar insets.
     */
    @Test fun onlyAHostSuppliedSafeAreaTakesTheBarsOver() {
        assertFalse(SafeAreaPadding.None.needsRootPadding())
        assertFalse(SafeAreaPadding(0.0, 0.0, 0.0, 0.0).needsRootPadding())
        assertTrue(asymmetric.needsRootPadding())
        assertTrue(SafeAreaPadding(top = 1.0).needsRootPadding())
    }

    @Test fun sanitizedClampsNegativeAndNonFiniteEdges() {
        val clamped = SafeAreaPadding(left = -3.0, top = Double.NaN, right = 10_000.0, bottom = 12.0).sanitized()
        assertEquals(SafeAreaPadding(left = 0.0, top = 0.0, right = MAX_SAFE_AREA_POINTS, bottom = 12.0), clamped)
        assertFalse(clamped.copy(left = 0.0, top = 0.0, right = 0.0, bottom = 0.0).needsRootPadding())
    }

    private fun assertEdges(direction: LayoutDirection) = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                Box(Modifier.size(400.dp, 400.dp)) {
                    // The same physical edges, written directly: the reference the owner must match.
                    Box(Modifier.fillMaxSize().absolutePadding(left = 44.dp, top = 10.dp, right = 4.dp, bottom = 20.dp)) {
                        Box(Modifier.fillMaxSize().testTag("reference"))
                    }
                    Box(Modifier.fillMaxSize().safeAreaPadding(asymmetric)) {
                        Box(Modifier.fillMaxSize().testTag("content"))
                    }
                }
            }
        }
        assertEquals(
            onNodeWithTag("reference").fetchSemanticsNode().boundsInRoot,
            onNodeWithTag("content").fetchSemanticsNode().boundsInRoot,
        )
    }
}
