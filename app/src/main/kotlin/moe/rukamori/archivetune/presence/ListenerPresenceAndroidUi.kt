package moe.rukamori.archivetune.presence

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.presence.ListenerPresenceState
import moe.rukamori.archivetune.shared.ui.presence.ListenerPresenceSection

/**
 * The listener-presence switch bound to the Android opt-in store. Onboarding uses it without the reset action. Settings
 * passes [showReset] to add the reset action.
 */
@Composable
fun ListenerPresenceAndroidSection(
    modifier: Modifier = Modifier,
    showReset: Boolean = false,
) {
    val context = LocalContext.current.applicationContext
    val store = remember(context) { ListenerPresenceAndroid.store(context) }
    val state by store.state.collectAsState(initial = ListenerPresenceState.Off)
    val scope = rememberCoroutineScope()
    val onReset: () -> Unit = { scope.launch { store.resetId() } }
    ListenerPresenceSection(
        enabled = state.active,
        onEnabledChange = { on -> scope.launch { if (on) store.enable() else store.disable() } },
        modifier = modifier,
        onResetId = if (showReset) onReset else null,
    )
}
