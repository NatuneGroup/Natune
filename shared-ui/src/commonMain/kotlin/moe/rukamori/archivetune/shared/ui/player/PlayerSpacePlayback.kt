package moe.rukamori.archivetune.shared.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * Space plays or pauses instead of activating the expansion surface this modifier sits on, but only while that surface
 * itself holds focus. A pressable control activates on the Space key-up, so the press is claimed in this surface's
 * preview pass and both edges are consumed; the play/pause runs once on the press. Without it a focused mini-player
 * strip took the documented play/pause key as its own click and re-opened the player sheet after Escape returned focus
 * to the strip.
 *
 * The gate is the surface's own [onFocusChanged] state, so a focused control inside the strip — next, like, the wide
 * bar's queue and lyrics, the seek bar — leaves it false and keeps the keyboard activation its own control gives it. A
 * preview handler on an ancestor strip would instead run before every one of those controls. Enter is untouched, so the
 * focused surface stays keyboard-activatable for accessibility. A text field never carries this modifier, and it is
 * applied per surface rather than at the window root, so a field outside the player keeps typing its space before the
 * root's bubbling handler ever sees the key.
 *
 * [onFocusChanged] has to stay before the caller's `.clickable`: it observes the focus target that clickable installs.
 */
@Composable
fun Modifier.spaceTogglesPlayback(onTogglePlayback: () -> Unit): Modifier {
    val surfaceFocused = remember { mutableStateOf(false) }
    return this
        .onFocusChanged { surfaceFocused.value = it.isFocused }
        .onPreviewKeyEvent { event ->
            if (!surfaceFocused.value) return@onPreviewKeyEvent false
            if (event.key != Key.Spacebar) return@onPreviewKeyEvent false
            if (event.isCtrlPressed || event.isMetaPressed || event.isAltPressed || event.isShiftPressed) {
                return@onPreviewKeyEvent false
            }
            when (event.type) {
                KeyEventType.KeyDown -> {
                    onTogglePlayback()
                    true
                }
                KeyEventType.KeyUp -> true
                else -> false
            }
        }
}
