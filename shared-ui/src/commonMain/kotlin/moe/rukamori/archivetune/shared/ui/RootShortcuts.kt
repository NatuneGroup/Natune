package moe.rukamori.archivetune.shared.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import moe.rukamori.archivetune.shared.ui.player.KeymapAction
import moe.rukamori.archivetune.shared.ui.shortcuts.KeyboardShortcuts

/**
 * The window's keyboard handling: [KeyboardShortcuts] resolves every chord and [dispatch] performs it.
 *
 * Space is claimed in the preview pass, before a focused non-text control sees it, because a pressable control
 * activates on the Space key-up. Without that, a focused mini-player strip or card took the documented play/pause key
 * as its own activation — after Escape closed the player sheet, focus stayed on the strip and Space re-opened the
 * sheet instead of pausing. Both edges are consumed for the same reason: letting the release through would still press
 * the control. Enter is never claimed, so the focused control stays keyboard-activatable for accessibility, and every
 * other chord still waits for unconsumed presses so a focused field or slider keeps its keys.
 *
 * [textInputActive] is the tracked editing counter ([moe.rukamori.archivetune.shared.ui.shortcuts.ShortcutTextInputState]);
 * while it is set the table drops the unmodified chords itself, Space included, so a tracked field types its space and
 * the preview never takes it.
 */
fun Modifier.playerShortcuts(
    textInputActive: () -> Boolean,
    dispatch: (KeymapAction) -> Boolean,
): Modifier = this
    .onPreviewKeyEvent { event ->
        if (textInputActive() || event.key != Key.Spacebar) return@onPreviewKeyEvent false
        val action = KeyboardShortcuts.actionFor(
            key = event.key,
            ctrl = event.isCtrlPressed,
            meta = event.isMetaPressed,
            alt = event.isAltPressed,
            shift = event.isShiftPressed,
            textInputFocused = false,
        )
        if (action != KeymapAction.TogglePlayback) return@onPreviewKeyEvent false
        when (event.type) {
            KeyEventType.KeyDown -> {
                dispatch(KeymapAction.TogglePlayback)
                true
            }
            KeyEventType.KeyUp -> true
            else -> false
        }
    }
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
