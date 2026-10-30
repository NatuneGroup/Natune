package moe.rukamori.archivetune.ui.player

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerSheetOverlayTest {
    private val collapsed = 100.dp
    private val maxHeight = 800.dp
    private val statusBarTop = 24.dp

    private fun fraction(sheetValue: Float) =
        playerSheetOverlayFraction(sheetValue.dp, collapsed, maxHeight, statusBarTop)

    @Test
    fun sheetWithinEpsilonOfCollapsed_isNotAnOverlay() {
        assertFalse(playerSheetOverlayActive(100.dp, collapsed))
        assertFalse(playerSheetOverlayActive(102.dp, collapsed))
        assertTrue(playerSheetOverlayActive(102.5.dp, collapsed))
        assertEquals(0f, fraction(101f), 0f)
    }

    @Test
    fun fractionStaysZeroUntilTheEdgeEntersTheRampAboveThePillZone() {
        // Pill zone ends 60dp under the status bar (84dp); the ramp starts 96dp below that (180dp).
        assertEquals(0f, fraction(500f), 0f)
        assertEquals(0f, fraction(620f), 0.0001f)
    }

    @Test
    fun fractionRisesLinearlyAcrossTheRampAndSaturates() {
        assertEquals(0.5f, fraction(668f), 0.0001f)
        assertEquals(1f, fraction(716f), 0.0001f)
        assertEquals(1f, fraction(800f), 0f)
    }
}
