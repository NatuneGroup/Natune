package moe.rukamori.archivetune.shared.ui.shortcuts

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.ui.playerShortcuts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The editing counter every unmodified shortcut is gated on. It has to come back down when focus leaves the field: a
 * counter that only ever rises silences Q, L, T and Space for the rest of the session after one visit to a text field,
 * which is what the desktop shell's search pill did.
 */
@OptIn(ExperimentalTestApi::class)
class ShortcutTextInputTest {
    @Test
    fun aTrackedFieldReleasesTheShortcutTableWhenFocusLeaves() = runComposeUiTest {
        val counter = ShortcutTextInputState()
        var typed by mutableStateOf("")
        var plainKeys = 0
        var focusManager: FocusManager? = null
        setContent {
            val manager = LocalFocusManager.current
            focusManager = manager
            MaterialTheme {
                CompositionLocalProvider(LocalShortcutTextInput provides counter) {
                    Box(
                        Modifier.requiredSize(360.dp, 200.dp)
                            .playerShortcuts(textInputActive = { counter.active }) { plainKeys++; true },
                    ) {
                        BasicTextField(
                            typed,
                            { typed = it },
                            Modifier.requiredSize(320.dp, 40.dp).testTag("tracked").shortcutTextInput(),
                        )
                        BasicTextField(
                            typed,
                            { typed = it },
                            Modifier.requiredSize(320.dp, 40.dp).testTag("plain"),
                        )
                    }
                }
            }
        }

        onNodeWithTag("tracked").requestFocus()
        runOnIdle { assertTrue(counter.active, "a focused tracked field is an editing surface") }
        onNodeWithTag("tracked").performKeyInput { keyDown(Key.Q); keyUp(Key.Q) }
        runOnIdle { assertEquals(0, plainKeys, "the focused field keeps typing instead of toggling the queue") }

        runOnIdle { focusManager?.clearFocus() }
        runOnIdle { assertFalse(counter.active, "clearing focus returns the unmodified shortcuts to the table") }

        // Keys reach the window root through any focused node; an untracked field stands in for one.
        onNodeWithTag("plain").requestFocus()
        onNodeWithTag("plain").performKeyInput { keyDown(Key.Q); keyUp(Key.Q) }
        runOnIdle { assertEquals(1, plainKeys, "the plain chord reaches the table once no text field holds focus") }
    }

    @Test
    fun focusMovingBetweenTrackedFieldsCountsOnce() = runComposeUiTest {
        val counter = ShortcutTextInputState()
        var typed by mutableStateOf("")
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalShortcutTextInput provides counter) {
                    Box(Modifier.requiredSize(360.dp, 200.dp)) {
                        BasicTextField(
                            typed,
                            { typed = it },
                            Modifier.requiredSize(320.dp, 40.dp).testTag("first").shortcutTextInput(),
                        )
                        BasicTextField(
                            typed,
                            { typed = it },
                            Modifier.requiredSize(320.dp, 40.dp).testTag("second").shortcutTextInput(),
                        )
                    }
                }
            }
        }

        onNodeWithTag("first").requestFocus()
        runOnIdle { assertTrue(counter.active) }
        onNodeWithTag("second").requestFocus()
        runOnIdle { assertTrue(counter.active, "the other field is an editing surface too") }
        onNodeWithTag("first").requestFocus()
        runOnIdle { assertTrue(counter.active) }
    }
}
