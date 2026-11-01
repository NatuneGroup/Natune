package moe.rukamori.archivetune.shared.ui.menu.playerqueue

import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.playback.PlaybackSnapshot

/**
 * The one sleep timer of a host, as Android's SleepTimer: a duration pauses the one player when it runs out, and the
 * end-of-song choice pauses it when the current song ends or the queue moves to another song. Either trigger clears
 * the timer. Nothing here persists; a restart starts with no timer, as on Android.
 *
 * The owner reads the player through [snapshot] and pauses it through [pause], so it runs on any host's player and a
 * test drives it with a flow and a lambda. [nowEpochMs] is the wall clock the countdown deadline is computed on; the
 * sheet supplies its own [PlayerQueueClock] and never reads this owner for time.
 */
class SharedSleepTimer(
    private val scope: CoroutineScope,
    private val snapshot: StateFlow<PlaybackSnapshot?>,
    private val pause: () -> Unit,
    private val nowEpochMs: () -> Long,
) : PlayerSleepTimerCommands {
    private val published = MutableStateFlow(INACTIVE)
    val state: StateFlow<PlayerSleepTimerState> = published.asStateFlow()
    private var job: Job? = null

    override fun startSleepTimer(minutes: Int) {
        job?.cancel()
        published.value = PlayerSleepTimerState(
            isActive = true,
            pauseWhenSongEnd = false,
            triggerAtEpochMs = nowEpochMs() + minutes.minutes.inWholeMilliseconds,
        )
        job = scope.launch {
            delay(minutes.minutes)
            pauseFromTimer()
        }
    }

    override fun startSleepTimerEndOfSong() {
        job?.cancel()
        published.value = PlayerSleepTimerState(isActive = true, pauseWhenSongEnd = true, triggerAtEpochMs = 0L)
        job = scope.launch {
            // The song that is current now; its end, or any move to another song, is the trigger.
            snapshot
                .map { current -> current?.occurrenceId to (current?.isEnded == true) }
                .distinctUntilChanged()
                .drop(1)
                .first()
            pauseFromTimer()
        }
    }

    override fun clearSleepTimer() {
        job?.cancel()
        job = null
        published.value = INACTIVE
    }

    private fun pauseFromTimer() {
        job = null
        published.value = INACTIVE
        pause()
    }

    private companion object {
        val INACTIVE = PlayerSleepTimerState(isActive = false, pauseWhenSongEnd = false, triggerAtEpochMs = 0L)
    }
}
