package moe.rukamori.archivetune.shared.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.*

/**
 * The key path the root handler needs: with the opt-in, a window that focuses nothing anywhere still receives Escape
 * (this host's back key), and a control that consumes it keeps it.
 */
@OptIn(ExperimentalTestApi::class)
class RootKeyTargetTest {
    @Test fun escapeReachesTheRootHandlerWhenTheRootTakesFocus() = runComposeUiTest {
        var backs = 0
        setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize().rootKeyTarget(enabled = true, route = "home").onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                        backs++
                        true
                    } else {
                        false
                    }
                })
            }
        }
        onRoot().performKeyInput { pressKey(Key.Escape) }
        assertEquals(1, backs)
    }

    @Test fun nothingHoldsFocusWithoutTheOptIn() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize().rootKeyTarget(enabled = false, route = "home").onKeyEvent { false })
            }
        }
        // A key handler only receives events along the focus path, so an unfocused window has no Escape to handle.
        onAllNodes(isFocused()).assertCountEquals(0)
    }

    @Test fun aFocusedChildThatConsumesEscapeStopsTheRoot() = runComposeUiTest {
        var backs = 0
        var childHandled = 0
        val child = FocusRequester()
        setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize().rootKeyTarget(enabled = true, route = "home").onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                        backs++
                        true
                    } else {
                        false
                    }
                }) {
                    Box(
                        Modifier.fillMaxSize().focusRequester(child).focusable().onKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                                childHandled++
                                true
                            } else {
                                false
                            }
                        },
                    )
                }
                // Declared after the root's own request, so the child wins the focus race deterministically.
                LaunchedEffect(Unit) { child.requestFocus() }
            }
        }
        onRoot().performKeyInput { pressKey(Key.Escape) }
        assertEquals(1, childHandled)
        assertEquals(0, backs)
    }
}
