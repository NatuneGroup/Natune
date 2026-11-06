package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * The separate integrations overlay: one modal list over the settings page, so Discord, scrobbling, Listen Together and
 * Device Connect do not take a slot in the settings navigation. Selecting a destination closes the overlay and opens
 * that page; dismissing it leaves the settings page exactly as it was.
 */
@Composable
fun IntegrationsOverlay(
    destinations: List<IntegrationDestination>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.m6_settings_desktop_integrations_title)) },
        text = {
            Column {
                Text(
                    stringResource(Res.string.m6_settings_desktop_integrations_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                LazyColumn(Modifier.heightIn(max = 420.dp).testTag("settings-integrations-list")) {
                    items(destinations, key = { it.route }) { destination ->
                        ListItem(
                            headlineContent = { Text(stringResource(destination.title)) },
                            supportingContent = { Text(stringResource(destination.description)) },
                            modifier = Modifier
                                .testTag("settings-integration-${destination.route}")
                                .clickable {
                                    onDismiss()
                                    onSelect(destination.route)
                                },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.m6_integration_ok)) }
        },
    )
}
