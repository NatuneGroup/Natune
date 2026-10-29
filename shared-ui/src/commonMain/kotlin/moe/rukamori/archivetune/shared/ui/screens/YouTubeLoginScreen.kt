package moe.rukamori.archivetune.shared.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.ui.glass.BusyLinearProgress
import moe.rukamori.archivetune.shared.ui.component.AuthWebViewLayout
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Owner-reported state of the YouTube Music cookie sign-in. The owner keeps the web attempt, the
 * same-session return guard, private account verification and the atomic commit; this chrome only
 * displays what it reports and never reports success on its own.
 */
sealed interface YouTubeLoginStatus {
    /** The attempt is loading, redirecting or verifying the selected account. */
    data object Loading : YouTubeLoginStatus
    /** No attempt is in progress. */
    data object Idle : YouTubeLoginStatus
    /** The last attempt failed; the owner leaves the previously committed account unchanged. */
    data class Failed(val error: YouTubeLoginError) : YouTubeLoginStatus
    /** The account committed; the host confirms and navigates back. */
    data object Succeeded : YouTubeLoginStatus
}

/** Owner-classified failure reasons. Each maps to one fixed message; none is a success stand-in. */
enum class YouTubeLoginError { MissingDataSyncId, LoginFailed, Network, Http, UnsupportedNavigation, RedirectLoop, RendererLost }

data class YouTubeLoginUi(val status: YouTubeLoginStatus)

/**
 * [back] is also the cancel action: the owner releases the attempt when this entry leaves composition.
 * [retry] restarts the attempt without clearing the existing cookie jar.
 */
data class YouTubeLoginActions(val back: () -> Unit, val retry: () -> Unit)

/**
 * Sign-in chrome with the sheet typography and spacing of the baseline sign-in flows, presented as a
 * page in the shell. [webSurface] is a host-supplied native web view (WebView, WebKitGTK or WKWebView);
 * the host must key it on its attempt revision and enforce the navigation policy. Cookies, scripts and
 * account commits never run in this composable.
 */
@Composable
fun YouTubeLoginScreen(
    state: YouTubeLoginUi,
    actions: YouTubeLoginActions,
    webSurface: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        ManagementHeader(stringResource(Res.string.m4_youtube_login_title), actions.back)
        AuthWebViewLayout(
            Modifier.fillMaxWidth().weight(1f).padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
            header = {
                Text(
                    stringResource(Res.string.m4_youtube_login_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LoginStatusContent(state.status, actions)
            },
        ) {
            Box(Modifier.fillMaxWidth().weight(1f).clip(MaterialTheme.shapes.large)) {
                webSurface(Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun LoginStatusContent(status: YouTubeLoginStatus, actions: YouTubeLoginActions) {
    if (status == YouTubeLoginStatus.Loading) {
        BusyLinearProgress(Modifier.fillMaxWidth())
        Text(stringResource(Res.string.m4_youtube_login_working))
    }
    if (status is YouTubeLoginStatus.Failed) {
        Text(stringResource(status.error.messageResource()), color = MaterialTheme.colorScheme.error)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (status is YouTubeLoginStatus.Failed) {
            TextButton(onClick = actions.retry, modifier = Modifier.testTag("youtube-login-retry")) {
                Text(stringResource(Res.string.m4_retry))
            }
        }
        TextButton(onClick = actions.back, modifier = Modifier.testTag("youtube-login-cancel")) {
            Text(stringResource(Res.string.m4_cancel))
        }
    }
}

private fun YouTubeLoginError.messageResource(): StringResource = when (this) {
    YouTubeLoginError.MissingDataSyncId -> Res.string.m4_youtube_login_missing_context
    YouTubeLoginError.LoginFailed -> Res.string.m4_youtube_login_validation_failed
    YouTubeLoginError.Network -> Res.string.m4_youtube_login_network_error
    YouTubeLoginError.Http -> Res.string.m4_youtube_login_http_error
    YouTubeLoginError.UnsupportedNavigation -> Res.string.m4_youtube_login_unsupported_navigation
    YouTubeLoginError.RedirectLoop -> Res.string.m4_youtube_login_redirect_loop
    YouTubeLoginError.RendererLost -> Res.string.m4_youtube_login_renderer_lost
}
