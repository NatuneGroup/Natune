package moe.rukamori.archivetune.shared.ui.player

import androidx.compose.ui.input.key.Key
import kotlin.math.roundToLong

/**
 * The action a hardware key resolves to, or null when the key binds nothing. Focused controls see the event
 * first, so a text field keeps its letters and a slider keeps its arrows; only unconsumed presses reach here.
 */
sealed interface KeymapAction {
    data object TogglePlayback : KeymapAction
    data object SeekBack : KeymapAction
    data object SeekForward : KeymapAction
    data object VolumeUp : KeymapAction
    data object VolumeDown : KeymapAction
    data object Mute : KeymapAction
    data object Shuffle : KeymapAction
    data object Repeat : KeymapAction
    data object Next : KeymapAction
    data object Previous : KeymapAction
    data object Back : KeymapAction
    data object Search : KeymapAction
    data object ExpandPlayer : KeymapAction
    data object FullscreenLyrics : KeymapAction
    data object ToggleFullscreen : KeymapAction
    data class SeekFraction(val fraction: Float) : KeymapAction
}

/** One volume step of the arrow and +/- bindings, the same step Metrolist's desktop uses. */
const val KEYMAP_VOLUME_STEP = 0.1f

/**
 * The desktop global-shortcut map: Space toggles, arrows seek and change volume, +/- change volume, M mutes,
 * S shuffles, R repeats, digits seek to tenths, Shift+N/P skip, Escape goes back, Ctrl or Meta+K/F searches,
 * L toggles fullscreen lyrics, T the player and F the window's fullscreen. Alt stays reserved for the host's
 * own bindings (Alt+Left is the window's back gesture), and other Shift combinations bind nothing, so typed
 * capitals never fire a transport action.
 */
fun keymapActionOf(key: Key, ctrlOrMeta: Boolean, alt: Boolean, shift: Boolean): KeymapAction? = when {
    alt -> null
    ctrlOrMeta -> when (key) {
        Key.K, Key.F -> KeymapAction.Search
        else -> null
    }
    shift -> when (key) {
        Key.N -> KeymapAction.Next
        Key.P -> KeymapAction.Previous
        Key.Equals, Key.Plus -> KeymapAction.VolumeUp // '+' is Shift+'=' on common layouts.
        else -> null
    }
    else -> when (key) {
        Key.Spacebar -> KeymapAction.TogglePlayback
        Key.DirectionLeft -> KeymapAction.SeekBack
        Key.DirectionRight -> KeymapAction.SeekForward
        Key.DirectionUp, Key.Plus, Key.Equals -> KeymapAction.VolumeUp
        Key.DirectionDown, Key.Minus, Key.NumPadSubtract -> KeymapAction.VolumeDown
        Key.M -> KeymapAction.Mute
        Key.S -> KeymapAction.Shuffle
        Key.R -> KeymapAction.Repeat
        Key.L -> KeymapAction.FullscreenLyrics
        Key.T -> KeymapAction.ExpandPlayer
        Key.F -> KeymapAction.ToggleFullscreen
        Key.Escape -> KeymapAction.Back
        else -> digitKey(key)?.let { KeymapAction.SeekFraction(it / 10f) }
    }
}

private fun digitKey(key: Key): Int? {
    val code = key.keyCode
    return when {
        code in Key.Zero.keyCode..Key.Nine.keyCode -> (code - Key.Zero.keyCode).toInt()
        code in Key.NumPad0.keyCode..Key.NumPad9.keyCode -> (code - Key.NumPad0.keyCode).toInt()
        else -> null
    }
}

/** The position a digit seeks to: n/10 of the duration, clamped inside it. Not seekable without a duration. */
fun fractionSeekTargetMs(durationMs: Long, fraction: Float): Long? =
    if (durationMs <= 0L || !fraction.isFinite()) null
    else (durationMs.toDouble() * fraction.toDouble()).roundToLong().coerceIn(0L, durationMs)

/** The volume to write and the level to remember for a later restore. */
data class MuteTarget(val volume: Float, val remembered: Float?)

/**
 * Muting remembers the current level and writes zero; a second press at zero restores the remembered level,
 * or full volume when nothing was remembered, as when the player already sat at zero when M was pressed.
 */
fun muteVolumeTarget(current: Float, remembered: Float?): MuteTarget =
    if (current > 0f) MuteTarget(0f, current.coerceIn(0f, 1f))
    else MuteTarget(remembered?.takeIf { it.isFinite() && it > 0f }?.coerceIn(0f, 1f) ?: 1f, null)
