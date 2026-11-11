package moe.rukamori.archivetune.shared.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
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
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalAndroidPhonePresentation provides true) {
                    Box(Modifier.requiredSize(393.dp, 760.dp)) {
                        BottomSheetPlayer(playerFixture().copy(queueTitle = "Local", formatLabel = "X-WAV • 8.0 kHz • 3.7 MB"),
                            actions.callbacks, PreferenceSnapshot.of(PlayerDesignStyleKey to "V4", ShowCodecOnPlayerKey to codec),
                            playerSlots(), false, interaction = interaction)
                    }
                }
            }
        }
        onNodeWithText("Now Playing").assertIsDisplayed()
        onNodeWithText("Local").assertIsDisplayed()
        onNodeWithTag("player-close").assertDoesNotExist()
        onNodeWithTag("player-cinematic-format").performScrollTo().assertIsDisplayed()
        onNodeWithTag("player-cinematic-sleep").performScrollTo().performClick()
        assertEquals(listOf(PlayerAction.SLEEP), actions.actions)
        onNodeWithTag("player-cinematic-queue").performClick()
        runOnIdle { assertEquals(PlayerPanel.QUEUE, interaction.panel); interaction.panel = PlayerPanel.ARTWORK; codec = false }
        onNodeWithTag("player-cinematic-format").assertDoesNotExist()
        onNodeWithTag("player-cinematic-lyrics").performScrollTo().performClick()
        runOnIdle { assertEquals(PlayerPanel.LYRICS, interaction.panel) }
    }
}
