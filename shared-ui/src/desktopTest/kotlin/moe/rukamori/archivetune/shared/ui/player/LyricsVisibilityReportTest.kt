package moe.rukamori.archivetune.shared.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.ui.host.LocalAndroidPhonePresentation
import kotlin.test.*

/**
 * Wires [PlayerActions.onLyricsVisibility] into the fullscreen switch as ArchiveTuneRoot does. Inline lyrics on the
 * artwork page must not open the full lyrics page for any style or orientation, while the lyrics panel still does.
 */
@OptIn(ExperimentalTestApi::class)
class LyricsVisibilityReportTest {
    @Test fun artworkPageNeverOpensFullscreenLyricsAndTheLyricsPanelStillDoes() = runSkikoComposeUiTest(size = Size(852f, 852f)) {
        var style by mutableStateOf(PlayerDesignStyle.Default)
        var landscape by mutableStateOf(false)
        val interaction = PlayerInteractionState()
        val actions = PlayerTestActions()
        val callbacks = PlayerActions(actions.callbacks.playPause, actions.callbacks.seekTo,
            actions.callbacks.selectOccurrence, actions.callbacks.setVolume, actions.callbacks.onAction,
            { interaction.fullscreenLyrics = it })
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalAndroidPhonePresentation provides true) {
                    Box(Modifier.requiredSize(if (landscape) 852.dp else 393.dp, if (landscape) 393.dp else 852.dp)) {
                        BottomSheetPlayer(playerFixture(), callbacks, PreferenceSnapshot.of(PlayerDesignStyleKey to style.name),
                            playerSlots(), false, interaction = interaction)
                    }
                }
            }
        }
        for (orientation in listOf(false, true)) for (look in PlayerDesignStyle.entries) {
            runOnIdle { landscape = orientation; style = look; interaction.fullscreenLyrics = false; interaction.panel = PlayerPanel.ARTWORK }
            waitForIdle()
            // Flamingo opens its own lyrics page in landscape. That page is requested, so only artwork-page states are checked.
            runOnIdle {
                if (interaction.panel == PlayerPanel.ARTWORK) assertFalse(interaction.fullscreenLyrics, "${look.name} opened fullscreen lyrics, landscape=$orientation")
            }
        }
        // Inline lyrics are mounted on the artwork page while the switch stays off.
        runOnIdle { landscape = false; style = PlayerDesignStyle.SIMPMUSIC; interaction.fullscreenLyrics = false; interaction.panel = PlayerPanel.ARTWORK }
        waitForIdle()
        onNodeWithTag("compact-lyrics-owner-slot").assertExists()
        runOnIdle { assertFalse(interaction.fullscreenLyrics) }
        // The lyrics panel asks for the full page, so the same wiring still switches fullscreen on.
        runOnIdle { interaction.panel = PlayerPanel.LYRICS }
        waitForIdle()
        runOnIdle { assertTrue(interaction.fullscreenLyrics) }
        onNodeWithTag("lyrics-owner-slot").assertExists()
    }
}
