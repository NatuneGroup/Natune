package moe.rukamori.archivetune.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.desktop.integration.DesktopStrings
import moe.rukamori.archivetune.shared.ui.settings.LastFmSettingsPage
import moe.rukamori.archivetune.shared.ui.settings.SettingsController

/**
 * The scrobbling settings page with the desktop web sign-in. Connect opens the service's approval page in the browser;
 * the card above the shared page then waits for the user to come back and finish, because a desktop browser cannot
 * hand the approved token back to the app.
 */
@Composable
internal fun DesktopLastFmSettingsPage(
    lastFm: DesktopLastFm,
    controller: SettingsController,
    onFailure: () -> Unit,
    scrollTo: String?,
    report: (String) -> Unit,
    openDashboard: () -> Unit,
) {
    val pending by lastFm.pendingCount.collectAsState()
    val signIn by lastFm.signIn.collectAsState()
    Column(Modifier.fillMaxSize()) {
        SignInCard(signIn, lastFm, report)
        // The baseline opens the dashboard from the profile menu, which this host does not draw.
        OutlinedButton(onClick = openDashboard, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(DesktopStrings["lastfm_dashboard"])
        }
        LastFmSettingsPage(
            controller = controller,
            defaultApiKey = lastFm.defaultApiKey,
            defaultSecret = lastFm.defaultSecret,
            pendingScrobbles = pending,
            onConnect = { provider -> lastFm.beginSignIn(provider) { url -> openDesktopUri(url, report) } },
            // The session lives only in settings, which the page clears after this returns.
            onLogout = { lastFm.cancelSignIn() },
            onFlushPendingScrobbles = lastFm::flushPending,
            onFailure = onFailure,
            scrollTo = scrollTo,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SignInCard(state: DesktopLastFmSignIn, lastFm: DesktopLastFm, report: (String) -> Unit) {
    if (state == DesktopLastFmSignIn.Idle) return
    Card(Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (state) {
                DesktopLastFmSignIn.Idle -> Unit
                DesktopLastFmSignIn.Requesting, DesktopLastFmSignIn.Exchanging -> {
                    CircularProgressIndicator()
                    Text(DesktopStrings["lastfm_contacting"], style = MaterialTheme.typography.bodyMedium)
                }
                is DesktopLastFmSignIn.Waiting -> {
                    Text(DesktopStrings["lastfm_waiting_title"], style = MaterialTheme.typography.titleMedium)
                    Text(DesktopStrings["lastfm_waiting_description"], style = MaterialTheme.typography.bodyMedium)
                    if (state.refused) {
                        Text(
                            DesktopStrings["lastfm_not_approved"],
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = lastFm::finishSignIn) { Text(DesktopStrings["browser_finish"]) }
                        TextButton(onClick = { openDesktopUri(state.url, report) }) {
                            Text(DesktopStrings["lastfm_open_again"])
                        }
                        TextButton(onClick = lastFm::cancelSignIn) { Text(DesktopStrings["cancel"]) }
                    }
                }
                is DesktopLastFmSignIn.Failed -> {
                    Text(
                        DesktopStrings[if (state.notConfigured) "lastfm_not_configured" else "lastfm_sign_in_failed"],
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = lastFm::cancelSignIn) { Text(DesktopStrings["lastfm_dismiss"]) }
                }
            }
        }
    }
}
