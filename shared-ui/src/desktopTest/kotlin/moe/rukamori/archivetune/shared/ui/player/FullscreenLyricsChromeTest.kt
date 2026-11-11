package moe.rukamori.archivetune.shared.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.domain.settings.*
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class FullscreenLyricsChromeTest {
    @Test fun movingBlurDriftsInFullscreenLyricsAndStopsWhenAnimationsAreDisabled() = runComposeUiTest {
        mainClock.autoAdvance = false
        val interaction = PlayerInteractionState()
        val actions = PlayerTestActions()
        var disabled by mutableStateOf(false)
        lateinit var artworkCoordinates: LayoutCoordinates
        val fixtureSlots = playerSlots(PlayerLyricsStatus("track@1", false))
        val slots = PlayerSlots(
            artwork = { id, modifier ->
                fixtureSlots.artwork(id, modifier.onGloballyPositioned { artworkCoordinates = it })
            },
            canvas = fixtureSlots.canvas, customBackground = fixtureSlots.customBackground,
            lyrics = fixtureSlots.lyrics, compactLyrics = fixtureSlots.compactLyrics, queue = fixtureSlots.queue,
            lyricsStatus = fixtureSlots.lyricsStatus,
        )
        setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(393.dp, 740.dp)) {
                    BottomSheetPlayer(playerFixture().copy(reducedMotion = false), actions.callbacks,
                        PreferenceSnapshot.of(LyricsBackgroundStyleKey to "MOVING_BLUR", DisableAnimationsKey to disabled),
                        slots, true, interaction = interaction)
                }
            }
        }
        runOnIdle { interaction.panel = PlayerPanel.LYRICS; interaction.fullscreenLyrics = true }
        mainClock.advanceTimeBy(32)
        onNodeWithTag("lyrics-background-MOVING_BLUR").assertIsDisplayed()
        val initial = runOnIdle { artworkCoordinates.localToRoot(Offset.Zero) }
        mainClock.advanceTimeBy(1000)
        waitForIdle()
        val moved = runOnIdle { artworkCoordinates.localToRoot(Offset.Zero) }
        assertNotEquals(initial, moved, "The fullscreen lyrics artwork must drift while motion is enabled")
        runOnIdle { disabled = true }
        mainClock.advanceTimeByFrame()
        waitForIdle()
        val stopped = runOnIdle { artworkCoordinates.localToRoot(Offset.Zero) }
        mainClock.advanceTimeBy(1000)
        waitForIdle()
        assertEquals(stopped, runOnIdle { artworkCoordinates.localToRoot(Offset.Zero) })
    }

    @Test fun controlsDispatchAndSavedLooksKeepTheirLyricsOwner() = runComposeUiTest {
        val callbacks = PlayerTestActions()
        val interaction = PlayerInteractionState().apply { fullscreenLyrics = true; panel = PlayerPanel.LYRICS }
        var background by mutableStateOf(PlayerBackgroundStyle.DEFAULT)
        var volumeVisible by mutableStateOf(true)
        var lyricsBackground by mutableStateOf("DEFAULT")
        var volume = 0f
        val actions = PlayerActions(callbacks.callbacks.playPause, callbacks.callbacks.seekTo, {},
            { volume = it }, callbacks.callbacks.onAction, {})
        setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(393.dp, 740.dp)) {
                    BottomSheetPlayer(playerFixture().copy(formatLabel = "Lossless", formatLossless = true), actions,
                        PreferenceSnapshot.of(PlayerBackgroundStyleKey to background.name, PlayerCustomImageUriKey to "fixture",
                            LyricsBackgroundStyleKey to lyricsBackground, ShowPlayerVolumeBarKey to volumeVisible),
                        playerSlots(PlayerLyricsStatus("track@1", false)), false, interaction = interaction)
                }
            }
        }
        for (look in PlayerBackgroundStyle.entries) {
            runOnIdle { background = look }
            onNodeWithTag("lyrics-owner-slot").assertExists()
            onNodeWithTag("fullscreen-lyrics-transport").assertIsDisplayed()
            onNodeWithTag("fullscreen-lyrics-footer").assertIsDisplayed()
            onNodeWithText("Lossless").assertIsDisplayed()
            onNodeWithText("Lyrics not found").assertIsDisplayed()
            if (look == PlayerBackgroundStyle.CUSTOM) onNodeWithTag("custom-fixture").assertExists()
        }
        runOnIdle { background = PlayerBackgroundStyle.DEFAULT }
        for (mode in listOf("DEFAULT", "FOLLOW_THEME", "COLORING", "MOVING_BLUR")) {
            runOnIdle { lyricsBackground = mode }
            onNodeWithTag("lyrics-background-$mode").assertIsDisplayed()
            onNodeWithTag("lyrics-owner-slot").assertExists()
        }
        onNodeWithContentDescription("Previous track").performClick()
        onNodeWithContentDescription("Pause").performClick()
        onNodeWithContentDescription("Next track").performClick()
        onNodeWithContentDescription("Playback position").performSemanticsAction(SemanticsActions.SetProgress) { it(.5f) }
        onNodeWithContentDescription("Volume").performSemanticsAction(SemanticsActions.SetProgress) { it(.25f) }
        onNodeWithContentDescription("More").performClick()
        runOnIdle {
            assertEquals(listOf(PlayerAction.PREVIOUS, PlayerAction.NEXT, PlayerAction.MORE), callbacks.actions)
            assertEquals(1, callbacks.plays)
            assertEquals(listOf(90000L), callbacks.seeks)
            assertEquals(.25f, volume)
            volumeVisible = false
        }
        onNodeWithTag("fullscreen-lyrics-volume").assertDoesNotExist()
        onNodeWithContentDescription("Like").assertDoesNotExist()
        onNodeWithContentDescription("Back to player").performClick()
        runOnIdle { assertFalse(interaction.fullscreenLyrics); assertEquals(PlayerPanel.LYRICS, interaction.panel) }
    }

    @Test fun savedLyricsModesResolveCustomBackgroundAndKeepFollowThemeExplicit() {
        for (mode in listOf("DEFAULT", "FOLLOW_THEME", "COLORING", "MOVING_BLUR")) {
            val preferences = PreferenceSnapshot.of(LyricsBackgroundStyleKey to mode).playerPreferences()
            assertEquals(mode, preferences.effectiveLyricsBackground())
            assertEquals("CUSTOM", preferences.copy(background = PlayerBackgroundStyle.CUSTOM).effectiveLyricsBackground())
        }
        assertEquals("DEFAULT", PreferenceSnapshot.of(LyricsBackgroundStyleKey to "CUSTOM").playerPreferences().effectiveLyricsBackground())
    }
}
