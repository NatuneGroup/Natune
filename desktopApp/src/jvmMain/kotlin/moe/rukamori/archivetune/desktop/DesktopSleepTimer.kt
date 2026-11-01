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
import moe.rukamori.archivetune.shared.ui.menu.playerqueue.PlayerQueueClock
import moe.rukamori.archivetune.shared.ui.menu.playerqueue.PlayerSleepTimerSheet
import moe.rukamori.archivetune.shared.ui.menu.playerqueue.SharedSleepTimer

/** Whether the player's sleep button has the timer sheet open. One per window. */
internal class DesktopSleepTimerSheetState {
    var open: Boolean by mutableStateOf(false)
}

private val DesktopSleepClock = PlayerQueueClock { System.currentTimeMillis() }

/** The sleep timer sheet over the window, opened by the player's sleep button or a menu row. */
@Composable
internal fun DesktopSleepTimerOverlay(sheet: DesktopSleepTimerSheetState, timer: SharedSleepTimer) {
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
