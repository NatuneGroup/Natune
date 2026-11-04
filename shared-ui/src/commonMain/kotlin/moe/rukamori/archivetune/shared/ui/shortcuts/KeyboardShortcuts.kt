package moe.rukamori.archivetune.shared.ui.shortcuts

import androidx.compose.ui.input.key.Key
import moe.rukamori.archivetune.shared.ui.player.KeymapAction
import moe.rukamori.archivetune.shared.ui.resources.Res
import org.jetbrains.compose.resources.StringResource

/** Sections of the help overlay; the order is the order they are shown in. */
enum class ShortcutGroup { PLAYBACK, NAVIGATION, VIEW }

/**
 * One key chord. [primary] is the platform's shortcut modifier — Cmd on Apple hardware, Ctrl elsewhere — so a binding
 * is written once and shown correctly on both. Literal [ctrl] and [meta] stay available for the rare chord that needs
 * the other one.
 */
data class ShortcutChord(
    val key: Key,
    val keyLabel: String,
    val primary: Boolean = false,
    val ctrl: Boolean = false,
    val meta: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
) {
    /** Whether the chord holds a modifier other than Shift, which is what keeps it live while the user types. */
    val modified: Boolean get() = primary || ctrl || meta || alt

    /** Exact modifier match: a chord with Alt never answers an event that also holds Ctrl, and the reverse. */
    fun holds(ctrl: Boolean, meta: Boolean, alt: Boolean, shift: Boolean, platform: ShortcutPlatform): Boolean {
        val wantsCtrl = this.ctrl || (primary && platform == ShortcutPlatform.WINDOWS_LINUX)
        val wantsMeta = this.meta || (primary && platform == ShortcutPlatform.MAC)
        return ctrl == wantsCtrl && meta == wantsMeta && alt == this.alt && shift == this.shift
    }

    /** Mac notation runs the glyphs together (⌘K); the other platforms spell the chord out (Ctrl + K). */
    fun displayText(platform: ShortcutPlatform): String {
        val parts = buildList {
            if (ctrl || (primary && platform == ShortcutPlatform.WINDOWS_LINUX)) {
                add(if (platform == ShortcutPlatform.MAC) "⌃" else "Ctrl")
            }
            if (meta || (primary && platform == ShortcutPlatform.MAC)) {
                add(if (platform == ShortcutPlatform.MAC) "⌘" else "Win")
            }
            if (alt) add(if (platform == ShortcutPlatform.MAC) "⌥" else "Alt")
            if (shift) add(if (platform == ShortcutPlatform.MAC) "⇧" else "Shift")
            add(keyLabel)
        }
        return parts.joinToString(if (platform == ShortcutPlatform.MAC) "" else " + ")
    }
}

/** One action and every chord that reaches it, plus the label the help overlay shows. */
data class ShortcutEntry(
    val action: KeymapAction,
    val group: ShortcutGroup,
    val label: StringResource,
    val chords: List<ShortcutChord>,
    /** Escape keeps working while typing; nothing else without a modifier does. */
    val allowedWhileTyping: Boolean = false,
    /** Digit seeks answer a key but are one line in the help overlay, so they are not listed individually. */
    val listed: Boolean = true,
)

/**
 * The one table of keyboard shortcuts. The root key handler matches against it, and the help overlay and the settings
 * entry render it, so a binding cannot exist in one place and be missing from the other.
 */
