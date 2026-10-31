package moe.rukamori.archivetune.playback

import androidx.media3.common.Player

/**
 * Runs a manual skip with the play state the user left. A paused queue loads its new item paused, so buffering
 * cannot start playback on its own. A queue that had already played to its end was not paused by the user, so a skip
 * from there still starts playback. Widget skips use this; the in-app skips in PlayerConnection apply the same rule.
 */
internal inline fun Player.skipKeepingPlayState(seek: Player.() -> Unit) {
    val finishedBeforeSkip = playbackState == Player.STATE_ENDED
    seek()
    prepare()
    if (finishedBeforeSkip) playWhenReady = true
}
