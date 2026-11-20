package app.natune.shared.ui.ios

import androidx.compose.foundation.clickable
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
import app.natune.shared.domain.settings.LastFMUsernameKey
import app.natune.shared.domain.settings.PreferenceSnapshot
import app.natune.shared.domain.settings.lastFmCanEnableScrobbling
import app.natune.shared.domain.settings.lastFmLoggedIn
import app.natune.shared.ui.resources.Res
import app.natune.shared.ui.resources.ios_services_lastfm_cancel
import app.natune.shared.ui.resources.ios_services_lastfm_contacting
import app.natune.shared.ui.resources.ios_services_lastfm_dismiss
import app.natune.shared.ui.resources.ios_services_lastfm_finish
import app.natune.shared.ui.resources.ios_services_lastfm_not_approved
import app.natune.shared.ui.resources.ios_services_lastfm_not_configured
import app.natune.shared.ui.resources.ios_services_lastfm_open_again
import app.natune.shared.ui.resources.ios_services_lastfm_sign_in_failed
import app.natune.shared.ui.resources.ios_services_lastfm_status_signed_in
import app.natune.shared.ui.resources.ios_services_lastfm_status_signed_out
import app.natune.shared.ui.resources.ios_services_lastfm_status_title
import app.natune.shared.ui.resources.ios_services_lastfm_waiting_description
import app.natune.shared.ui.resources.ios_services_lastfm_waiting_title
import app.natune.shared.ui.resources.m6_profile_lastfm_dashboard
import app.natune.shared.ui.settings.LastFmSettingsPage
import app.natune.shared.ui.settings.SettingsController
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
    openDashboard: () -> Unit,
) {
    val snapshot by controller.store.snapshots.collectAsState()
    val preferences = snapshot
    if (preferences == null) {
        CircularProgressIndicator()
        return
    }
    val pending by lastFm.pendingCount.collectAsState()
    val signIn by lastFm.signIn.collectAsState()
    LastFmSettingsPage(
        controller = controller,
        defaultApiKey = lastFm.defaultApiKey,
        defaultSecret = lastFm.defaultSecret,
        pendingScrobbles = pending,
        onConnect = { provider -> lastFm.beginSignIn(provider) { url -> openIosUri(url, report) } },
        onLogout = { lastFm.cancelSignIn() },
        onFlushPendingScrobbles = { lastFm.flushPending() },
        onFailure = onFailure,
        scrollTo = scrollTo,
        onDashboard = openDashboard,
        authorizationPrompt = {
            val phase = when (signIn) {
                IosLastFmSignIn.Idle -> app.natune.shared.ui.settings.LastFmAuthorizationPhase.IDLE
                IosLastFmSignIn.Requesting, IosLastFmSignIn.Exchanging -> app.natune.shared.ui.settings.LastFmAuthorizationPhase.BUSY
                is IosLastFmSignIn.Waiting -> app.natune.shared.ui.settings.LastFmAuthorizationPhase.WAITING
                is IosLastFmSignIn.Failed -> app.natune.shared.ui.settings.LastFmAuthorizationPhase.FAILED
            }
            app.natune.shared.ui.settings.LastFmAuthorizationPrompt(phase,
                (signIn as? IosLastFmSignIn.Waiting)?.refused == true,
                (signIn as? IosLastFmSignIn.Failed)?.notConfigured == true,
                finish = lastFm::finishSignIn,
                reopen = { (signIn as? IosLastFmSignIn.Waiting)?.url?.let { openIosUri(it, report) } },
                cancel = lastFm::cancelSignIn)
        },
    )
}
