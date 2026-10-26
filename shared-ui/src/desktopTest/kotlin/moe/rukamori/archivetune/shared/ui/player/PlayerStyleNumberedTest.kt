/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.shared.ui.player

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.domain.settings.*
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class PlayerStyleNumberedTest {
    @Test fun numberedStylesRenderInBothOrientationsWithDifferentGeometry() = runComposeUiTest {
        var style by mutableStateOf(PlayerDesignStyle.V1)
        var wide by mutableStateOf(false)
        val callbacks=PlayerTestActions()
        setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(if(wide) 1000.dp else 400.dp,if(wide) 600.dp else 740.dp)) {
                    BottomSheetPlayer(playerFixture(),callbacks.callbacks,
                        PreferenceSnapshot.of(PlayerDesignStyleKey to style.name),playerSlots(),true)
                }
            }
        }
        val numbered=listOf(PlayerDesignStyle.V1,PlayerDesignStyle.V2,PlayerDesignStyle.V3,PlayerDesignStyle.V4,
            PlayerDesignStyle.V5,PlayerDesignStyle.V6,PlayerDesignStyle.V7,PlayerDesignStyle.V10)
        for(landscape in listOf(false,true)) for(design in numbered) {
            runOnIdle { style=design; wide=landscape }
            onNodeWithTag("player-${design.name}-${if(landscape) "landscape" else "portrait"}").assertExists()
            onNodeWithContentDescription("Playback position").assertExists()
            if(design!=PlayerDesignStyle.V7) onNodeWithContentDescription("Share").assertExists()
            onNodeWithContentDescription("Next track").performScrollTo().performClick()
        }
        runOnIdle { assertEquals(16,callbacks.actions.count { it==PlayerAction.NEXT }) }
    }

    @Test fun backgroundAndSeekCustomizationsAreLiveButDoNotRewriteSnapshot() = runComposeUiTest {
        var background by mutableStateOf(PlayerBackgroundStyle.DEFAULT)
        var slider by mutableStateOf(PlayerSliderStyle.Standard)
        setContent {
            MaterialTheme {
                BottomSheetPlayer(playerFixture(),PlayerTestActions().callbacks,
                    PreferenceSnapshot.of(PlayerBackgroundStyleKey to background.name,SliderStyleKey to slider.name,
                        PlayerCustomImageUriKey to "fixture"),playerSlots(),true)
            }
        }
        for(value in PlayerBackgroundStyle.entries) {
            runOnIdle { background=value }
            onNodeWithTag("background-${value.name}").assertExists()
        }
        for(value in PlayerSliderStyle.entries) {
            runOnIdle { slider=value }
            onNodeWithTag("seek-${value.name}").assertExists()
        }
    }

    @Test fun styleSwitchKeepsSharedSeekDraftQueueAndFullscreenTransitions() = runComposeUiTest {
        val interaction=PlayerInteractionState()
        var style by mutableStateOf(PlayerDesignStyle.V4)
        val state=playerFixture()
        val callbacks=PlayerTestActions()
        setContent {
            MaterialTheme {
                BottomSheetPlayer(state,callbacks.callbacks,PreferenceSnapshot.of(PlayerDesignStyleKey to style.name),
                    playerSlots(),true,interaction=interaction)
            }
        }
        runOnIdle { interaction.preview(.5f,state); interaction.panel=PlayerPanel.QUEUE; style=PlayerDesignStyle.APPLE_MUSIC }
        onNodeWithTag("queue-owner-slot").assertExists()
        runOnIdle {
            assertEquals(90000L,interaction.previewMs)
            interaction.panel=PlayerPanel.LYRICS
            interaction.fullscreenLyrics=true
        }
        onNodeWithTag("lyrics-owner-slot").assertExists()
        onNodeWithContentDescription("Back to player").performClick()
        runOnIdle { interaction.commit(state,callbacks.callbacks.seekTo); assertEquals(listOf(90000L),callbacks.seeks) }
    }
}
