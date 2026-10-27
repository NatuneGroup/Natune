package moe.rukamori.archivetune.shared.data.provider

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.prepareForm
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.encodeURLParameter
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import moe.rukamori.archivetune.shared.domain.platform.ContentDigest
import moe.rukamori.archivetune.shared.domain.settings.TidalAuthFlowDevice
import moe.rukamori.archivetune.shared.domain.settings.TidalAuthFlowPkce
import moe.rukamori.archivetune.shared.domain.settings.TidalAuthFlowWebCapture
import moe.rukamori.archivetune.shared.domain.settings.TidalDeviceAuthorization
import moe.rukamori.archivetune.shared.domain.settings.TidalDeviceLink
import moe.rukamori.archivetune.shared.domain.settings.TidalDeviceLinkPoll
import moe.rukamori.archivetune.shared.domain.settings.TidalSessionFacts
import moe.rukamori.archivetune.shared.domain.settings.TidalSubscriptionStatus

/** One TIDAL OAuth client. Both values are build-supplied and never written to settings, logs or diagnostics. */
class TidalClientCredentials(val clientId: String, val clientSecret: String) {
    init {
        require(clientId.isNotBlank() && clientSecret.isNotBlank()) { "TIDAL client is incomplete" }
    }

    override fun toString(): String = "TidalClientCredentials(REDACTED)"
}

/** The tokens of one successful grant. [refreshToken] is null when TIDAL did not rotate or issue one. */
class TidalTokenGrant(
    val accessToken: String,
    val refreshToken: String?,
    val expiresInSeconds: Long,
    val userId: Long?,
    val countryCode: String?,
    val username: String?,
) {
    override fun toString(): String = "TidalTokenGrant(REDACTED)"
}

/**
 * A generated PKCE challenge pair for the TIDAL web login. [authorizeUrl] is loaded in the login
 * browser; [verifier] and [uniqueKey] are kept and passed to [TidalAccountAuth.exchangePkceCode]
 * once the redirect returns the authorization code. Nothing here is a token, but the verifier is
 * still kept out of logs like the rest of the flow.
 */
class TidalPkceAuthorization(
    val verifier: String,
    val challenge: String,
    val uniqueKey: String,
    val authorizeUrl: String,
    val redirectUri: String,
) {
    override fun toString(): String = "TidalPkceAuthorization(REDACTED)"
}

/** How one refresh ended. Only [Refreshed] carries tokens. */
sealed interface TidalRefreshResult {
    class Refreshed(val grant: TidalTokenGrant) : TidalRefreshResult {
        override fun toString(): String = "Refreshed(REDACTED)"
    }

    /** TIDAL refused the refresh token. The user has to sign in again. */
    data object Rejected : TidalRefreshResult

    /** TIDAL no longer knows the client that issued the token. Signing in again with the current client is the fix. */
    data object RetiredClient : TidalRefreshResult

    /** No client for this flow, a captured web session, or a network failure. The stored session stays as it is. */
    data object Unavailable : TidalRefreshResult
}

/**
 * The TIDAL account endpoints the settings sign-in and the token refresh use: the OAuth device grant, the refresh grant
 * and the session and subscription lookups. [device] is the device-flow client and [pkce] the web-login client; either may
 * be absent from a build, and the matching operation then answers as unavailable instead of using a copied value.
 *
 * Every response is read with a bounded body and a wall timeout, and no request follows a redirect, so a bearer or client
 * secret never travels to another host. Failures carry no response text.
 */
/** The fixed https return the embedded TIDAL PKCE WebView intercepts — the same URI the Android flow uses. */
const val TIDAL_PKCE_REDIRECT_URI = "https://tidal.com/android/login/auth"

