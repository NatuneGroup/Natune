package moe.rukamori.archivetune.shared.domain.source

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import moe.rukamori.archivetune.shared.domain.platform.ContentDigest

/**
 * The OAuth endpoints the Librespot engine's sign-in uses. The OAuth application id is a host build
 * value (`ARCHIVETUNE_SPOTIFY_OAUTH_CLIENT_ID`, see docs/build-credentials.md), not a source constant:
 * it is a public client identifier PKCE, never a secret, and a build that lacks it reports the flow
 * as not configured instead of borrowing someone else's id.
 */
const val SPOTIFY_OAUTH_AUTHORIZE_ENDPOINT = "https://accounts.spotify.com/authorize"
const val SPOTIFY_OAUTH_TOKEN_ENDPOINT = "https://accounts.spotify.com/api/token"
const val SPOTIFY_OAUTH_ME_ENDPOINT = "https://api.spotify.com/v1/me"

/**
 * The scopes the Librespot session token carries and nothing more: `streaming` is what a librespot
 * access-token session needs to register as a Connect device and play, `user-read-private` is what
 * the `/v1/me` `product` read needs for the Premium check. Scopes stay minimal on purpose.
 */
const val SPOTIFY_LIBRESPOT_OAUTH_SCOPES = "streaming user-read-private"

private const val OAUTH_HEX = "0123456789ABCDEF"

private val spotifyOAuthJson = Json { ignoreUnknownKeys = true }

/**
 * The authorize URL the host's system browser loads: authorization-code with PKCE S256. [clientId] and
 * [redirectUri] come from the host — the registered app, and `natune://spotify-callback` on Android and
 * iOS or the `127.0.0.1` loopback listener on desktop. [state] and [challenge] come from the host's
 * secure generator.
 */
fun spotifyOAuthAuthorizeUrl(clientId: String, redirectUri: String, state: String, challenge: String): String =
    "$SPOTIFY_OAUTH_AUTHORIZE_ENDPOINT?client_id=${spotifyFormEncode(clientId)}" +
        "&response_type=code&redirect_uri=${spotifyFormEncode(redirectUri)}" +
        "&scope=${spotifyFormEncode(SPOTIFY_LIBRESPOT_OAUTH_SCOPES)}&state=$state" +
        "&code_challenge=$challenge&code_challenge_method=S256"

/** The S256 challenge of [verifier]: base64url without padding, as the PKCE authorization requires. */
@OptIn(ExperimentalEncodingApi::class)
fun spotifyPkceChallenge(verifier: String): String =
    Base64.UrlSafe.encode(hexToBytes(ContentDigest.sha256(verifier.encodeToByteArray()))).trimEnd('=')

/** The form body of the authorization-code exchange: PKCE verifier, public client, no secret. */
fun spotifyTokenExchangeBody(clientId: String, redirectUri: String, code: String, verifier: String): String =
    "client_id=${spotifyFormEncode(clientId)}&grant_type=authorization_code" +
        "&code=${spotifyFormEncode(code)}&redirect_uri=${spotifyFormEncode(redirectUri)}" +
        "&code_verifier=${spotifyFormEncode(verifier)}"

/** The form body of a refresh grant: refresh token and public client only, never a secret. */
fun spotifyTokenRefreshBody(clientId: String, refreshToken: String): String =
    "client_id=${spotifyFormEncode(clientId)}&grant_type=refresh_token" +
        "&refresh_token=${spotifyFormEncode(refreshToken)}"

/** One completed token response: access token, the rotating refresh token and lifetime in seconds. */
class SpotifyOAuthTokenGrant(val accessToken: String, val refreshToken: String?, val expiresInSeconds: Long) {
    override fun toString(): String = "SpotifyOAuthTokenGrant(redacted)"
}

/**
 * Parses a token endpoint body into a grant. `access_token` is required; `refresh_token` is optional
 * because Spotify only returns one when it rotates — callers keep the stored refresh token then.
 * Missing or malformed payloads are null, never partially trusted.
 */
fun parseSpotifyOAuthTokenGrant(body: String): SpotifyOAuthTokenGrant? = runCatching {
    val root = spotifyOAuthJson.parseToJsonElement(body).jsonObject
    val access = root["access_token"]?.jsonPrimitive?.content ?: return null
    SpotifyOAuthTokenGrant(
        accessToken = access,
        refreshToken = root["refresh_token"]?.jsonPrimitive?.content,
        expiresInSeconds = root["expires_in"]?.jsonPrimitive?.longOrNull ?: 0L,
    )
}.getOrNull()

/**
 * The `/v1/me` facts the Librespot sign-in needs: the account id for the same-account check against the
 * catalog sign-in, `product` for the Premium gate, `display_name` for the settings row.
 */
class SpotifyOAuthAccountFacts(val userId: String, val displayName: String?, val product: String?)

/** Parses a `/v1/me` body into [SpotifyOAuthAccountFacts]. A missing id makes the read unusable. */
fun parseSpotifyOAuthMe(body: String): SpotifyOAuthAccountFacts? = runCatching {
    val root = spotifyOAuthJson.parseToJsonElement(body).jsonObject
    val id = root["id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return null
    SpotifyOAuthAccountFacts(
        userId = id,
        displayName = root["display_name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
        product = root["product"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
    )
}.getOrNull()

/**
 * Percent-encodes one form value over its UTF-8 bytes, keeping the unreserved set. The produced form
 * fields match what a standard encoder emits for every value this flow sends.
 */
private fun spotifyFormEncode(value: String): String {
    val out = StringBuilder()
    for (byte in value.encodeToByteArray()) {
        val c = byte.toInt() and 0xff
        val unreserved = c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code || c in '0'.code..'9'.code ||
            c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code
        if (unreserved) out.append(c.toChar())
        else out.append('%').append(OAUTH_HEX[c shr 4]).append(OAUTH_HEX[c and 0x0f])
    }
    return out.toString()
}

private fun hexToBytes(hex: String): ByteArray =
    ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
