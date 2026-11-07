package moe.rukamori.archivetune.shared.ui.shortcuts

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged

/**
 * How many text inputs currently hold focus. A plain counter, read when a key arrives rather than observed, because the
 * key handler is the only reader and a counter never needs to recompose anything.
 */
class ShortcutTextInputState {
    private var focused = 0

    /** True while at least one tracked text input is being edited. */
    val active: Boolean get() = focused > 0

    fun attach() {
        focused++
    }

    fun detach() {
        if (focused > 0) focused--
    }
}

/** One state per root, so every tracked field in the window reports into the same counter. */
val LocalShortcutTextInput = staticCompositionLocalOf { ShortcutTextInputState() }

/**
 * Marks a text input as an editing surface. While one holds focus, unmodified shortcuts such as Space or a letter stay
 * quiet so typing wins; Escape and every modifier-bearing shortcut keep working.
 *
 * The attachment holds its own flag rather than snapshot state, and it is the same flag the dispose path reads. A
 * `remember { mutableStateOf(false) }` read from inside `onDispose` reads the value *after* the focus change, which is
 * false again, so the release never ran: one visit to a field silenced every unmodified shortcut for the rest of the
 * session.
 */
fun Modifier.shortcutTextInput(): Modifier = composed {
    val counter = LocalShortcutTextInput.current
    val attachment = remember(counter) { TextInputAttachment(counter) }
    DisposableEffect(attachment) { onDispose { attachment.setFocused(false) } }
    onFocusChanged { attachment.setFocused(it.isFocused) }
}

/** Counts one editing surface in or out exactly once per focus transition, however often it is composed. */
private class TextInputAttachment(private val counter: ShortcutTextInputState) {
    private var focused = false

    fun setFocused(value: Boolean) {
        if (value == focused) return
        focused = value
        if (value) counter.attach() else counter.detach()
    }
}
