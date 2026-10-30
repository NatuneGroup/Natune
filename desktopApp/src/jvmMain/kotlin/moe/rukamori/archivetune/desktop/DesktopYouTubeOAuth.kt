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
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.parameters
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import moe.rukamori.archivetune.desktop.integration.DesktopStrings
import moe.rukamori.archivetune.shared.domain.settings.InnerTubeOAuthExpiresAtKey
import moe.rukamori.archivetune.shared.domain.settings.InnerTubeOAuthRefreshTokenKey
import moe.rukamori.archivetune.shared.domain.settings.InnerTubeOAuthTokenKey
import moe.rukamori.archivetune.shared.ui.navigation.ShellNavigation
import moe.rukamori.archivetune.shared.ui.settings.SettingsController

internal const val DesktopYouTubeOAuthRoute = "settings/youtube/oauth"

/**
 * The Android app's YouTube device-code sign-in: the same endpoints, scope and grant, with the client pair read from
 * [DesktopCredentials]. Responses are parsed here, apart from the transport, so they can be checked without a network.
 */
internal object YouTubeDeviceCodeFlow {
    const val CLIENT_ID_NAME = "ARCHIVETUNE_YOUTUBE_OAUTH_CLIENT_ID"
    const val CLIENT_SECRET_NAME = "ARCHIVETUNE_YOUTUBE_OAUTH_CLIENT_SECRET"
    const val DEVICE_CODE_URL = "https://www.youtube.com/o/oauth2/device/code"
    const val TOKEN_URL = "https://www.youtube.com/o/oauth2/token"
    const val SCOPE = "https://www.googleapis.com/auth/youtube"
    const val DEVICE_GRANT = "http://oauth.net/grant_type/device/1.0"
    const val DEFAULT_VERIFICATION_URL = "https://www.google.com/device"
    const val SLOW_DOWN_STEP_MS = 5_000L

    data class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUrl: String,
        val intervalSeconds: Int,
        val expiresInSeconds: Int,
    )

    sealed interface Poll {
        data object Pending : Poll
        data object SlowDown : Poll
        data class Granted(val accessToken: String, val refreshToken: String?, val expiresInSeconds: Int) : Poll
        data class Failed(val error: String) : Poll
    }

    fun parseDeviceCode(body: String): DeviceCode? {
        val json = parse(body) ?: return null
        val deviceCode = json.text("device_code") ?: return null
        val userCode = json.text("user_code") ?: return null
        return DeviceCode(
            deviceCode = deviceCode,
            userCode = userCode,
            verificationUrl = json.text("verification_url") ?: DEFAULT_VERIFICATION_URL,
            intervalSeconds = (json.number("interval") ?: 5).coerceAtLeast(1),
            expiresInSeconds = json.number("expires_in") ?: 1800,
        )
    }

    fun parsePoll(body: String): Poll {
        val json = parse(body) ?: return Poll.Failed("network")
        return when (val error = json.text("error")) {
            null -> json.text("access_token")
                ?.let { Poll.Granted(it, json.text("refresh_token"), json.number("expires_in") ?: 3600) }
                ?: Poll.Failed("no_access_token")
            "authorization_pending" -> Poll.Pending
            "slow_down" -> Poll.SlowDown
            else -> Poll.Failed(error)
        }
    }

    private fun parse(body: String): JsonObject? =
        runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()

    private fun JsonObject.text(name: String): String? =
        runCatching { get(name)?.jsonPrimitive?.contentOrNull }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun JsonObject.number(name: String): Int? =
        runCatching { get(name)?.jsonPrimitive?.intOrNull }.getOrNull()
}

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
    val codeBody = http.submitForm(YouTubeDeviceCodeFlow.DEVICE_CODE_URL, parameters {
        append("client_id", clientId)
        append("scope", YouTubeDeviceCodeFlow.SCOPE)
        append("device_id", UUID.randomUUID().toString())
        append("device_model", "ytlr::")
    }) { expectSuccess = false }.bodyAsText()
    val code = YouTubeDeviceCodeFlow.parseDeviceCode(codeBody) ?: return CodeSignInState.Failed("youtube_oauth_failed")
    onCode(code)
    val deadline = System.currentTimeMillis() + code.expiresInSeconds * 1000L
    var interval = code.intervalSeconds * 1000L
    while (System.currentTimeMillis() < deadline) {
        delay(interval)
        val body = http.submitForm(YouTubeDeviceCodeFlow.TOKEN_URL, parameters {
            append("client_id", clientId)
            append("client_secret", clientSecret)
            append("code", code.deviceCode)
            append("grant_type", YouTubeDeviceCodeFlow.DEVICE_GRANT)
        }) { expectSuccess = false }.bodyAsText()
        when (val poll = YouTubeDeviceCodeFlow.parsePoll(body)) {
            YouTubeDeviceCodeFlow.Poll.Pending -> Unit
            YouTubeDeviceCodeFlow.Poll.SlowDown -> interval += YouTubeDeviceCodeFlow.SLOW_DOWN_STEP_MS
            is YouTubeDeviceCodeFlow.Poll.Granted -> {
                val expiresAt = System.currentTimeMillis() + poll.expiresInSeconds * 1000L
                controller.edit {
                    this[InnerTubeOAuthTokenKey] = poll.accessToken
                    poll.refreshToken?.let { this[InnerTubeOAuthRefreshTokenKey] = it }
                    this[InnerTubeOAuthExpiresAtKey] = expiresAt
                }
                return CodeSignInState.SignedIn
            }
            is YouTubeDeviceCodeFlow.Poll.Failed -> return CodeSignInState.Failed(
                when (poll.error) {
                    "access_denied" -> "youtube_oauth_denied"
                    "expired_token" -> "youtube_oauth_expired"
                    else -> "youtube_oauth_failed"
                },
            )
        }
    }
    return CodeSignInState.Failed("youtube_oauth_expired")
}
