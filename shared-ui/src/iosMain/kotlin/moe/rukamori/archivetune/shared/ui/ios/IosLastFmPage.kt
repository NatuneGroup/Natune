package moe.rukamori.archivetune.shared.ui.ios

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.domain.settings.LastFMUsernameKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.lastFmCanEnableScrobbling
import moe.rukamori.archivetune.shared.domain.settings.lastFmLoggedIn
import moe.rukamori.archivetune.shared.ui.resources.Res
import moe.rukamori.archivetune.shared.ui.resources.ios_services_lastfm_cancel
import moe.rukamori.archivetune.shared.ui.resources.ios_services_lastfm_contacting
import moe.rukamori.archivetune.shared.ui.resources.ios_services_lastfm_dismiss
import moe.rukamori.archivetune.shared.ui.resources.ios_services_lastfm_finish
import moe.rukamori.archivetune.shared.ui.resources.ios_services_lastfm_not_approved
import moe.rukamori.archivetune.shared.ui.resources.ios_services_lastfm_not_configured
import moe.rukamori.archivetune.shared.ui.resources.ios_services_lastfm_open_again
import moe.rukamori.archivetune.shared.ui.resources.ios_services_lastfm_sign_in_failed
import moe.rukamori.archivetune.shared.ui.resources.ios_services_lastfm_status_signed_in
import moe.rukamori.archivetune.shared.ui.resources.ios_services_lastfm_status_signed_out
import moe.rukamori.archivetune.shared.ui.resources.ios_services_lastfm_status_title
import moe.rukamori.archivetune.shared.ui.resources.ios_services_lastfm_waiting_description
import moe.rukamori.archivetune.shared.ui.resources.ios_services_lastfm_waiting_title
import moe.rukamori.archivetune.shared.ui.settings.LastFmSettingsPage
import moe.rukamori.archivetune.shared.ui.settings.SettingsController
import org.jetbrains.compose.resources.stringResource

/**
 * The scrobbling settings page with the iOS web sign-in. Connect asks for a token and opens the service's approval page
 * in the system browser; the card then waits for the user to come back and finish, because the approval page cannot
 * call the app back with a custom scheme. The account status and the pending count sit above the shared rows.
 */
@Composable
internal fun IosLastFmSettingsPage(
    lastFm: IosLastFm,
    controller: SettingsController,
    onFailure: () -> Unit,
    scrollTo: String?,
    report: (String) -> Unit,
) {
    val snapshot by controller.store.snapshots.collectAsState()
    val preferences = snapshot
    if (preferences == null) {
        CircularProgressIndicator()
        return
    }
    val pending by lastFm.pendingCount.collectAsState()
    val signIn by lastFm.signIn.collectAsState()
    Column(Modifier.fillMaxSize()) {
        IosLastFmStatusRow(preferences, lastFm)
        IosLastFmSignInCard(signIn, lastFm, report)
        LastFmSettingsPage(
            controller = controller,
            defaultApiKey = lastFm.defaultApiKey,
            defaultSecret = lastFm.defaultSecret,
            pendingScrobbles = pending,
            onConnect = { provider -> lastFm.beginSignIn(provider) { url -> openIosUri(url, report) } },
            // The session lives only in settings, which the shared page clears after this returns.
            onLogout = { lastFm.cancelSignIn() },
            onFlushPendingScrobbles = { lastFm.flushPending() },
            onFailure = onFailure,
            scrollTo = scrollTo,
            modifier = Modifier.weight(1f),
        )
    }
}

/** The account status: who is signed in, or why this build cannot scrobble yet. The queued plays stay on the shared row. */
@Composable
private fun IosLastFmStatusRow(preferences: PreferenceSnapshot, lastFm: IosLastFm) {
    val loggedIn = preferences.lastFmLoggedIn()
    ListItem(
        headlineContent = { Text(stringResource(Res.string.ios_services_lastfm_status_title)) },
        supportingContent = {
            Text(
                when {
                    loggedIn -> stringResource(
                        Res.string.ios_services_lastfm_status_signed_in,
                        preferences[LastFMUsernameKey].orEmpty(),
                    )
                    !preferences.lastFmCanEnableScrobbling(lastFm.defaultApiKey, lastFm.defaultSecret) ->
                        stringResource(Res.string.ios_services_lastfm_not_configured)
                    else -> stringResource(Res.string.ios_services_lastfm_status_signed_out)
                },
            )
        },
    )
}

@Composable
private fun IosLastFmSignInCard(state: IosLastFmSignIn, lastFm: IosLastFm, report: (String) -> Unit) {
    if (state == IosLastFmSignIn.Idle) return
    Card(Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (state) {
                IosLastFmSignIn.Idle -> Unit
                IosLastFmSignIn.Requesting, IosLastFmSignIn.Exchanging -> {
                    CircularProgressIndicator()
                    Text(
                        stringResource(Res.string.ios_services_lastfm_contacting),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                is IosLastFmSignIn.Waiting -> {
                    Text(
                        stringResource(Res.string.ios_services_lastfm_waiting_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(Res.string.ios_services_lastfm_waiting_description),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (state.refused) {
                        Text(
                            stringResource(Res.string.ios_services_lastfm_not_approved),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = lastFm::finishSignIn) {
                            Text(stringResource(Res.string.ios_services_lastfm_finish))
                        }
                        TextButton(onClick = { openIosUri(state.url, report) }) {
                            Text(stringResource(Res.string.ios_services_lastfm_open_again))
                        }
                        TextButton(onClick = lastFm::cancelSignIn) {
                            Text(stringResource(Res.string.ios_services_lastfm_cancel))
                        }
                    }
                }
                is IosLastFmSignIn.Failed -> {
                    Text(
                        stringResource(
                            if (state.notConfigured) {
                                Res.string.ios_services_lastfm_not_configured
                            } else {
                                Res.string.ios_services_lastfm_sign_in_failed
                            },
                        ),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(onClick = lastFm::cancelSignIn) {
                        Text(stringResource(Res.string.ios_services_lastfm_dismiss))
                    }
                }
            }
        }
    }
}
