package moe.rukamori.archivetune.shared.ui.player

import androidx.compose.ui.Modifier
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
 * Space plays or pauses instead of activating the focusable control this modifier sits on (or above). A pressable
 * control activates on the Space key-up, so the press is claimed in this surface's preview pass and both edges are
 * consumed; the play/pause runs once on the press. Without it a focused mini-player strip took the documented
 * play/pause key as its own click and re-opened the player sheet after Escape returned focus to the strip.
 *
 * Enter is untouched, so the focused control stays keyboard-activatable for accessibility. A text field never carries
 * this modifier, and it is applied per surface rather than at the window root, so a field outside the player keeps
 * typing its space before the root's bubbling handler ever sees the key.
 */
fun Modifier.spaceTogglesPlayback(onTogglePlayback: () -> Unit): Modifier =
    onPreviewKeyEvent { event ->
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
