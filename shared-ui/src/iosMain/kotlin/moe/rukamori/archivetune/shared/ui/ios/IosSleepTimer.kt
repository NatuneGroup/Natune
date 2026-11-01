package moe.rukamori.archivetune.shared.ui.ios

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
import moe.rukamori.archivetune.shared.data.platform.ios.iosNowMillis
import moe.rukamori.archivetune.shared.ui.menu.playerqueue.PlayerQueueClock
import moe.rukamori.archivetune.shared.ui.menu.playerqueue.PlayerSleepTimerSheet
import moe.rukamori.archivetune.shared.ui.menu.playerqueue.SharedSleepTimer

/** Whether the player's sleep button or a menu row has the timer sheet open. One per host root. */
internal class IosSleepTimerSheetState {
    var open: Boolean by mutableStateOf(false)
}

private val IosSleepClock = PlayerQueueClock { iosNowMillis() }

/** The shared sleep timer sheet over the root, opened by the player's sleep button or the song menu's timer row. */
@Composable
internal fun IosSleepTimerOverlay(sheet: IosSleepTimerSheetState, timer: SharedSleepTimer) {
    if (!sheet.open) return
    val state by timer.state.collectAsState()
    Dialog(onDismissRequest = { sheet.open = false }) {
        PlayerSleepTimerSheet(
            state = state,
            clock = IosSleepClock,
            commands = timer,
            onDismiss = { sheet.open = false },
            modifier = Modifier.widthIn(max = 480.dp).padding(16.dp),
        )
    }
}
