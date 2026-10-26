package moe.rukamori.archivetune.shared.ui.presence

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.ui.resources.Res
import org.jetbrains.compose.resources.stringResource

/** Test tag of the listener-presence switch, so UI tests and accessibility tooling can address it. */
const val LISTENER_PRESENCE_SWITCH_TAG: String = "listener-presence-switch"

/**
 * The listener-presence opt-in, shared by the first-run Welcome page and Settings > Privacy. It is off until the user
 * switches it on, and the disclosure stays one tap away. [onResetId] is passed only where an identifier can exist, and
 * it adds the reset action. [controlsEnabled] is false while a change is being saved.
 */
@Composable
fun ListenerPresenceSection(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onResetId: (() -> Unit)? = null,
    controlsEnabled: Boolean = true,
) {
    var showDetails by rememberSaveable { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(Res.string.presence_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(Res.string.presence_summary), style = MaterialTheme.typography.bodyMedium)
            }
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChange,
                enabled = controlsEnabled,
                modifier = Modifier.testTag(LISTENER_PRESENCE_SWITCH_TAG),
            )
        }
        TextButton(onClick = { showDetails = !showDetails }) {
            Text(stringResource(if (showDetails) Res.string.presence_hide_details else Res.string.presence_show_details))
        }
        if (showDetails) {
            Text(stringResource(Res.string.presence_details), style = MaterialTheme.typography.bodySmall)
        }
        if (enabled && onResetId != null) {
            TextButton(onClick = onResetId, enabled = controlsEnabled) {
                Text(stringResource(Res.string.presence_reset_id))
            }
        }
    }
}
