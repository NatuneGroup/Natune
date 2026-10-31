package moe.rukamori.archivetune.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.media3.common.Player
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import moe.rukamori.archivetune.playback.SleepTimer
import moe.rukamori.archivetune.shared.domain.playback.sleepCountdownRemainingMs

/**
 * Remaining time for the queue sleep-timer pill. A timed sleep reads the shared countdown from the timer's deadline.
 * End-of-song sleep has no deadline, so it reads the current song's remaining time from the player once per second.
 * Either flow is collected only while the queue is composed, so a hidden queue costs nothing.
 */
@Composable
internal fun rememberQueueSleepTimerRemainingMs(
    sleepTimer: SleepTimer,
    playerProvider: () -> Player,
): State<Long> {
    val endOfSong = sleepTimer.pauseWhenSongEnd
    val deadlineEpochMs = sleepTimer.deadlineEpochMs
    val countdown: Flow<Long> =
        remember(endOfSong, deadlineEpochMs) {
            if (endOfSong) {
                endOfSongRemainingMs(playerProvider)
            } else {
                sleepCountdownRemainingMs(deadlineEpochMs) { System.currentTimeMillis() }
            }
        }
    return countdown.collectAsState(initial = 0L)
}

private fun endOfSongRemainingMs(playerProvider: () -> Player): Flow<Long> =
    flow {
        while (true) {
            val player = playerProvider()
            emit((player.duration - player.currentPosition).coerceAtLeast(0L))
            delay(1_000L)
        }
    }
