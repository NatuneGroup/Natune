
package app.natune.ui.player

import kotlin.math.abs

const val AppleMusicLyricsControlsAutoHideDelayMs = 5_000L

fun shouldAutoHideAppleMusicControls(
    lyricsOpen: Boolean,
    queueOpen: Boolean,
    enabled: Boolean,
): Boolean = enabled && (lyricsOpen || queueOpen)

/**
 * Whether the lyrics page may start its idle countdown to tuck the controls away. Paused playback
 * keeps them up (there is nothing to read along to, and play is the next thing wanted), as does
 * anything the user is in the middle of: scrolling the lyrics, scrubbing, an open menu, or an
 * active lyric selection.
 */
fun shouldAutoHideLyricsControls(
    isPlaying: Boolean,
    userScrollingLyrics: Boolean,
    scrubbing: Boolean,
    menuOpen: Boolean,
    selectionActive: Boolean = false,
): Boolean = isPlaying && !userScrollingLyrics && !scrubbing && !menuOpen && !selectionActive

fun isAppleMusicLyricsDismissDrag(
    deltaX: Float,
    deltaY: Float,
    threshold: Float,
): Boolean = deltaY >= threshold && deltaY >= abs(deltaX)
