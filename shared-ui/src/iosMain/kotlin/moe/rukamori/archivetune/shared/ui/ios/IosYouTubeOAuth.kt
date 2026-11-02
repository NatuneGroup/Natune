package moe.rukamori.archivetune.shared.ui.ios

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.ktor.client.HttpClient
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.data.platform.ios.IosProviderCredentials
import moe.rukamori.archivetune.shared.data.provider.YouTubeDeviceCodeFlow
import moe.rukamori.archivetune.shared.domain.settings.InnerTubeOAuthExpiresAtKey
import moe.rukamori.archivetune.shared.domain.settings.InnerTubeOAuthRefreshTokenKey
import moe.rukamori.archivetune.shared.domain.settings.InnerTubeOAuthTokenKey
import moe.rukamori.archivetune.shared.ui.navigation.ShellNavigation
import moe.rukamori.archivetune.shared.ui.resources.Res
import moe.rukamori.archivetune.shared.ui.resources.ios_youtube_oauth_copy_code
import moe.rukamori.archivetune.shared.ui.resources.ios_youtube_oauth_denied
import moe.rukamori.archivetune.shared.ui.resources.ios_youtube_oauth_description
import moe.rukamori.archivetune.shared.ui.resources.ios_youtube_oauth_done
import moe.rukamori.archivetune.shared.ui.resources.ios_youtube_oauth_enter_code
import moe.rukamori.archivetune.shared.ui.resources.ios_youtube_oauth_expired
import moe.rukamori.archivetune.shared.ui.resources.ios_youtube_oauth_failed
import moe.rukamori.archivetune.shared.ui.resources.ios_youtube_oauth_not_configured
import moe.rukamori.archivetune.shared.ui.resources.ios_youtube_oauth_open_page
import moe.rukamori.archivetune.shared.ui.resources.ios_youtube_oauth_requesting
import moe.rukamori.archivetune.shared.ui.resources.ios_youtube_oauth_scope_note
import moe.rukamori.archivetune.shared.ui.resources.ios_youtube_oauth_signed_in
import moe.rukamori.archivetune.shared.ui.resources.ios_youtube_oauth_start
import moe.rukamori.archivetune.shared.ui.resources.ios_youtube_oauth_title
import moe.rukamori.archivetune.shared.ui.resources.ios_youtube_oauth_waiting
import moe.rukamori.archivetune.shared.ui.resources.m6_provider_sign_in_cancel
import moe.rukamori.archivetune.shared.ui.settings.SettingsController
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import platform.Foundation.NSUUID

internal const val IosYouTubeOAuthRoute = "settings/youtube/oauth"

private sealed interface IosCodeSignInState {
    data object Idle : IosCodeSignInState
    data object Requesting : IosCodeSignInState
    data class Waiting(val code: YouTubeDeviceCodeFlow.DeviceCode) : IosCodeSignInState
    data object SignedIn : IosCodeSignInState
    data class Failed(val key: StringResource) : IosCodeSignInState
}

/**
 * Settings > Account > code sign-in, the same device-code page Android and the desktop mount. The client pair comes
 * from the `ARCHIVETUNE_YOUTUBE_OAUTH_*` build settings via the Info.plist keys; without them the page reports itself
 * not configured. The poll runs only while this page is open. On success the access token, refresh token and expiry
 * are stored under the keys the account page reads, so its code-session sign-out and revoke apply, and a backup can
 * carry the session to a host that uses it for playback.
 */
