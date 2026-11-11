package moe.rukamori.archivetune.shared.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.ui.host.LocalAndroidPhonePresentation
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class CinematicPhoneControlsTest {
    @Test fun sourceAndObservedFormatRespectTheCodecToggleAndFooterActionsKeepTheirOwners() = runComposeUiTest {
        var codec by mutableStateOf(true)
        val interaction = PlayerInteractionState()
        val actions = PlayerTestActions()
        var expanded by mutableStateOf(true)
        // Routed as the root routes it: an open panel closes first, and only the artwork page collapses the sheet.
        val callbacks = PlayerActions(actions.callbacks.playPause, actions.callbacks.seekTo,
            actions.callbacks.selectOccurrence, actions.callbacks.setVolume, { action ->
            actions.actions += action
            if (action == PlayerAction.COLLAPSE) interaction.backOrCollapse { expanded = false }
        }, actions.callbacks.onLyricsVisibility)
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalAndroidPhonePresentation provides true) {
                    Box(Modifier.requiredSize(393.dp, 760.dp)) {
                        PhonePlayerSheet(expanded, { expanded = it }, false) {
                            BottomSheetPlayer(playerFixture().copy(queueTitle = "Local", formatLabel = "X-WAV • 8.0 kHz • 3.7 MB"),
                                callbacks, PreferenceSnapshot.of(PlayerDesignStyleKey to "V4", ShowCodecOnPlayerKey to codec),
                                playerSlots(), false, interaction = interaction)
                        }
                    }
                }
            }
        }
        onNodeWithText("Now Playing").assertIsDisplayed()
        onNodeWithText("Local").assertIsDisplayed()
        onAllNodesWithTag("player-close").assertCountEquals(1)
        onNodeWithTag("player-close").assertIsDisplayed().assertContentDescriptionEquals("Collapse player")
        onNodeWithTag("player-cinematic-format").performScrollTo().assertIsDisplayed()
        onNodeWithTag("player-cinematic-sleep").performScrollTo().performClick()
        assertEquals(listOf(PlayerAction.SLEEP), actions.actions)
        onNodeWithTag("player-cinematic-queue").performClick()
        runOnIdle { assertEquals(PlayerPanel.QUEUE, interaction.panel); interaction.panel = PlayerPanel.ARTWORK; codec = false }
        onNodeWithTag("player-cinematic-format").assertDoesNotExist()
        onNodeWithTag("player-cinematic-lyrics").performScrollTo().performClick()
        runOnIdle { assertEquals(PlayerPanel.LYRICS, interaction.panel) }
        // With a panel open the control only returns to the artwork, so it must not claim to collapse the player.
        onAllNodesWithTag("player-close").assertCountEquals(1)
        onNodeWithTag("player-close").assertIsDisplayed().assertContentDescriptionEquals("Back to player")
        onNodeWithTag("player-close").performClick()
        runOnIdle { assertEquals(PlayerPanel.ARTWORK, interaction.panel); assertTrue(expanded) }
        onNodeWithTag("phone-player-sheet").assertExists()
        onAllNodesWithTag("player-close").assertCountEquals(1)
        onNodeWithTag("player-close").assertIsDisplayed().assertContentDescriptionEquals("Collapse player")
        onNodeWithTag("player-close").performClick()
        onNodeWithTag("phone-player-sheet").assertDoesNotExist()
        runOnIdle { assertFalse(expanded); assertEquals(PlayerAction.COLLAPSE, actions.actions.last()) }
    }

    @Test fun everyPhoneStyleHasAReachableCollapseInBothOrientations() = runSkikoComposeUiTest(size = Size(852f, 852f)) {
        var style by mutableStateOf(PlayerDesignStyle.Default)
        var landscape by mutableStateOf(false)
        var expanded by mutableStateOf(true)
        val actions = PlayerTestActions()
        val callbacks = PlayerActions(actions.callbacks.playPause, actions.callbacks.seekTo,
            actions.callbacks.selectOccurrence, actions.callbacks.setVolume, { action ->
            actions.actions += action
            if (action == PlayerAction.COLLAPSE) expanded = false
        }, actions.callbacks.onLyricsVisibility)
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalAndroidPhonePresentation provides true) {
                    Box(Modifier.requiredSize(if (landscape) 852.dp else 393.dp, if (landscape) 393.dp else 852.dp)) {
                        PhonePlayerSheet(expanded, { expanded = it }, false) {
                            BottomSheetPlayer(playerFixture(), callbacks, PreferenceSnapshot.of(PlayerDesignStyleKey to style.name),
                                playerSlots(), false)
                        }
                    }
                }
            }
        }
        for (orientation in listOf(false, true)) for (look in PlayerDesignStyle.entries) {
            runOnIdle { landscape = orientation; style = look; expanded = true }
            onAllNodesWithTag("player-close").assertCountEquals(1)
            onNodeWithTag("player-close").assertIsDisplayed().performClick()
            onNodeWithTag("phone-player-sheet").assertDoesNotExist()
            runOnIdle { assertFalse(expanded, "${look.name}, landscape=$orientation"); assertEquals(PlayerAction.COLLAPSE, actions.actions.last()) }
        }
    }
}
