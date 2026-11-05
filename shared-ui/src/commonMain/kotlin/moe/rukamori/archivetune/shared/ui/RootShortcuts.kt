package moe.rukamori.archivetune.shared.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import moe.rukamori.archivetune.shared.ui.player.KeymapAction
import moe.rukamori.archivetune.shared.ui.shortcuts.KeyboardShortcuts

/**
 * The window's keyboard handling: [KeyboardShortcuts] resolves every chord and [dispatch] performs it.
 *
 * The root only ever sees presses the focused control did not consume. That order is what lets a text field keep its
 * space and letters, so nothing here may claim a key in the preview pass: the one key that must beat a focused control
 * is claimed by that control itself (`Modifier.spaceTogglesPlayback` on the player's own strips), where the exception
 * is visible and text fields are untouched.
 *
 * [textInputActive] is the tracked editing counter ([moe.rukamori.archivetune.shared.ui.shortcuts.ShortcutTextInputState]);
 * while it is set the table drops the unmodified chords itself, Space included, so a tracked field types its space.
 */
fun Modifier.playerShortcuts(
    textInputActive: () -> Boolean,
    dispatch: (KeymapAction) -> Boolean,
): Modifier = this
    .onKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
        val action = KeyboardShortcuts.actionFor(
            key = event.key,
            ctrl = event.isCtrlPressed,
            meta = event.isMetaPressed,
            alt = event.isAltPressed,
            shift = event.isShiftPressed,
            textInputFocused = textInputActive(),
        ) ?: return@onKeyEvent false
        dispatch(action)
    }
