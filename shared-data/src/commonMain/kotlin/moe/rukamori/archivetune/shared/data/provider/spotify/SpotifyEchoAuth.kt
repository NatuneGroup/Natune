package moe.rukamori.archivetune.shared.data.provider.spotify

import io.ktor.client.HttpClient
import io.ktor.http.HttpMethod
import io.ktor.http.Url
import io.ktor.http.formUrlEncode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import moe.rukamori.archivetune.shared.domain.repository.AudioProviderUnavailable
import moe.rukamori.archivetune.shared.domain.repository.AudioProviderUnavailableReason

class SpotifyEchoConfiguration(val desktopClientId: String, val webClientId: String, val audioIv: String) {
    override fun toString() = "SpotifyEchoConfiguration(REDACTED)"


}

class SpotifyDesktopSession(val bearer: String, val clientToken: String?) {
    override fun toString() = "SpotifyDesktopSession(REDACTED)"
}

/** The retained desktop device/pair flow, using only the caller's streaming sp_dc and a private, short-lived jar. */
class SpotifyEchoAuth(
    client: HttpClient,
    private val configuration: SpotifyEchoConfiguration,
    private val nowMs: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
) {
    private val http = SpotifyHttp(client)
    private val gate = Mutex()
    private var owner: String? = null
    private var session: SpotifyDesktopSession? = null
    private var expiresAtMs = 0L
    private var closed = false
    private val deviceId = kotlin.random.Random.nextBytes(16).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

    suspend fun session(spDc: String, active: () -> Boolean): SpotifyDesktopSession = gate.withLock {
        require(spDc.isNotBlank() && spDc.none { it in "\r\n;" })
        checkActive(active)
        session?.takeIf { owner == spDc && nowMs() < expiresAtMs }?.let { return@withLock it }
        session = null
        owner = null
        val token = authorize(spDc, active)
        checkActive(active)
        val clientToken = try {
            clientToken(configuration.desktopClientId, false, active)
                ?: configuration.webClientId.takeIf { it.isNotBlank() }?.let { clientToken(it, true, active) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SpotifyProtocolFailure) {
            null
        }
        checkActive(active)
        SpotifyDesktopSession(token.first, clientToken).also {
            owner = spDc
            expiresAtMs = nowMs() + token.second.coerceIn(1L, 1800L) * 1000L
            session = it
        }
    }

    private fun checkActive(active: () -> Boolean) {
        if (closed || !active()) throw AudioProviderUnavailable(AudioProviderUnavailableReason.ACCOUNT_CHANGED)
    }

    private suspend fun authorize(spDc: String, active: () -> Boolean): Pair<String, Long> {
        val jar = linkedMapOf("sp_dc" to spDc)
        suspend fun request(url: String, form: List<Pair<String, String>>? = null,
            json: JsonObject? = null, extra: Map<String, String> = emptyMap()): SpotifyReply {
            checkActive(active)
            val uri = Url(url)
            if (uri.protocol.name != "https" || uri.host != ACCOUNTS_HOST || uri.port != 443 ||
                !uri.user.isNullOrEmpty() || !uri.password.isNullOrEmpty()) throw SpotifyProtocolFailure()
            val reply = http.exchange(url, if (form == null && json == null) HttpMethod.Get else HttpMethod.Post,
                buildMap {
                    put("User-Agent", USER_AGENT)
                    put("Accept", "application/json")
                    put("Cookie", jar.entries.joinToString("; ") { "${it.key}=${it.value}" })
                    putAll(extra)
                    if (form != null) put("Content-Type", "application/x-www-form-urlencoded")
                    if (json != null) put("Content-Type", "application/json")
                }, form?.formUrlEncode()?.encodeToByteArray() ?: json?.toString()?.encodeToByteArray())
            checkActive(active)
            for (raw in reply.cookies.take(32)) {
                val pair = raw.substringBefore(';')
                val name = pair.substringBefore('=')
                val value = pair.substringAfter('=', "")
                if (name.isNotBlank() && name.all { it.isLetterOrDigit() || it in "_-" } &&
                    value.length <= 8192 && value.none { it in "\r\n;" } && jar.size < 32) jar[name] = value
            }
            return reply
        }
        val start = request("https://$ACCOUNTS_HOST/oauth2/device/authorize",
            form = listOf("client_id" to configuration.desktopClientId, "scope" to DEVICE_SCOPE)).success().json()
        val deviceCode = start.spotifyText("device_code") ?: throw SpotifyProtocolFailure()
        val userCode = start.spotifyText("user_code") ?: throw SpotifyProtocolFailure()
        var verificationUrl = start.spotifyText("verification_uri_complete") ?: start.spotifyText("verification_uri")
            ?: throw SpotifyProtocolFailure()
        var page: SpotifyReply? = null
        for (redirect in 0..MAX_REDIRECTS) {
            val reply = request(verificationUrl)
            if (reply.status in setOf(301, 302, 303, 307, 308)) {
                val location = reply.location ?: throw SpotifyProtocolFailure(reply.status)
                verificationUrl = if (location.startsWith('/')) "https://$ACCOUNTS_HOST$location" else location
            } else {
                page = reply.success()
                break
            }
        }
        val html = page?.bytes?.decodeToString() ?: throw SpotifyProtocolFailure()
        val flow = Url(verificationUrl).parameters["flow_ctx"]?.substringBefore(':')?.takeIf { it.isNotBlank() }
            ?: throw SpotifyProtocolFailure()
        val nextData = NEXT_DATA.find(html)?.groupValues?.get(1) ?: throw SpotifyProtocolFailure()
        val props = (Json.parseToJsonElement(nextData) as? JsonObject)?.spotifyObject("props")
        val csrf = props?.spotifyText("initialToken")?.takeIf { it.none { c -> c in "\r\n" } }
            ?: throw SpotifyProtocolFailure()
        val resolve = SpotifyHttp.url("https://$ACCOUNTS_HOST/pair/api/resolve",
            mapOf("flow_ctx" to "$flow:${nowMs() / 1000}"))
        val submitted = request(resolve, json = buildJsonObject { put("code", userCode) },
            extra = mapOf("x-csrf-token" to csrf, "Referer" to verificationUrl,
                "Origin" to "https://$ACCOUNTS_HOST")).success().json()
        if (submitted.spotifyText("result") != "ok") throw SpotifyProtocolFailure()
        val result = request("https://$ACCOUNTS_HOST/api/token", form = listOf(
            "client_id" to configuration.desktopClientId,
            "device_code" to deviceCode,
            "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
        )).success().json()
        return (result.spotifyText("access_token") ?: throw SpotifyProtocolFailure()) to
            ((result["expires_in"] as? JsonPrimitive)?.longOrNull ?: 1800L)
    }

    private suspend fun clientToken(clientId: String, web: Boolean, active: () -> Boolean): String? {
        checkActive(active)
        val payload = buildJsonObject {
            put("client_data", buildJsonObject {
                put("client_version", APP_VERSION)
                put("client_id", clientId)
                put("js_sdk_data", buildJsonObject {
                    put("device_brand", if (web) "unknown" else "Microsoft")
                    put("device_id", deviceId)
                    put("device_model", if (web) "unknown" else "PC")
                    put("device_type", "computer")
                    put("os", "windows")
                    put("os_version", "NT 10.0")
                })
            })
        }
        val reply = http.exchange("https://clienttoken.spotify.com/v1/clienttoken", HttpMethod.Post,
            mapOf("Content-Type" to "application/json", "Accept" to "application/json", "User-Agent" to USER_AGENT),
            payload.toString().encodeToByteArray())
        checkActive(active)
        return if (reply.status in 200..299) reply.json().spotifyObject("granted_token")?.spotifyText("token") else null
    }

    suspend fun close() = gate.withLock {
        closed = true
        owner = null
        session = null
        expiresAtMs = 0L
        http.close()
    }

    companion object {
        const val APP_VERSION = "1.2.66.510.gd0327113"
        const val USER_AGENT = "Spotify/126600510 Win32_x86_64/0 (PC laptop)"
        private const val ACCOUNTS_HOST = "accounts.spotify.com"
        private const val MAX_REDIRECTS = 8
        private val NEXT_DATA = Regex("""<script id="__NEXT_DATA__" type="application/json"[^>]*>(.*?)</script>""",
            RegexOption.DOT_MATCHES_ALL)
        private const val DEVICE_SCOPE =
            "app-remote-control,playlist-modify,playlist-modify-private,playlist-modify-public," +
                "playlist-read,playlist-read-collaborative,playlist-read-private,streaming," +
                "transfer-auth-session,ugc-image-upload,user-follow-modify,user-follow-read," +
                "user-library-modify,user-library-read,user-modify,user-modify-playback-state," +
                "user-modify-private,user-personalized,user-read-birthdate,user-read-currently-playing," +
                "user-read-email,user-read-play-history,user-read-playback-position,user-read-playback-state," +
                "user-read-private,user-read-recently-played,user-top-read"
    }
}

private fun SpotifyReply.success(): SpotifyReply {
    if (status !in 200..299) throw SpotifyProtocolFailure(status)
    return this
}
