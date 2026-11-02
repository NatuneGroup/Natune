package moe.rukamori.archivetune.desktop

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
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.desktop.integration.DesktopStrings
import moe.rukamori.archivetune.shared.data.provider.YouTubeDeviceCodeFlow
import moe.rukamori.archivetune.shared.domain.settings.InnerTubeOAuthExpiresAtKey
import moe.rukamori.archivetune.shared.domain.settings.InnerTubeOAuthRefreshTokenKey
import moe.rukamori.archivetune.shared.domain.settings.InnerTubeOAuthTokenKey
import moe.rukamori.archivetune.shared.ui.navigation.ShellNavigation
import moe.rukamori.archivetune.shared.ui.settings.SettingsController

internal const val DesktopYouTubeOAuthRoute = "settings/youtube/oauth"

private sealed interface CodeSignInState {
    data object Idle : CodeSignInState
    data object Requesting : CodeSignInState
    data class Waiting(val code: YouTubeDeviceCodeFlow.DeviceCode) : CodeSignInState
    data object SignedIn : CodeSignInState
    data class Failed(val messageKey: String) : CodeSignInState
}

/**
 * Settings > Account > code sign-in. The poll runs only while this page is open, as on Android. On success the access
 * token, refresh token and expiry are stored under the keys the account page reads, so its code-session sign-out and
 * revoke apply.
 */
@Composable
internal fun DesktopYouTubeOAuthPage(
    http: HttpClient,
    controller: SettingsController,
    navigation: ShellNavigation,
    report: (String) -> Unit,
) {
    val clientId = remember { DesktopCredentials.value(YouTubeDeviceCodeFlow.CLIENT_ID_NAME) }
    val clientSecret = remember { DesktopCredentials.value(YouTubeDeviceCodeFlow.CLIENT_SECRET_NAME) }
    val configured = clientId.isNotBlank() && clientSecret.isNotBlank()
    var state by remember { mutableStateOf<CodeSignInState>(CodeSignInState.Idle) }
    var attempt by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    fun start() {
        attempt?.cancel()
        state = CodeSignInState.Requesting
        attempt = scope.launch {
            state = try {
                runCodeSignIn(http, controller, clientId, clientSecret) { code -> state = CodeSignInState.Waiting(code) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                report("youtube code sign-in failed: ${failure.javaClass.simpleName}")
                CodeSignInState.Failed("youtube_oauth_failed")
            }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(DesktopSettingsText["youtube_oauth_title"], style = MaterialTheme.typography.headlineSmall)
        Text(DesktopSettingsText["youtube_oauth_description"], style = MaterialTheme.typography.bodyMedium)
        Text(DesktopSettingsText["youtube_oauth_scope_note"], style = MaterialTheme.typography.bodySmall)
        if (!configured) {
            Text(DesktopSettingsText["youtube_oauth_not_configured"], color = MaterialTheme.colorScheme.error)
            return@Column
        }
        when (val current = state) {
            CodeSignInState.Idle -> Button(onClick = ::start) { Text(DesktopSettingsText["youtube_oauth_start"]) }
            CodeSignInState.Requesting -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator()
                Text(DesktopSettingsText["youtube_oauth_requesting"])
            }
            is CodeSignInState.Waiting -> {
                Text(DesktopSettingsText.format("youtube_oauth_enter_code", current.code.verificationUrl))
                SelectionContainer { Text(current.code.userCode, style = MaterialTheme.typography.displaySmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { openDesktopUri(current.code.verificationUrl, report) }) {
                        Text(DesktopSettingsText["youtube_oauth_open_page"])
                    }
                    OutlinedButton(onClick = { copyToDesktopClipboard(current.code.userCode) }) {
                        Text(DesktopSettingsText["youtube_oauth_copy_code"])
                    }
                    TextButton(onClick = {
                        attempt?.cancel()
                        state = CodeSignInState.Idle
                    }) { Text(DesktopStrings["cancel"]) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator()
                    Text(DesktopSettingsText["youtube_oauth_waiting"])
                }
            }
            CodeSignInState.SignedIn -> {
                Text(DesktopSettingsText["youtube_oauth_signed_in"])
                Button(onClick = { navigation.back() }) { Text(DesktopSettingsText["youtube_oauth_done"]) }
            }
            is CodeSignInState.Failed -> {
                Text(DesktopSettingsText[current.messageKey], color = MaterialTheme.colorScheme.error)
                Button(onClick = ::start) { Text(DesktopSettingsText["youtube_oauth_start"]) }
            }
        }
    }
}

private suspend fun runCodeSignIn(
    http: HttpClient,
    controller: SettingsController,
    clientId: String,
    clientSecret: String,
    onCode: (YouTubeDeviceCodeFlow.DeviceCode) -> Unit,
): CodeSignInState {
    val code = YouTubeDeviceCodeFlow.requestDeviceCode(http, clientId, UUID.randomUUID().toString())
        ?: return CodeSignInState.Failed("youtube_oauth_failed")
    onCode(code)
    return when (val poll = YouTubeDeviceCodeFlow.pollUntilDone(
        http, clientId, clientSecret, code, System::currentTimeMillis,
    )) {
        is YouTubeDeviceCodeFlow.Poll.Granted -> {
            val expiresAt = System.currentTimeMillis() + poll.expiresInSeconds * 1_000L
            controller.edit {
                this[InnerTubeOAuthTokenKey] = poll.accessToken
                poll.refreshToken?.let { this[InnerTubeOAuthRefreshTokenKey] = it }
                this[InnerTubeOAuthExpiresAtKey] = expiresAt
            }
            CodeSignInState.SignedIn
        }
        is YouTubeDeviceCodeFlow.Poll.Failed -> CodeSignInState.Failed(
            when (poll.error) {
                "access_denied" -> "youtube_oauth_denied"
                "expired_token" -> "youtube_oauth_expired"
                else -> "youtube_oauth_failed"
            },
        )
        // pollUntilDone only ends on a terminal poll, so Pending and SlowDown cannot reach this point.
        else -> CodeSignInState.Failed("youtube_oauth_failed")
    }
}
