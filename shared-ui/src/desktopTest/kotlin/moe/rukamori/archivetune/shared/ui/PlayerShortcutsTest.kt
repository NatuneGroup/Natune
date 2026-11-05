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
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
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
import kotlin.test.assertTrue

/**
 * Space stays the player's play/pause on the player's own strip, where Escape leaves focus, and stays a typed space in
 * a text field that is not tracked by the shortcut table. The window root never claims a key in the preview pass, so
 * the focused field keeps its space; the strip claims Space itself, where the exception is intended.
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
        var plays = 0
        var opens = 0
        var rootToggles = 0
        setContent {
            MaterialTheme {
                Box(
                    Modifier.requiredSize(420.dp, 96.dp).playerShortcuts(textInputActive = { false }) { action ->
                        if (action == KeymapAction.TogglePlayback) rootToggles++
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
                        onPlayPause = { plays++ },
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
            assertEquals(1, plays, "Space on the focused strip plays or pauses")
            assertEquals(0, opens, "Space must not activate the strip and open the sheet")
            assertEquals(0, rootToggles, "the claimed press never reaches the root handler as well")
        }
    }

    @Test
    fun enterStillActivatesTheFocusedStrip() = runComposeUiTest {
        var plays = 0
        var opens = 0
        var rootToggles = 0
        setContent {
            MaterialTheme {
                Box(
                    Modifier.requiredSize(420.dp, 96.dp).playerShortcuts(textInputActive = { false }) { action ->
                        if (action == KeymapAction.TogglePlayback) rootToggles++
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
                        onPlayPause = { plays++ },
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
            assertEquals(1, opens, "Enter stays the keyboard activation of the focused strip")
            assertEquals(0, plays, "Enter must not toggle playback")
            assertEquals(0, rootToggles, "Enter reaches the strip, not the shortcut table")
        }
    }

    @Test
    fun anUntrackedTextFieldKeepsItsSpace() = runComposeUiTest {
        var toggles = 0
        var fieldSawSpace = false
        var typed by mutableStateOf("")
        setContent {
            MaterialTheme {
                Box(
                    Modifier.requiredSize(320.dp, 96.dp).playerShortcuts(textInputActive = { false }) {
                        toggles++
                        true
                    },
                ) {
                    // A plain field that never registers with ShortcutTextInputState, like the settings and dialog
                    // inputs: the window root must not take its space before the field can use it.
                    BasicTextField(
                        typed,
                        { typed = it },
                        Modifier.fillMaxSize().testTag("field").onPreviewKeyEvent { event ->
                            if (event.key == Key.Spacebar) fieldSawSpace = true
                            false
                        },
                    )
                }
            }
        }
        onNodeWithTag("field").requestFocus()
        onNodeWithTag("field").performKeyInput {
            keyDown(Key.Spacebar)
            keyUp(Key.Spacebar)
        }
        runOnIdle {
            assertTrue(fieldSawSpace, "the focused field keeps the Space key")
            assertEquals(0, toggles, "typing a space must not toggle playback")
        }
        onNodeWithTag("field").performTextInput(" ")
        runOnIdle {
            assertTrue(typed.contains(" "), "the typed space lands in the field")
            assertEquals(0, toggles)
        }
    }
}
