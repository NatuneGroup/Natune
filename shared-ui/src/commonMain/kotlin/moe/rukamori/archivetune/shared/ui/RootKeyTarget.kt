package moe.rukamori.archivetune.shared.ui

import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester

/**
 * Makes the root Box a focus target, so its key handler sees events when no control owns focus.
 *
 * Key events reach [androidx.compose.ui.input.key.onKeyEvent] only along the focus path, so a host whose root never
 * takes focus never receives Escape or the other hardware shortcuts. The request is repeated per [route], because
 * navigating moves focus wherever the new page composes it. Descendants dispatch first and the event then bubbles up,
 * so a text field, search field or menu that consumes a key still wins. Off by default: Android and iOS keep their
 * system back and their existing focus behaviour.
 */
@Composable
fun Modifier.rootKeyTarget(enabled: Boolean, route: String): Modifier {
    if (!enabled) return this
    val requester = remember { FocusRequester() }
    LaunchedEffect(route) {
        // On the first frame the node can still be detached, and a failed request must not take the window down.
        try {
            requester.requestFocus()
        } catch (_: IllegalStateException) {
        }
    }
    return focusRequester(requester).focusable()
}
