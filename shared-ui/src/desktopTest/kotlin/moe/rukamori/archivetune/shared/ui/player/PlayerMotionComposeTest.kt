package moe.rukamori.archivetune.shared.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.ui.desktop.DesktopExpandedPlayerTransition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class PlayerMotionComposeTest {
    @Test fun transportCrossfadeDisplaysPlayPausePlayWithTheSameReplayLayerAndContent() = runComposeUiTest {
        var playing by mutableStateOf(false)
        setContent {
            val content: @Composable (Boolean) -> Unit = remember {
                { glyph -> PlayerPlayPauseGlyph(glyph, Color.White, Modifier.size(48.dp).testTag("transport")) }
            }
            Box(Modifier.size(48.dp).background(Color.Black)) {
                PlayerTransportCrossfade(playing, replay = false, content = content)
            }
        }
        mainClock.autoAdvance = false
        for (target in listOf(false, true, false)) {
            runOnUiThread { playing = target }
            mainClock.advanceTimeBy(1_000)
            val pixels = onNodeWithTag("transport").captureToImage().toPixelMap()
            val centre = pixels[pixels.width / 2, pixels.height / 2].red
            assertTrue(if (target) centre < .1f else centre > .9f,
                "The displayed glyph must be ${if (target) "pause" else "play"}")
            if (target) assertTrue(pixels[pixels.width / 3, pixels.height / 2].red > .9f,
                "Pause must draw its left bar")
        }
    }

    @Test fun geometricMorphSettlesToTheLastVisualStateAfterRapidReversals() = runComposeUiTest {
        var playing by mutableStateOf(false)
        setContent {
            Box(Modifier.size(48.dp).background(Color.Black)) {
                PlayerPlayPauseGlyph(playing, Color.White, Modifier.size(48.dp).testTag("morph"))
            }
        }
        mainClock.autoAdvance = false
        repeat(7) {
            runOnUiThread { playing = !playing }
            mainClock.advanceTimeBy(16)
        }
        mainClock.advanceTimeBy(1_000)
        var pixels = onNodeWithTag("morph").captureToImage().toPixelMap()
        assertTrue(pixels[pixels.width / 2, pixels.height / 2].red < .1f, "Pause must have a gap between its bars")
        assertTrue(pixels[pixels.width / 3, pixels.height / 2].red > .9f, "Pause must draw its left bar")
        runOnUiThread { playing = false }
        mainClock.advanceTimeBy(1_000)
        pixels = onNodeWithTag("morph").captureToImage().toPixelMap()
        assertTrue(pixels[pixels.width / 2, pixels.height / 2].red > .9f, "Play must draw the triangle at its centre")
    }

    private val occurrence = ContentOccurrence(
        "track@1",
        ContentItem(ContentRef("youtube", ContentKind.SONG, "track"), "Test song", "Test artist"),
    )
    private val nativeState = PlayerNativeState(
        occurrenceId = "track@1",
        playbackGeneration = 1L,
        canPrevious = true,
        canNext = true,
        canSeek = true,
        shuffle = false,
        repeat = PlayerRepeat.OFF,
        liked = false,
        volume = 0.75f,
        canvasAvailable = false,
        palette = emptyList(),
    )

    /** Taps play/pause faster than its squash and crossfade can finish, then lets every animation settle. */
    private fun rapidToggles(taps: Int, systemReducedMotion: Boolean, motion: Boolean = true) = runComposeUiTest {
        val base = playerFixture().playback!!
        var playing by mutableStateOf(true)
        var presses = 0
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalSystemReducedMotion provides systemReducedMotion) {
                    Box(Modifier.requiredSize(420.dp, 96.dp)) {
                        ArchiveMiniPlayer(
                            current = occurrence,
                            playback = base.copy(playWhenReady = playing),
                            nativeState = nativeState,
                            artwork = { _, modifier -> Box(modifier.background(Color.Cyan)) },
                            motion = motion,
                            onExpand = {},
                            onOpenPanel = {},
                            onPlayPause = { presses++; playing = !playing },
                            onSeekTo = {},
                            onAction = {},
                            onVolumeChange = {},
                        )
                    }
                }
            }
        }
        onNodeWithTag("mini-player-play-pause").assertContentDescriptionEquals("Pause")
        mainClock.autoAdvance = false
        repeat(taps) {
            onNodeWithTag("mini-player-play-pause").performClick()
            mainClock.advanceTimeBy(16)
        }
        mainClock.advanceTimeBy(2_000)
        assertEquals(taps, presses)
        onNodeWithTag("mini-player-play-pause").assertContentDescriptionEquals(if (taps % 2 == 0) "Pause" else "Play")
    }

    @Test fun rapidPlayPauseTogglesEndOnTheLastStateWithFullMotion() {
        rapidToggles(taps = 7, systemReducedMotion = false)
        rapidToggles(taps = 8, systemReducedMotion = false)
    }

    @Test fun rapidPlayPauseTogglesEndOnTheLastStateUnderReduceMotion() {
        rapidToggles(taps = 5, systemReducedMotion = true)
        rapidToggles(taps = 6, systemReducedMotion = false, motion = false)
    }

    @Test fun panelTogglesEndOnTheLastPanel() = runComposeUiTest {
        var panel by mutableStateOf<PlayerPanel?>(null)
        setContent {
            PlayerPanelTransition(panel, PlayerMotionLevel.Full) {
                Box(Modifier.size(40.dp).testTag("panel-${it.name}"))
            }
        }
        mainClock.autoAdvance = false
        for (next in listOf(PlayerPanel.QUEUE, null, PlayerPanel.LYRICS, null, PlayerPanel.QUEUE)) {
            runOnUiThread { panel = next }
            mainClock.advanceTimeBy(16)
        }
        mainClock.advanceTimeBy(3_000)
        onNodeWithTag("panel-QUEUE").assertExists()
        onNodeWithTag("panel-LYRICS").assertDoesNotExist()
        runOnUiThread { panel = null }
        mainClock.advanceTimeBy(3_000)
        onNodeWithTag("panel-QUEUE").assertDoesNotExist()
    }

    @Test fun expandedPlayerOpenCloseTogglesEndOnTheLastState() = runComposeUiTest {
        var open by mutableStateOf(false)
        setContent {
            DesktopExpandedPlayerTransition(occurrence.takeIf { open }, motion = true) {
                Box(Modifier.size(40.dp).testTag("expanded-${it.occurrenceId}"))
            }
        }
        mainClock.autoAdvance = false
        repeat(5) {
            runOnUiThread { open = !open }
            mainClock.advanceTimeBy(16)
        }
        mainClock.advanceTimeBy(3_000)
        onNodeWithTag("expanded-track@1").assertExists()
        runOnUiThread { open = false }
        mainClock.advanceTimeBy(3_000)
        onNodeWithTag("expanded-track@1").assertDoesNotExist()
    }
}
