package moe.rukamori.archivetune.shared.ui.player

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
    data object Like : KeymapAction
    data object Next : KeymapAction
    data object Previous : KeymapAction
    data object Back : KeymapAction
    data object Search : KeymapAction
    data object Home : KeymapAction
    data object Library : KeymapAction
    data object Playlists : KeymapAction
    data object Podcasts : KeymapAction
    data object NewReleases : KeymapAction
    data object Charts : KeymapAction
    data object Settings : KeymapAction
    data object NowPlaying : KeymapAction
    data object Queue : KeymapAction
    data object TogglePlayer : KeymapAction
    data object FullscreenLyrics : KeymapAction
    data object ToggleFullscreen : KeymapAction
    data object Help : KeymapAction
    data class SeekFraction(val fraction: Float) : KeymapAction
}

/** One volume step of the arrow and +/- bindings, the same step Metrolist's desktop uses. */
const val KEYMAP_VOLUME_STEP = 0.1f


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