object KeyboardShortcuts {
    val entries: List<ShortcutEntry> = buildList {
        add(
            ShortcutEntry(
                KeymapAction.TogglePlayback, ShortcutGroup.PLAYBACK, Res.string.shortcut_action_play_pause,
                listOf(ShortcutChord(Key.Spacebar, "Space")),
            ),
        )
        add(
            ShortcutEntry(
                KeymapAction.Next, ShortcutGroup.PLAYBACK, Res.string.shortcut_action_next,
                listOf(
                    ShortcutChord(Key.N, "N", shift = true),
                    ShortcutChord(Key.DirectionRight, "→", primary = true),
                ),
            ),
        )
        add(
            ShortcutEntry(
                KeymapAction.Previous, ShortcutGroup.PLAYBACK, Res.string.shortcut_action_previous,
                listOf(
                    ShortcutChord(Key.P, "P", shift = true),
                    ShortcutChord(Key.DirectionLeft, "←", primary = true),
                ),
            ),
        )
        add(
            ShortcutEntry(
                KeymapAction.SeekBack, ShortcutGroup.PLAYBACK, Res.string.shortcut_action_seek_back,
                listOf(ShortcutChord(Key.DirectionLeft, "←")),
            ),
        )
        add(
            ShortcutEntry(
                KeymapAction.SeekForward, ShortcutGroup.PLAYBACK, Res.string.shortcut_action_seek_forward,
                listOf(ShortcutChord(Key.DirectionRight, "→")),
            ),
        )
        add(
            ShortcutEntry(
                KeymapAction.VolumeUp, ShortcutGroup.PLAYBACK, Res.string.shortcut_action_volume_up,
                listOf(
                    ShortcutChord(Key.DirectionUp, "↑"),
                    ShortcutChord(Key.Equals, "="),
                    ShortcutChord(Key.Plus, "+"),
                    // '+' types as Shift+'=' on common layouts.
                    ShortcutChord(Key.Equals, "+", shift = true),
                    ShortcutChord(Key.DirectionUp, "↑", primary = true),
                ),
            ),
        )
        add(
            ShortcutEntry(
                KeymapAction.VolumeDown, ShortcutGroup.PLAYBACK, Res.string.shortcut_action_volume_down,
                listOf(
                    ShortcutChord(Key.DirectionDown, "↓"),
                    ShortcutChord(Key.Minus, "-"),
                    ShortcutChord(Key.NumPadSubtract, "-"),
                    ShortcutChord(Key.DirectionDown, "↓", primary = true),
                ),
            ),
        )
        add(
            ShortcutEntry(
                KeymapAction.Mute, ShortcutGroup.PLAYBACK, Res.string.shortcut_action_mute,
                listOf(ShortcutChord(Key.M, "M")),
            ),
        )
        add(
            ShortcutEntry(
                KeymapAction.Shuffle, ShortcutGroup.PLAYBACK, Res.string.shortcut_action_shuffle,
                listOf(
                    ShortcutChord(Key.S, "S"),
                    ShortcutChord(Key.S, "S", alt = true),
                    ShortcutChord(Key.S, "S", primary = true),
                ),
            ),
        )
        add(
            ShortcutEntry(
                KeymapAction.Repeat, ShortcutGroup.PLAYBACK, Res.string.shortcut_action_repeat,
                listOf(
                    ShortcutChord(Key.R, "R"),
                    ShortcutChord(Key.R, "R", alt = true),
                    ShortcutChord(Key.R, "R", primary = true),
                ),
            ),
        )
        add(
            ShortcutEntry(
                KeymapAction.Like, ShortcutGroup.PLAYBACK, Res.string.shortcut_action_like,
                listOf(ShortcutChord(Key.B, "B", alt = true, shift = true)),
            ),
        )
        addAll(digitSeekEntries())

        add(
            ShortcutEntry(
                KeymapAction.Search, ShortcutGroup.NAVIGATION, Res.string.shortcut_action_search,
                listOf(
                    ShortcutChord(Key.K, "K", primary = true),
                    ShortcutChord(Key.F, "F", primary = true),
                    ShortcutChord(Key.L, "L", primary = true),
                    ShortcutChord(Key.L, "L", primary = true, shift = true),
                ),
            ),
        )
        add(
            ShortcutEntry(
                KeymapAction.Home, ShortcutGroup.NAVIGATION, Res.string.shortcut_action_home,
                listOf(ShortcutChord(Key.H, "H", alt = true, shift = true)),
            ),
        )
        add(
            ShortcutEntry(
                KeymapAction.Library, ShortcutGroup.NAVIGATION, Res.string.shortcut_action_library,
                listOf(ShortcutChord(Key.Zero, "0", alt = true, shift = true)),
            ),
        )
        add(
            ShortcutEntry(
                KeymapAction.Playlists, ShortcutGroup.NAVIGATION, Res.string.shortcut_action_playlists,
                listOf(ShortcutChord(Key.One, "1", alt = true, shift = true)),
            ),
        )
        add(
            ShortcutEntry(
                KeymapAction.Podcasts, ShortcutGroup.NAVIGATION, Res.string.shortcut_action_podcasts,
                listOf(ShortcutChord(Key.Two, "2", alt = true, shift = true)),
            ),
        )
        add(
            ShortcutEntry(
                KeymapAction.NewReleases, ShortcutGroup.NAVIGATION, Res.string.shortcut_action_new_releases,
                listOf(ShortcutChord(Key.N, "N", alt = true, shift = true)),
            ),
        )
        add(
            ShortcutEntry(
                KeymapAction.Charts, ShortcutGroup.NAVIGATION, Res.string.shortcut_action_charts,
                listOf(ShortcutChord(Key.C, "C", alt = true, shift = true)),
            ),
        )
        add(
            ShortcutEntry(
                KeymapAction.Settings, ShortcutGroup.NAVIGATION, Res.string.shortcut_action_settings,
                listOf(ShortcutChord(Key.Comma, ",", primary = true)),
            ),
        )
        add(
            ShortcutEntry(
                KeymapAction.Back, ShortcutGroup.NAVIGATION, Res.string.shortcut_action_back,
                listOf(ShortcutChord(Key.Escape, "Esc")),
                allowedWhileTyping = true,
            ),
        )

        add(
            ShortcutEntry(
                KeymapAction.NowPlaying, ShortcutGroup.VIEW, Res.string.shortcut_action_now_playing,
                listOf(ShortcutChord(Key.J, "J", alt = true, shift = true)),
            ),
        )
        add(
            ShortcutEntry(
                KeymapAction.Queue, ShortcutGroup.VIEW, Res.string.shortcut_action_queue,
                listOf(ShortcutChord(Key.Q, "Q", alt = true, shift = true)),
            ),
        )
        add(
            ShortcutEntry(
                KeymapAction.TogglePlayer, ShortcutGroup.VIEW, Res.string.shortcut_action_toggle_player,
                listOf(
                    ShortcutChord(Key.T, "T"),
                    ShortcutChord(Key.R, "R", alt = true, shift = true),
                ),
            ),
        )
        add(
            ShortcutEntry(
                KeymapAction.FullscreenLyrics, ShortcutGroup.VIEW, Res.string.shortcut_action_fullscreen_lyrics,
                listOf(ShortcutChord(Key.L, "L")),
            ),
        )
        add(
            ShortcutEntry(
                KeymapAction.ToggleFullscreen, ShortcutGroup.VIEW, Res.string.shortcut_action_window_fullscreen,
                listOf(ShortcutChord(Key.F, "F")),
            ),
        )
        add(
            ShortcutEntry(
                KeymapAction.Help, ShortcutGroup.VIEW, Res.string.shortcut_action_help,
                listOf(
                    ShortcutChord(Key.Slash, "/", primary = true),
                    ShortcutChord(Key.Slash, "?", primary = true, shift = true),
                ),
            ),
        )
    }

