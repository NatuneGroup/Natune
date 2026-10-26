package moe.rukamori.archivetune.desktop

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.ui.menu.playerqueue.PlayerQueueClock
import moe.rukamori.archivetune.shared.ui.menu.playerqueue.PlayerSleepTimerCommands
import moe.rukamori.archivetune.shared.ui.menu.playerqueue.PlayerSleepTimerSheet
import moe.rukamori.archivetune.shared.ui.menu.playerqueue.PlayerSleepTimerState

/**
 * The desktop sleep timer, as Android's SleepTimer: a duration pauses the one player when it runs out, and the
 * end-of-song choice pauses it when the current song ends or the queue moves to another song. Either trigger clears the
 * timer. Nothing here persists; a restart starts with no timer, as on Android.
 */
internal class DesktopSleepTimer(private val graph: DesktopSessionGraph) : PlayerSleepTimerCommands {
    private val published = MutableStateFlow(INACTIVE)
    val state: StateFlow<PlayerSleepTimerState> = published.asStateFlow()
    private var job: Job? = null

    override fun startSleepTimer(minutes: Int) {
        job?.cancel()
        published.value = PlayerSleepTimerState(
            isActive = true,
            pauseWhenSongEnd = false,
            triggerAtEpochMs = System.currentTimeMillis() + minutes.minutes.inWholeMilliseconds,
        )
        job = graph.scope.launch {
            delay(minutes.minutes)
            pauseFromTimer()
        }
    }

    override fun startSleepTimerEndOfSong() {
        job?.cancel()
        published.value = PlayerSleepTimerState(isActive = true, pauseWhenSongEnd = true, triggerAtEpochMs = 0L)
        job = graph.scope.launch {
            // The song that is current now; its end, or any move to another song, is the trigger.
            graph.player.snapshot
                .map { snapshot -> snapshot?.occurrenceId to (snapshot?.isEnded == true) }
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
        graph.player.pause()
    }

    private companion object {
        val INACTIVE = PlayerSleepTimerState(isActive = false, pauseWhenSongEnd = false, triggerAtEpochMs = 0L)
    }
}

/** Whether the player's sleep button has the timer sheet open. One per window. */
internal class DesktopSleepTimerSheetState {
    var open: Boolean by mutableStateOf(false)
}

private val DesktopSleepClock = PlayerQueueClock { System.currentTimeMillis() }

/** The sleep timer sheet over the window, opened by the player's sleep button. */
@Composable
internal fun DesktopSleepTimerOverlay(sheet: DesktopSleepTimerSheetState, timer: DesktopSleepTimer) {
    if (!sheet.open) return
    val state by timer.state.collectAsState()
    Dialog(onDismissRequest = { sheet.open = false }) {
        PlayerSleepTimerSheet(
            state = state,
            clock = DesktopSleepClock,
            commands = timer,
            onDismiss = { sheet.open = false },
            modifier = Modifier.widthIn(max = 480.dp).padding(16.dp),
        )
    }
}