@Composable
internal fun IosYouTubeOAuthPage(
    http: HttpClient,
    controller: SettingsController,
    navigation: ShellNavigation,
    report: (String) -> Unit,
) {
    val clientId = remember { IosProviderCredentials.youTubeOauthClientId }
    val clientSecret = remember { IosProviderCredentials.youTubeOauthClientSecret }
    val configured = clientId.isNotBlank() && clientSecret.isNotBlank()
    var state by remember { mutableStateOf<IosCodeSignInState>(IosCodeSignInState.Idle) }
    var attempt by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    fun start() {
        attempt?.cancel()
        state = IosCodeSignInState.Requesting
        attempt = scope.launch {
            state = try {
                runIosCodeSignIn(http, controller, clientId, clientSecret) { code ->
                    state = IosCodeSignInState.Waiting(code)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                report("youtube code sign-in failed: ${failure::class.simpleName}")
                IosCodeSignInState.Failed(Res.string.ios_youtube_oauth_failed)
            }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(Res.string.ios_youtube_oauth_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(Res.string.ios_youtube_oauth_description), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(Res.string.ios_youtube_oauth_scope_note), style = MaterialTheme.typography.bodySmall)
        if (!configured) {
            Text(stringResource(Res.string.ios_youtube_oauth_not_configured), color = MaterialTheme.colorScheme.error)
            return@Column
        }
        when (val current = state) {
            IosCodeSignInState.Idle ->
                Button(onClick = ::start) { Text(stringResource(Res.string.ios_youtube_oauth_start)) }
            IosCodeSignInState.Requesting -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator()
                Text(stringResource(Res.string.ios_youtube_oauth_requesting))
            }
            is IosCodeSignInState.Waiting -> {
                Text(
                    stringResource(Res.string.ios_youtube_oauth_enter_code, current.code.verificationUrl),
                )
                SelectionContainer { Text(current.code.userCode, style = MaterialTheme.typography.displaySmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { openIosUri(current.code.verificationUrl, report) }) {
                        Text(stringResource(Res.string.ios_youtube_oauth_open_page))
                    }
                    OutlinedButton(onClick = { copyToIosClipboard(current.code.userCode) }) {
                        Text(stringResource(Res.string.ios_youtube_oauth_copy_code))
                    }
                    TextButton(onClick = {
                        attempt?.cancel()
                        state = IosCodeSignInState.Idle
                    }) { Text(stringResource(Res.string.m6_provider_sign_in_cancel)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator()
                    Text(stringResource(Res.string.ios_youtube_oauth_waiting))
                }
            }
            IosCodeSignInState.SignedIn -> {
                Text(stringResource(Res.string.ios_youtube_oauth_signed_in))
                Button(onClick = { navigation.back() }) {
                    Text(stringResource(Res.string.ios_youtube_oauth_done))
                }
            }
            is IosCodeSignInState.Failed -> {
                Text(stringResource(current.key), color = MaterialTheme.colorScheme.error)
                Button(onClick = ::start) { Text(stringResource(Res.string.ios_youtube_oauth_start)) }
            }
        }
    }
}

private suspend fun runIosCodeSignIn(
    http: HttpClient,
    controller: SettingsController,
    clientId: String,
    clientSecret: String,
    onCode: (YouTubeDeviceCodeFlow.DeviceCode) -> Unit,
): IosCodeSignInState {
    val code = YouTubeDeviceCodeFlow.requestDeviceCode(http, clientId, NSUUID().UUIDString)
        ?: return IosCodeSignInState.Failed(Res.string.ios_youtube_oauth_failed)
    onCode(code)
    return when (val poll = YouTubeDeviceCodeFlow.pollUntilDone(
        http, clientId, clientSecret, code, { Clock.System.now().toEpochMilliseconds() },
    )) {
        is YouTubeDeviceCodeFlow.Poll.Granted -> {
            val expiresAt = Clock.System.now().toEpochMilliseconds() + poll.expiresInSeconds * 1_000L
            controller.edit {
                this[InnerTubeOAuthTokenKey] = poll.accessToken
                poll.refreshToken?.let { this[InnerTubeOAuthRefreshTokenKey] = it }
                this[InnerTubeOAuthExpiresAtKey] = expiresAt
            }
            IosCodeSignInState.SignedIn
        }
        is YouTubeDeviceCodeFlow.Poll.Failed -> IosCodeSignInState.Failed(
            when (poll.error) {
                "access_denied" -> Res.string.ios_youtube_oauth_denied
                "expired_token" -> Res.string.ios_youtube_oauth_expired
                else -> Res.string.ios_youtube_oauth_failed
            },
        )
        // pollUntilDone only ends on a terminal poll, so Pending and SlowDown cannot reach this point.
        else -> IosCodeSignInState.Failed(Res.string.ios_youtube_oauth_failed)
    }
}