    /** The rows the help overlay shows, in registry order. */
    val listed: List<ShortcutEntry> = entries.filter { it.listed && it.chords.isNotEmpty() }

    /**
     * The action [key] reaches under exactly these modifiers, or null. [textInputFocused] silences the unmodified
     * bindings while a tracked field is being edited, so typing a space or a letter never starts playback.
     */
    fun actionFor(
        key: Key,
        ctrl: Boolean,
        meta: Boolean,
        alt: Boolean,
        shift: Boolean,
        platform: ShortcutPlatform = currentShortcutPlatform,
        textInputFocused: Boolean = false,
    ): KeymapAction? {
        for (entry in entries) {
            for (chord in entry.chords) {
                if (chord.key != key || !chord.holds(ctrl, meta, alt, shift, platform)) continue
                if (textInputFocused && !entry.allowedWhileTyping && !chord.modified) continue
                return entry.action
            }
        }
        return null
    }
}

/** Digits seek to tenths of the duration, on the main row and on the numpad. One unlisted entry per digit. */
private fun digitSeekEntries(): List<ShortcutEntry> {
    val pairs = listOf(
        Key.Zero to Key.NumPad0, Key.One to Key.NumPad1, Key.Two to Key.NumPad2, Key.Three to Key.NumPad3,
        Key.Four to Key.NumPad4, Key.Five to Key.NumPad5, Key.Six to Key.NumPad6, Key.Seven to Key.NumPad7,
        Key.Eight to Key.NumPad8, Key.Nine to Key.NumPad9,
    )
    return pairs.mapIndexed { digit, (row, numpad) ->
        val label = digit.toString()
        ShortcutEntry(
            KeymapAction.SeekFraction(digit / 10f),
            ShortcutGroup.PLAYBACK,
            Res.string.shortcut_action_seek_fraction,
            listOf(ShortcutChord(row, label), ShortcutChord(numpad, label)),
            listed = false,
        )
    }
}