class TidalAccountAuth(
    client: HttpClient,
    private val device: TidalClientCredentials?,
    private val pkce: TidalClientCredentials?,
    private val nowMs: () -> Long,
    authBase: String = "https://auth.tidal.com/v1/oauth2",
    apiBase: String = "https://api.tidal.com/v1",
) {
    private val raw = client.config { followRedirects = false; expectSuccess = false }
    private val http = ProviderHttp(client)
    private val auth = endpoint(authBase)
    private val api = endpoint(apiBase)

    val deviceSignInAvailable: Boolean get() = device != null

    /** The PKCE web-login client is build-supplied; without it the embedded authorization-code flow is not offered. */
    val pkceSignInAvailable: Boolean get() = pkce != null

    /** Starts a device authorization, or returns null when this build has no device client or TIDAL refused. */
    suspend fun startDeviceAuthorization(): TidalDeviceAuthorization? {
        val client = device ?: return null
        val (status, body) = form("$auth/device_authorization", listOf("client_id" to client.clientId, "scope" to FULL_SCOPE))
        if (status !in 200..299 || body == null) return null
        val deviceCode = body.text("deviceCode") ?: body.text("device_code") ?: return null
        val userCode = body.text("userCode") ?: body.text("user_code") ?: return null
        val page = body.text("verificationUriComplete") ?: body.text("verification_uri_complete")
            ?: body.text("verificationUri") ?: body.text("verification_uri") ?: return null
        val expiresIn = body.long("expiresIn") ?: body.long("expires_in") ?: DEFAULT_DEVICE_EXPIRY_SECONDS
        val interval = body.long("interval") ?: DEFAULT_DEVICE_INTERVAL_SECONDS
        return TidalDeviceAuthorization(
            userCode = userCode,
            // TIDAL answers with a bare host such as link.tidal.com/ABCDE, which the browser needs as an HTTPS link.
            verificationUri = if (page.startsWith("https://")) page else "https://${page.removePrefix("http://")}",
            expiresInMillis = expiresIn.coerceAtLeast(1L) * 1_000L,
            intervalMillis = interval.coerceAtLeast(1L) * 1_000L,
            deviceCode = deviceCode,
        )
    }

    /** One device-grant poll. An authorized grant is checked with [verifiedSession] before it is reported. */
    suspend fun pollDeviceAuthorization(authorization: TidalDeviceAuthorization): TidalDeviceLinkPoll {
        val client = device ?: return TidalDeviceLinkPoll.Failed
        val (status, body) = form(
            "$auth/token",
            listOf(
                "client_id" to client.clientId,
                "client_secret" to client.clientSecret,
                "device_code" to authorization.deviceCode,
                "grant_type" to DEVICE_GRANT,
                "scope" to FULL_SCOPE,
            ),
        )
        if (status in 200..299) {
            val grant = body?.let(::grantOf) ?: return TidalDeviceLinkPoll.Failed
            val session = verifiedSession(grant, TidalAuthFlowDevice) ?: return TidalDeviceLinkPoll.Failed
            return TidalDeviceLinkPoll.Authorized(session)
        }
        return when (body?.text("error")) {
            "authorization_pending" -> TidalDeviceLinkPoll.Pending
            "slow_down" -> TidalDeviceLinkPoll.SlowDown
            "expired_token" -> TidalDeviceLinkPoll.Expired
            "access_denied" -> TidalDeviceLinkPoll.Denied
            else -> TidalDeviceLinkPoll.Failed
        }
    }

    /**
     * Exchanges [refreshToken] for a new access token with the client of [flow]. The full scope is asked first; when TIDAL
     * answers invalid_scope, as it does for tokens issued with the narrower scope, the narrower scope is asked once. A
     * captured web session has no refresh client and answers as unavailable.
     */
    suspend fun refresh(refreshToken: String, flow: String): TidalRefreshResult {
        if (refreshToken.isBlank() || flow == TidalAuthFlowWebCapture) return TidalRefreshResult.Unavailable
        val client = (if (flow == TidalAuthFlowPkce) pkce else device) ?: return TidalRefreshResult.Unavailable
        for (scope in listOf(FULL_SCOPE, REDUCED_SCOPE)) {
            val (status, body) = try {
                form(
                    "$auth/token",
                    listOf(
                        "client_id" to client.clientId,
                        "client_secret" to client.clientSecret,
                        "refresh_token" to refreshToken,
                        "grant_type" to "refresh_token",
                        "scope" to scope,
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return TidalRefreshResult.Unavailable
            }
            if (status in 200..299) {
                return body?.let(::grantOf)?.let { TidalRefreshResult.Refreshed(it) } ?: TidalRefreshResult.Unavailable
            }
            val error = body?.text("error")
            val description = body?.text("error_description").orEmpty()
            when {
                error == "invalid_scope" -> continue
                description.contains("Client id", ignoreCase = true) && description.contains("not found", ignoreCase = true) ->
                    return TidalRefreshResult.RetiredClient
                error == "invalid_grant" || error == "invalid_client" || status == 401 -> return TidalRefreshResult.Rejected
                else -> return TidalRefreshResult.Unavailable
            }
        }
        return TidalRefreshResult.Rejected
    }

    /**
     * Checks [grant] with TIDAL's session endpoint and reads the subscription. The session's user id is the account; a grant
     * whose session names no user, or names another user than the grant, is refused. [refreshFallback] is kept when the
     * grant did not rotate the refresh token.
     */
    suspend fun verifiedSession(
        grant: TidalTokenGrant,
        flow: String,
        refreshFallback: String? = null,
    ): TidalSessionFacts? {
        val session = try {
            http.json("$api/sessions", headers = bearer(grant.accessToken)).jsonObject
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
        val userId = session.long("userId")?.takeIf { it > 0L } ?: return null
        if (grant.userId != null && grant.userId != userId) return null
        val country = session.text("countryCode") ?: grant.countryCode
        return TidalSessionFacts(
            accessToken = grant.accessToken,
            refreshToken = grant.refreshToken ?: refreshFallback.orEmpty(),
            expiresAtMillis = nowMs() + grant.expiresInSeconds.coerceAtLeast(60L) * 1_000L,
            authFlow = flow,
            userId = userId,
            countryCode = country,
            accountName = session.text("username") ?: grant.username,
            subscription = subscription(grant.accessToken, userId, country ?: DEFAULT_COUNTRY),
        )
    }

    /** Verifies a bearer captured from the web player. It has no refresh token, so its session expires within the hour. */
    suspend fun sessionFromBearer(accessToken: String): TidalSessionFacts? =
        verifiedSession(
            TidalTokenGrant(accessToken, null, WEB_BEARER_LIFETIME_SECONDS, null, null, null),
            TidalAuthFlowWebCapture,
        )

    /**
     * A fresh S256 PKCE challenge and the authorize URL the login browser loads, as the baseline
     * built them: the verifier is 64 random bytes base64url-encoded, the challenge its SHA-256,
     * and the unique key 8 random bytes as hex. Hosts that can inject cryptographic randomness
     * (SecureRandom, SecRandomCopyBytes) should pass [randomBytes]; the default is fine for the
     * shape of the values but not their entropy.
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun startPkceAuthorization(
        randomBytes: (Int) -> ByteArray = { Random.nextBytes(it) },
    ): TidalPkceAuthorization? {
        val client = pkce ?: return null
        val verifier = Base64.UrlSafe.encode(randomBytes(64)).trimEnd('=')
        val challenge = Base64.UrlSafe.encode(hexToBytes(ContentDigest.sha256(verifier.encodeToByteArray())))
            .trimEnd('=')
        val uniqueKey = randomBytes(8).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        val url = buildString {
            append(PKCE_AUTHORIZE_ENDPOINT)
            append("?response_type=code")
            append("&redirect_uri=").append(PKCE_REDIRECT_URI.encodeURLParameter())
            append("&client_id=").append(client.clientId)
            append("&lang=EN")
            append("&appMode=android")
            append("&client_unique_key=").append(uniqueKey)
            append("&code_challenge=").append(challenge)
            append("&code_challenge_method=S256")
            append("&restrict_signup=true")
        }
        return TidalPkceAuthorization(verifier, challenge, uniqueKey, url, PKCE_REDIRECT_URI)
    }

    /**
     * Exchanges the authorization [code] from the redirect for access + refresh tokens, exactly as
     * the baseline's exchange did (client secret, unique key and verifier all go back). The grant
     * then runs through [verifiedSession] so a mismatched user id or dead session is a null, never
     * a stored token.
     */
    suspend fun exchangePkceCode(authorization: TidalPkceAuthorization, code: String): TidalSessionFacts? {
        val client = pkce ?: return null
        val (_, body) = try {
            form(
                "$auth/token",
                listOf(
                    "code" to code,
                    "client_id" to client.clientId,
                    "client_secret" to client.clientSecret,
                    "grant_type" to "authorization_code",
                    "redirect_uri" to authorization.redirectUri,
                    "scope" to FULL_SCOPE,
                    "code_verifier" to authorization.verifier,
                    "client_unique_key" to authorization.uniqueKey,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
        val grant = body?.let(::grantOf) ?: return null
        return verifiedSession(grant, TidalAuthFlowPkce)
    }

    /**
     * The subscription tier, read as the Android account manager reads it: premiumAccess first, then a FREE type, the
     * highest sound quality and the known paid type names. Anything indeterminate is UNKNOWN.
     */
    suspend fun subscription(accessToken: String, userId: Long, countryCode: String): TidalSubscriptionStatus {
        val root = try {
            http.json(
                "$api/users/$userId/subscription",
                params = mapOf("countryCode" to countryCode),
                headers = bearer(accessToken),
            ).jsonObject
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return TidalSubscriptionStatus.UNKNOWN
        }
        val type = root.obj("subscription")?.text("type")?.uppercase().orEmpty()
        val quality = root.text("highestSoundQuality")?.uppercase().orEmpty()
        return when {
            root.containsKey("premiumAccess") ->
                if (root.flag("premiumAccess")) TidalSubscriptionStatus.PREMIUM else TidalSubscriptionStatus.FREE
            "FREE" in type -> TidalSubscriptionStatus.FREE
            "LOSSLESS" in quality || "HI_RES" in quality -> TidalSubscriptionStatus.PREMIUM
            quality == "LOW" -> TidalSubscriptionStatus.FREE
            "HIFI" in type || "PREMIUM" in type || "PLUS" in type -> TidalSubscriptionStatus.PREMIUM
            else -> TidalSubscriptionStatus.UNKNOWN
        }
    }

    private fun bearer(token: String) = mapOf("Authorization" to "Bearer $token")

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun grantOf(body: JsonObject): TidalTokenGrant? {
        val access = body.text("access_token") ?: return null
        val user = body.obj("user")
        return TidalTokenGrant(
            accessToken = access,
            refreshToken = body.text("refresh_token"),
            expiresInSeconds = body.long("expires_in") ?: DEFAULT_TOKEN_LIFETIME_SECONDS,
            userId = user?.long("userId")?.takeIf { it > 0L } ?: body.long("user_id")?.takeIf { it > 0L },
            countryCode = user?.text("countryCode"),
            username = user?.text("username"),
        )
    }

    /** Posts one form. The status and the parsed object come back; an unparsable body is null, never its text. */
    private suspend fun form(url: String, fields: List<Pair<String, String>>): Pair<Int, JsonObject?> =
        withTimeoutOrNull(FORM_TIMEOUT_MS) {
            try {
                raw.prepareForm(url, Parameters.build { fields.forEach { (name, value) -> append(name, value) } }) {
                    header(HttpHeaders.Accept, "application/json")
                    header(HttpHeaders.UserAgent, AUTH_USER_AGENT)
                }.execute { response ->
                    val text = response.readBoundedText(MAX_AUTH_BODY_BYTES)
                    val parsed = try {
                        Json.parseToJsonElement(text) as? JsonObject
                    } catch (_: Exception) {
                        null
                    }
                    response.status.value to parsed
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                throw ProviderProtocolFailure()
            }
        } ?: throw ProviderRequestTimeout()

    private companion object {
        const val FULL_SCOPE = "r_usr+w_usr+w_sub"
        const val PKCE_AUTHORIZE_ENDPOINT = "https://login.tidal.com/authorize"
        const val PKCE_REDIRECT_URI = TIDAL_PKCE_REDIRECT_URI
        const val REDUCED_SCOPE = "r_usr+w_usr"
        const val DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"
        const val AUTH_USER_AGENT = "TIDAL/1000 (Linux; Android 10)"
        const val DEFAULT_COUNTRY = "US"
        const val DEFAULT_DEVICE_EXPIRY_SECONDS = 300L
        const val DEFAULT_DEVICE_INTERVAL_SECONDS = 2L
        const val DEFAULT_TOKEN_LIFETIME_SECONDS = 3_600L
        const val WEB_BEARER_LIFETIME_SECONDS = 3_600L
        const val FORM_TIMEOUT_MS = 15_000L
        const val MAX_AUTH_BODY_BYTES = 256 * 1024
    }
}

/** The settings page's device sign-in over [auth]. The page owns the poll loop; this makes one request per call. */
class TidalAccountDeviceLink(private val auth: TidalAccountAuth) : TidalDeviceLink {
    override suspend fun start(): TidalDeviceAuthorization? = auth.startDeviceAuthorization()

    override suspend fun poll(authorization: TidalDeviceAuthorization): TidalDeviceLinkPoll =
        try {
            auth.pollDeviceAuthorization(authorization)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: ProviderRequestTimeout) {
            // One slow poll is not a refusal; the next poll asks again until the code expires.
            TidalDeviceLinkPoll.Pending
        }
}
