package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.stringResource

enum class LastFmAuthorizationPhase { IDLE, BUSY, WAITING, FAILED }

/** The browser grant's app-content prompt is shared; the host owns the browser and authorization attempt. */
@Composable
fun LastFmAuthorizationPrompt(
    phase: LastFmAuthorizationPhase,
    refused: Boolean,
    notConfigured: Boolean,
    finish: () -> Unit,
    reopen: () -> Unit,
    cancel: () -> Unit,
) {
    if (phase == LastFmAuthorizationPhase.IDLE) return
    Card(Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (phase) {
                LastFmAuthorizationPhase.IDLE -> Unit
                LastFmAuthorizationPhase.BUSY -> {
                    CircularProgressIndicator()
                    Text(stringResource(Res.string.ios_services_lastfm_contacting), style = MaterialTheme.typography.bodyMedium)
                }
                LastFmAuthorizationPhase.WAITING -> {
                    Text(stringResource(Res.string.ios_services_lastfm_waiting_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(Res.string.ios_services_lastfm_waiting_description), style = MaterialTheme.typography.bodyMedium)
                    if (refused) Text(stringResource(Res.string.ios_services_lastfm_not_approved), color = MaterialTheme.colorScheme.error)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = finish) { Text(stringResource(Res.string.ios_services_lastfm_finish)) }
                        TextButton(onClick = reopen) { Text(stringResource(Res.string.ios_services_lastfm_open_again)) }
                        TextButton(onClick = cancel) { Text(stringResource(Res.string.ios_services_lastfm_cancel)) }
                    }
                }
                LastFmAuthorizationPhase.FAILED -> {
                    Text(stringResource(if (notConfigured) Res.string.ios_services_lastfm_not_configured else Res.string.ios_services_lastfm_sign_in_failed), color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = cancel) { Text(stringResource(Res.string.ios_services_lastfm_dismiss)) }
                }
            }
        }
    }
}
