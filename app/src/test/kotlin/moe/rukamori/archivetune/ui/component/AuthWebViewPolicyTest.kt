package moe.rukamori.archivetune.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthWebViewPolicyTest {
    @Test
    fun darkeningOverridesUseWebViewCapabilitiesIndependentlyOfTheOs() {
        for (sdk in listOf(26, 28, 29, 32, 33, 37)) {
            for (algorithmic in listOf(false, true)) {
                for (legacy in listOf(false, true)) {
                    val policy = authWebViewDarkeningPolicy(sdk, algorithmic, legacy)
                    assertEquals(sdk >= 29, policy.disableViewForceDark)
                    assertEquals(algorithmic, policy.disableAlgorithmicDarkening)
                    assertEquals(legacy, policy.disableLegacyForceDark)
                }
            }
        }
    }

    @Test
    fun keyboardShrinkRevealsTheFocusedControl() {
        assertTrue(shouldRevealFocusedAuthInput(400, 800, 400, 420))
    }

    @Test
    fun widthReflowRevealsTheFocusedControl() {
        assertTrue(shouldRevealFocusedAuthInput(800, 400, 400, 800))
    }

    @Test
    fun unchangedOrExpandedViewportDoesNotScrollThePage() {
        assertFalse(shouldRevealFocusedAuthInput(400, 420, 400, 420))
        assertFalse(shouldRevealFocusedAuthInput(400, 420, 400, 800))
    }

    @Test
    fun initialAndEmptyLayoutsDoNotScheduleDomWork() {
        assertFalse(shouldRevealFocusedAuthInput(0, 0, 400, 800))
        assertFalse(shouldRevealFocusedAuthInput(400, 800, 400, 0))
        assertFalse(shouldRevealFocusedAuthInput(400, 800, 0, 420))
        assertFalse(shouldRevealFocusedAuthInput(400, 0, 400, 420))
    }
}
