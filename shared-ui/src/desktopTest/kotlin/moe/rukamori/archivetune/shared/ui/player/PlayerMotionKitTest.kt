package moe.rukamori.archivetune.shared.ui.player

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PlayerMotionKitTest {
    @Test fun trackDirectionUsesOccurrencesIncludingDuplicateSongs() {
        val direction = PlayerTrackDirection()
        val order = listOf("same-song@1", "another@1", "same-song@2")
        assertEquals(1, direction.update(order[0], order))
        assertEquals(1, direction.update(order[2], order))
        assertEquals(-1, direction.update(order[1], order))
        assertEquals(-1, direction.update(order[0], order))
        // Repeated progress observations keep the most recent direction.
        assertEquals(-1, direction.update(order[0], order))
    }

    @Test fun sourceReplacementDefaultsToForwardAndRemovedOccurrenceStillHasAnIndex() {
        val direction = PlayerTrackDirection()
        direction.update("b", listOf("a", "b", "c"))
        assertEquals(-1, direction.update("a", listOf("a", "c")))
        assertEquals(1, direction.update("new-source", listOf("new-source")))
    }

    @Test fun inAppSwitchStopsEverythingWhateverThePlatformSays() {
        assertEquals(PlayerMotionLevel.None, playerMotionLevel(animationsAllowed = false, systemReducedMotion = false))
        assertEquals(PlayerMotionLevel.None, playerMotionLevel(animationsAllowed = false, systemReducedMotion = true))
    }

    @Test fun platformReduceMotionKeepsOnlyCrossfades() {
        val level = playerMotionLevel(animationsAllowed = true, systemReducedMotion = true)
        assertEquals(PlayerMotionLevel.Fade, level)
        assertFalse(level.moves)
        assertTrue(level.fades)
    }

    @Test fun fullMotionMovesAndFades() {
        val level = playerMotionLevel(animationsAllowed = true, systemReducedMotion = false)
        assertEquals(PlayerMotionLevel.Full, level)
        assertTrue(level.moves)
        assertTrue(level.fades)
        assertFalse(PlayerMotionLevel.None.moves)
        assertFalse(PlayerMotionLevel.None.fades)
    }

    @Test fun panelsCutWithoutMotionAndOnlyFadeUnderReduceMotion() {
        for (panel in PlayerPanel.entries) {
            assertEquals(EnterTransition.None, playerPanelEnter(panel, PlayerMotionLevel.None))
            assertEquals(ExitTransition.None, playerPanelExit(panel, PlayerMotionLevel.None))
            assertEquals(fadeIn(tween(160)), playerPanelEnter(panel, PlayerMotionLevel.Fade))
            assertEquals(fadeOut(tween(160)), playerPanelExit(panel, PlayerMotionLevel.Fade))
        }
    }

    @Test fun fullMotionPanelsDoMoreThanFade() {
        for (panel in PlayerPanel.entries) {
            val enter = playerPanelEnter(panel, PlayerMotionLevel.Full)
            val exit = playerPanelExit(panel, PlayerMotionLevel.Full)
            assertNotEquals(EnterTransition.None, enter)
            assertNotEquals(ExitTransition.None, exit)
            assertNotEquals(playerPanelEnter(panel, PlayerMotionLevel.Fade), enter)
            assertNotEquals(playerPanelExit(panel, PlayerMotionLevel.Fade), exit)
        }
    }
}
