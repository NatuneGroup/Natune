package moe.rukamori.archivetune.playback

import androidx.media3.common.Player
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

/**
 * Lets the service's periodic work sleep through pauses instead of polling while nothing moves.
 *
 * The periodic resume-state save and the Spotify report poll only matter while the playback
 * position advances. [onEvents] mirrors the session player's play state and reports when a change
 * made while paused must be written now (pause, seek, track, queue, repeat, shuffle), so a paused
 * service writes once per change rather than waking every interval to write nothing.
 */
internal class PausedPlaybackGate {
    private val playing = MutableStateFlow(false)

    // Bumped on every sync. A loop asleep while paused wakes to re-check its track, so a track change
    // made while paused still ends that loop at once instead of waiting for the next play.
    private val wakes = MutableStateFlow(0L)

    /**
     * Call when a session player is attached or swapped. Media3 sends no event for a play state that
     * was already in place when the listener was added, so a promoted player that is playing would
     * otherwise still look paused to a waiting loop.
     */
    fun sync(player: Player) {
        playing.value = player.isPlaying
        wakes.update { it + 1 }
    }

    /**
     * Call from `Player.Listener.onEvents`. Returns true when a paused change to the resume state
     * should be written immediately.
     */
    fun onEvents(player: Player, events: Player.Events): Boolean {
        if (!events.isResumeStateChange()) return false
        sync(player)
        return !player.isPlaying
    }

    /**
     * Sleeps for [intervalMs] while playback is advancing. While paused it suspends until the next
     * sync. The mirror is consulted as well as [isPlaying], so a briefly stale value costs one
     * interval and can never turn this into a busy loop.
     */
    suspend fun awaitPlaybackOrDelay(
        isPlaying: Boolean,
        intervalMs: Long,
    ) {
        // Read the counter first: a sync that lands after this read changes it and is never missed.
        val seen = wakes.value
        if (isPlaying || playing.value) {
            delay(intervalMs)
        } else {
            wakes.first { it != seen }
        }
    }

    private fun Player.Events.isResumeStateChange(): Boolean =
        containsAny(
            Player.EVENT_IS_PLAYING_CHANGED,
            Player.EVENT_POSITION_DISCONTINUITY,
            Player.EVENT_MEDIA_ITEM_TRANSITION,
            Player.EVENT_TIMELINE_CHANGED,
            Player.EVENT_REPEAT_MODE_CHANGED,
            Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
        )
}
