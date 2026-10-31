package moe.rukamori.archivetune.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import moe.rukamori.archivetune.playback.SleepTimer
import moe.rukamori.archivetune.shared.domain.playback.sleepCountdownRemainingMs

/**
 * Remaining time for the queue and player sleep-timer pills. A timed sleep reads the shared countdown from the
 * timer's deadline. An inactive timer has no deadline, so it reads 0 and ends at once. End-of-song sleep has no
 * deadline, so it reads the current song's remaining time from the player once per second.
 *
 * The countdown is collected only while the host is STARTED. A backgrounded app costs nothing, keeps its last value,
 * and restarts the countdown at once on resume.
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
    return countdown.collectAsStateWithLifecycle(initialValue = 0L)
}

private fun endOfSongRemainingMs(playerProvider: () -> Player): Flow<Long> =
    flow {
        while (true) {
            val player = playerProvider()
            emit((player.duration - player.currentPosition).coerceAtLeast(0L))
            delay(1_000L)
        }
    }
