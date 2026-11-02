package moe.rukamori.archivetune.shared.data.provider

import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.parameters
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The YouTube device-code sign-in the Android app runs: the same endpoints, scope and grant. The client pair is
 * build-supplied on every host, so this object only carries the recipe and the response parsing; each host's page
 * holds its own state machine, strings and time source. The token keys the granted session is written under are the
 * shared `InnerTubeOAuth*` settings, so the account page's sign-out and the backup export see the session on any host.
 */
object YouTubeDeviceCodeFlow {
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

    /** The one device-code request; null when the answer carries no usable code. */
    suspend fun requestDeviceCode(http: HttpClient, clientId: String, deviceId: String): DeviceCode? {
        val body = http.submitForm(DEVICE_CODE_URL, parameters {
            append("client_id", clientId)
            append("scope", SCOPE)
            append("device_id", deviceId)
            append("device_model", "ytlr::")
        }) { expectSuccess = false }.bodyAsText()
        return parseDeviceCode(body)
    }

    /** One token-endpoint poll; the page repeats it at the code's interval until it stops being Pending or SlowDown. */
    suspend fun pollOnce(http: HttpClient, clientId: String, clientSecret: String, code: String): Poll {
        val body = http.submitForm(TOKEN_URL, parameters {
            append("client_id", clientId)
            append("client_secret", clientSecret)
            append("code", code)
            append("grant_type", DEVICE_GRANT)
        }) { expectSuccess = false }.bodyAsText()
        return parsePoll(body)
    }

    /**
     * The bounded poll: repeats at the code's interval, backs off on `slow_down` and stops at the code's expiry.
     * [nowMs] is the host's wall clock in milliseconds. Only a terminal [Poll] is returned, never Pending or SlowDown.
     */
    suspend fun pollUntilDone(
        http: HttpClient,
        clientId: String,
        clientSecret: String,
        code: DeviceCode,
        nowMs: () -> Long,
    ): Poll {
        val deadline = nowMs() + code.expiresInSeconds * 1_000L
        var interval = code.intervalSeconds * 1_000L
        while (nowMs() < deadline) {
            delay(interval)
            when (val poll = pollOnce(http, clientId, clientSecret, code.deviceCode)) {
                Poll.Pending -> Unit
                Poll.SlowDown -> interval += SLOW_DOWN_STEP_MS
                else -> return poll
            }
        }
        return Poll.Failed("expired_token")
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
