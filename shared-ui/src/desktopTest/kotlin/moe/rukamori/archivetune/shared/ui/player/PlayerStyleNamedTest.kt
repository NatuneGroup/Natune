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
import androidx.compose.runtime.setValue

@OptIn(ExperimentalTestApi::class)
class PlayerStyleBitChordTikTokSimpTest {
    @Test fun namedFamiliesRenderBothOrientations() = namedStyleOrientations(
        listOf(PlayerDesignStyle.BITCHORD,PlayerDesignStyle.TIKTOK,PlayerDesignStyle.SIMPMUSIC))

    @Test fun userHorizontalAndVerticalSwipesSelectOnceAndProgrammaticFollowNeverSkips() = runComposeUiTest {
        var style by mutableStateOf(PlayerDesignStyle.SIMPMUSIC)
        var state by mutableStateOf(playerFixture())
        val callbacks=PlayerTestActions()
        setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(400.dp,740.dp)) {
                    BottomSheetPlayer(state,callbacks.callbacks,
                        PreferenceSnapshot.of(PlayerDesignStyleKey to style.name),playerSlots(),true)
                }
            }
        }
        onNodeWithTag("horizontal-artwork-pager").performTouchInput { swipeLeft() }
        waitForIdle()
        runOnIdle { assertEquals(listOf("track@2"),callbacks.selected) }
        runOnIdle { state=state.copy(playback=state.playback!!.copy(occurrenceId="track@2",playbackGeneration=2)) }
        waitForIdle()
        runOnIdle {
            assertEquals(1,callbacks.selected.size)
            state=state.copy(playback=state.playback!!.copy(occurrenceId="track@0",playbackGeneration=3))
            style=PlayerDesignStyle.TIKTOK
        }
        waitForIdle()
        runOnIdle { assertEquals(1,callbacks.selected.size) }
        onNodeWithTag("vertical-artwork-pager").performTouchInput { swipeUp() }
        waitForIdle()
        runOnIdle { assertEquals(listOf("track@2","track@1"),callbacks.selected) }
    }
}

@OptIn(ExperimentalTestApi::class)
class PlayerStyleSpatialLooperFlamingoTest {
    @Test fun distinctNamedLayoutsAndFontFallbackRenderBothOrientations() = namedStyleOrientations(
        listOf(PlayerDesignStyle.SPATIALFLOW,PlayerDesignStyle.LOOPER,PlayerDesignStyle.FLAMINGO))

    @Test fun flamingoPagesBackAndLegacyVolumeArePresentationNotPlaybackMutations() = runComposeUiTest {
        val interaction=PlayerInteractionState()
        val callbacks=PlayerTestActions()
        setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(400.dp,740.dp)) {
                    BottomSheetPlayer(playerFixture(),callbacks.callbacks,
                        PreferenceSnapshot.of(PlayerDesignStyleKey to "FLAMINGO",
                            ShowPlayerVolumeBarKey to true,FlamingoShowVolumeBarKey to false),
                        playerSlots(),true,interaction=interaction)
                }
            }
        }
        onNodeWithTag("player-volume").assertDoesNotExist()
        onNodeWithText("Queue").performClick()
        onNodeWithTag("queue-owner-slot").assertExists()
        runOnIdle { assertTrue(interaction.back()) }
        onNodeWithTag("player-sleeve").assertExists()
        onNodeWithText("Lyrics").performClick()
        onNodeWithTag("lyrics-owner-slot").assertExists()
        runOnIdle { assertTrue(callbacks.selected.isEmpty()); assertTrue(callbacks.actions.isEmpty()) }
    }
}

@OptIn(ExperimentalTestApi::class)
private fun namedStyleOrientations(styles: List<PlayerDesignStyle>) = runComposeUiTest {
    var style by mutableStateOf(styles.first())
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
    for(landscape in listOf(false,true)) for(design in styles) {
        runOnIdle { style=design; wide=landscape }
        onNodeWithTag("player-${design.name}-${if(landscape) "landscape" else "portrait"}").assertExists()
        when(design) {
            PlayerDesignStyle.SPATIALFLOW -> onNodeWithTag("spatial-font-fallback").assertExists()
            PlayerDesignStyle.TIKTOK -> onNodeWithTag("vertical-artwork-pager").assertExists()
            PlayerDesignStyle.SIMPMUSIC,PlayerDesignStyle.LOOPER -> onNodeWithTag("horizontal-artwork-pager").assertExists()
            PlayerDesignStyle.FLAMINGO -> onNodeWithTag("flamingo-content-pages").assertExists()
            else -> onNodeWithTag("bitchord-content").assertExists()
        }
    }
    runOnIdle { assertTrue(callbacks.selected.isEmpty()) }
}
