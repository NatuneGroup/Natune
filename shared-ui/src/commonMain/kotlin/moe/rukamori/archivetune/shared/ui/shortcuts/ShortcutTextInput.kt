package moe.rukamori.archivetune.shared.ui.shortcuts

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.composed
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
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
 */
fun Modifier.shortcutTextInput(): Modifier = composed {
    val state = LocalShortcutTextInput.current
    var focused by remember { mutableStateOf(false) }
    DisposableEffect(state, focused) {
        if (focused) state.attach()
        onDispose { if (focused) state.detach() }
    }
    onFocusChanged { focused = it.isFocused }
}
