package moe.rukamori.archivetune.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.ui.player.ArchiveMiniPlayer
import moe.rukamori.archivetune.shared.ui.player.KeymapAction
import moe.rukamori.archivetune.shared.ui.player.PlayerNativeState
import moe.rukamori.archivetune.shared.ui.player.PlayerRepeat
import moe.rukamori.archivetune.shared.ui.player.playerFixture
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Space keeps its documented play/pause meaning wherever focus sits. After Escape closes the player sheet, focus stays
 * on the mini-player strip, where Space used to activate the strip and reopen the sheet instead of pausing.
 */
@OptIn(ExperimentalTestApi::class)
class PlayerShortcutsTest {
    private val occurrence = ContentOccurrence(
        "track@1",
        ContentItem(ContentRef("youtube", ContentKind.SONG, "track"), "Test song", "Test artist"),
    )

    private fun nativeState() = PlayerNativeState(
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

    @Test
    fun spaceOnTheFocusedMiniPlayerStripTogglesPlaybackInsteadOfOpeningTheSheet() = runComposeUiTest {
        var toggles = 0
        var opens = 0
        setContent {
            MaterialTheme {
                Box(
                    Modifier.requiredSize(420.dp, 96.dp).playerShortcuts(textInputActive = { false }) { action ->
                        if (action == KeymapAction.TogglePlayback) toggles++
                        true
                    },
                ) {
                    ArchiveMiniPlayer(
                        current = occurrence,
                        playback = playerFixture().playback,
                        nativeState = nativeState(),
                        artwork = { _, modifier -> Box(modifier.background(Color.Cyan)) },
                        motion = false,
                        onExpand = { opens++ },
                        onOpenPanel = {},
                        onPlayPause = {},
                        onSeekTo = {},
                        onAction = {},
                        onVolumeChange = {},
                    )
                }
            }
        }
        onNodeWithTag("compact-mini-player").requestFocus()
        onNodeWithTag("compact-mini-player").performKeyInput {
            keyDown(Key.Spacebar)
            keyUp(Key.Spacebar)
        }
        runOnIdle {
            assertEquals(1, toggles, "Space on the focused strip plays or pauses")
            assertEquals(0, opens, "Space must not activate the strip and open the sheet")
        }
    }

    @Test
    fun enterStillActivatesTheFocusedControl() = runComposeUiTest {
        var toggles = 0
        var opens = 0
        setContent {
            MaterialTheme {
                Box(
                    Modifier.requiredSize(420.dp, 96.dp).playerShortcuts(textInputActive = { false }) { action ->
                        if (action == KeymapAction.TogglePlayback) toggles++
                        true
                    },
                ) {
                    ArchiveMiniPlayer(
                        current = occurrence,
                        playback = playerFixture().playback,
                        nativeState = nativeState(),
                        artwork = { _, modifier -> Box(modifier.background(Color.Cyan)) },
                        motion = false,
                        onExpand = { opens++ },
                        onOpenPanel = {},
                        onPlayPause = {},
                        onSeekTo = {},
                        onAction = {},
                        onVolumeChange = {},
                    )
                }
            }
        }
        onNodeWithTag("compact-mini-player").requestFocus()
        onNodeWithTag("compact-mini-player").performKeyInput {
            keyDown(Key.Enter)
            keyUp(Key.Enter)
        }
        runOnIdle {
            assertEquals(1, opens, "Enter stays the keyboard activation of the focused control")
            assertEquals(0, toggles, "Enter must not toggle playback")
        }
    }

    @Test
    fun aTrackedTextInputKeepsItsSpace() = runComposeUiTest {
        var toggles = 0
        var typed by mutableStateOf("")
        setContent {
            MaterialTheme {
                Box(
                    Modifier.requiredSize(320.dp, 96.dp).playerShortcuts(textInputActive = { true }) {
                        toggles++
                        true
                    },
                ) {
                    BasicTextField(typed, { typed = it }, Modifier.fillMaxSize().testTag("field"))
                }
            }
        }
        onNodeWithTag("field").requestFocus()
        onNodeWithTag("field").performKeyInput {
            keyDown(Key.Spacebar)
            keyUp(Key.Spacebar)
        }
        runOnIdle { assertEquals(0, toggles, "an editing surface keeps its space") }
    }
}
