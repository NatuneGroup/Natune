package moe.rukamori.archivetune.shared.domain.settings

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import moe.rukamori.archivetune.shared.domain.platform.ContentDigest

/**
 * The Discord OAuth application id, an intentionally public client constant: the same default the Android build
 * carries and the desktop host uses. The OAuth client has no confidential secret — PKCE proves the caller.
 */
const val DISCORD_APPLICATION_ID = "1165706613961789445"

/** The redirect the application registers, the same string the Android build sends. */
const val DISCORD_REDIRECT_URI = "discord-$DISCORD_APPLICATION_ID:/authorize/callback"
const val DISCORD_OAUTH_SCOPES = "openid identify sdk.social_layer_presence"
const val DISCORD_AUTHORIZE_ENDPOINT = "https://discord.com/oauth2/authorize"
const val DISCORD_TOKEN_ENDPOINT = "https://discord.com/api/oauth2/token"
const val DISCORD_CURRENT_USER_ENDPOINT = "https://discord.com/api/v10/users/@me"
const val DISCORD_GATEWAY_URL = "wss://gateway.discord.gg/?v=10&encoding=json"

private const val DISCORD_HEX = "0123456789ABCDEF"

private val discordProtocolJson = Json { ignoreUnknownKeys = true }

/**
 * The authorize URL the host's browser surface loads: PKCE S256, the public application id and the fixed scopes the
 * AGENTS.md Discord invariant prescribes. [state] and [challenge] come from the host's secure generator.
 */
fun discordAuthorizeUrl(state: String, challenge: String): String =
    "$DISCORD_AUTHORIZE_ENDPOINT?client_id=$DISCORD_APPLICATION_ID" +
        "&response_type=code&redirect_uri=${discordFormEncode(DISCORD_REDIRECT_URI)}" +
        "&scope=${discordFormEncode(DISCORD_OAUTH_SCOPES)}&state=$state" +
        "&code_challenge=$challenge&code_challenge_method=S256"

/** The S256 challenge of [verifier]: base64url without padding, as the PKCE authorization requires. */
@OptIn(ExperimentalEncodingApi::class)
fun discordPkceChallenge(verifier: String): String =
    Base64.UrlSafe.encode(hexToBytes(ContentDigest.sha256(verifier.encodeToByteArray()))).trimEnd('=')

/** The form body of the authorization-code exchange: PKCE verifier, public client, no secret. */
fun discordTokenExchangeBody(code: String, verifier: String): String =
    "client_id=$DISCORD_APPLICATION_ID&grant_type=authorization_code" +
        "&code=${discordFormEncode(code)}&redirect_uri=${discordFormEncode(DISCORD_REDIRECT_URI)}" +
        "&code_verifier=${discordFormEncode(verifier)}"

/** The form body of a refresh grant: refresh token and public client only, never a secret. */
fun discordTokenRefreshBody(refreshToken: String): String =
    "client_id=$DISCORD_APPLICATION_ID&grant_type=refresh_token" +
        "&refresh_token=${discordFormEncode(refreshToken)}"

/** One completed token response: access token, optional refresh token and lifetime in seconds. */
class DiscordTokenGrant(val accessToken: String, val refreshToken: String?, val expiresInSeconds: Long) {
    override fun toString(): String = "DiscordTokenGrant(redacted)"
}

/** Parses a token endpoint body. Missing or malformed payloads are null, never partially trusted. */
fun parseDiscordTokenGrant(body: String): DiscordTokenGrant? = runCatching {
    val root = discordProtocolJson.parseToJsonElement(body).jsonObject
    val access = root["access_token"]?.jsonPrimitive?.content ?: return null
    DiscordTokenGrant(
        accessToken = access,
        refreshToken = root["refresh_token"]?.jsonPrimitive?.content,
        expiresInSeconds = root["expires_in"]?.jsonPrimitive?.longOrNull ?: 0L,
    )
}.getOrNull()

/** Parses the `users/@me` body into profile facts; the avatar joins the CDN URL only when both parts exist. */
fun parseDiscordAccountFacts(body: String): DiscordAccountFacts? = runCatching {
    val root = discordProtocolJson.parseToJsonElement(body).jsonObject
    val username = root["username"]?.jsonPrimitive?.content ?: return null
    val display = root["global_name"]?.jsonPrimitive?.content ?: username
    val id = root["id"]?.jsonPrimitive?.content
    val avatar = root["avatar"]?.jsonPrimitive?.content
    val avatarUrl = if (id != null && avatar != null) "https://cdn.discordapp.com/avatars/$id/$avatar.png" else null
    DiscordAccountFacts(username = username, displayName = display, avatarUrl = avatarUrl)
}.getOrNull()

/**
 * The minimal IDENTIFY the invariant prescribes: intents 0, no capabilities, gateway v10, no compression. [osName]
 * names the host the way the shipped pages report it.
 */
fun buildDiscordIdentifyJson(token: String, osName: String): String = buildJsonObject {
    put("op", 2)
    put("d", buildJsonObject {
        put("token", "Bearer $token")
        put("intents", 0)
        put("properties", buildJsonObject {
            put("os", osName)
            put("browser", "ArchiveTune")
            put("device", "ArchiveTune")
        })
    })
}.toString()

/** One heartbeat frame. [sequence] is the last seen `s`, or null before the first dispatch. */
fun buildDiscordHeartbeatJson(sequence: Int?): String = buildJsonObject {
    put("op", 1)
    put("d", sequence)
}.toString()

/** The fields of a gateway event the session needs: opcode, the hello heartbeat interval and the dispatch sequence. */
data class DiscordGatewayEvent(val op: Int, val heartbeatIntervalMs: Long?, val sequence: Int?)

/** Parses one gateway text frame. Malformed frames are null; the caller ignores them as the shipped client does. */
fun parseDiscordGatewayEvent(frame: String): DiscordGatewayEvent? = runCatching {
    val event = discordProtocolJson.parseToJsonElement(frame).jsonObject
    val op = event["op"]?.jsonPrimitive?.intOrNull ?: return null
    DiscordGatewayEvent(
        op = op,
        heartbeatIntervalMs = event["d"]?.jsonObject?.get("heartbeat_interval")?.jsonPrimitive?.longOrNull,
        sequence = event["s"]?.jsonPrimitive?.intOrNull,
    )
}.getOrNull()

/** The display facts of the current track a presence update may carry. Everything null means no track is known. */
data class DiscordPresenceTrack(
    val title: String?,
    val artists: List<String> = emptyList(),
    val album: String? = null,
    val artworkUri: String? = null,
)

/**
 * The op 3 presence payload shaped exactly like the shipped client's: name/details/state/timestamps/assets/type.
 * Timestamps go out only for an unpaused track with known duration and position. [track] carries the metadata the
 * host resolved for its current occurrence.
 */
fun buildDiscordPresenceJson(
    preferences: PreferenceSnapshot,
    track: DiscordPresenceTrack?,
    positionMs: Long?,
    durationMs: Long?,
    paused: Boolean,
    nowMillis: Long,
): String {
    val status = preferences[DiscordPresenceStatusKey, DiscordSettingsDefaults.PRESENCE_STATUS]
    val activityType = when (preferences[DiscordActivityTypeKey, DiscordSettingsDefaults.ACTIVITY_TYPE]) {
        "PLAYING" -> 0
        "STREAMING" -> 1
        "LISTENING" -> 2
        "WATCHING" -> 3
        "COMPETING" -> 5
        else -> 2
    }
    val name = when (preferences.discordActivitySource(DiscordActivityNameKey, DiscordSettingsDefaults.ACTIVITY_NAME_SOURCE)) {
        DiscordActivitySource.APP -> "ArchiveTune"
        else -> track?.title ?: "ArchiveTune"
    }
    val details = when (preferences.discordActivitySource(DiscordActivityDetailsKey, DiscordSettingsDefaults.ACTIVITY_DETAILS_SOURCE)) {
        DiscordActivitySource.ARTIST -> track?.artists?.joinToString(", ")?.takeIf(String::isNotEmpty)
        DiscordActivitySource.ALBUM -> track?.album
        DiscordActivitySource.SONG -> track?.title
        DiscordActivitySource.APP -> "ArchiveTune"
    }
    val state = when (preferences.discordActivitySource(DiscordActivityStateKey, DiscordSettingsDefaults.ACTIVITY_STATE_SOURCE)) {
        DiscordActivitySource.ARTIST -> track?.artists?.joinToString(", ")?.takeIf(String::isNotEmpty)
        DiscordActivitySource.ALBUM -> track?.album
        DiscordActivitySource.SONG -> track?.title
        DiscordActivitySource.APP -> "ArchiveTune"
    }
    val activity = buildJsonObject {
        put("name", name)
        put("type", activityType)
        details?.let { put("details", it) }
        state?.let { put("state", it) }
        if (track != null && !paused && (durationMs ?: 0) > 0 && (positionMs ?: -1) >= 0) {
            put("timestamps", buildJsonObject {
                put("start", nowMillis - (positionMs ?: 0))
                put("end", nowMillis + ((durationMs ?: 0) - (positionMs ?: 0)))
            })
        }
        val assets = buildJsonObject {
            resolveDiscordImage(
                preferences[DiscordLargeImageTypeKey, DiscordSettingsDefaults.LARGE_IMAGE],
                preferences[DiscordLargeImageCustomUrlKey, ""],
                track?.artworkUri,
            )?.let { put("large_image", it) }
            resolveDiscordImage(
                preferences[DiscordSmallImageTypeKey, DiscordSettingsDefaults.SMALL_IMAGE],
                preferences[DiscordSmallImageCustomUrlKey, ""],
                track?.artworkUri,
            )?.let { put("small_image", it) }
        }
        if (assets.isNotEmpty()) put("assets", assets)
    }
    return buildJsonObject {
        put("op", 3)
        put("d", buildJsonObject {
            put("status", status)
            put("afk", false)
            put("since", JsonNull)
            put("activities", buildJsonArray { add(activity) })
        })
    }.toString()
}

/**
 * The image value one assets field carries: a custom URL, the track artwork for thumbnail/artist, the app icon key,
 * or nothing for dontshow and unknown values — the same choices the settings page offers.
 */
fun resolveDiscordImage(type: String, custom: String, artworkUri: String?): String? = when (type) {
    "custom" -> custom.takeIf { it.isNotBlank() }
    "thumbnail", "artist" -> artworkUri
    "appicon" -> "archivetune"
    else -> null
}

/** Reads one decoded query value of a callback URI, the way the shipped client reads `code` and `state`. */
fun discordQueryParam(uri: String, name: String): String? {
    val query = uri.substringAfter('?', "").substringBefore('#')
    if (query.isEmpty()) return null
    return query.split('&').firstNotNullOfOrNull { part ->
        val pieces = part.split('=', limit = 2)
        if (pieces.size == 2 && pieces[0] == name) discordPercentDecode(pieces[1]) else null
    }
}

/**
 * Percent-encodes one form value over its UTF-8 bytes, keeping the unreserved set. The produced form fields match
 * what the shipped client's encoder emits for every value this flow sends.
 */
internal fun discordFormEncode(value: String): String {
    val out = StringBuilder()
    for (byte in value.encodeToByteArray()) {
        val c = byte.toInt() and 0xff
        val unreserved = c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code || c in '0'.code..'9'.code ||
            c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code
        if (unreserved) out.append(c.toChar())
        else out.append('%').append(DISCORD_HEX[c shr 4]).append(DISCORD_HEX[c and 0x0f])
    }
    return out.toString()
}

private fun discordPercentDecode(value: String): String {
    if (!value.contains('%') && !value.contains('+')) return value
    val bytes = ArrayList<Byte>(value.length)
    var i = 0
    while (i < value.length) {
        val c = value[i]
        when {
            c == '+' -> { bytes.add(' '.code.toByte()); i += 1 }
            c == '%' && i + 2 < value.length -> {
                val hi = hexDigitValue(value[i + 1])
                val lo = hexDigitValue(value[i + 2])
                if (hi < 0 || lo < 0) { bytes.add(c.code.toByte()); i += 1 }
                else { bytes.add(((hi shl 4) or lo).toByte()); i += 3 }
            }
            else -> { bytes.add(c.code.toByte()); i += 1 }
        }
    }
    return bytes.toByteArray().decodeToString()
}

private fun hexDigitValue(c: Char): Int = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    in 'A'..'F' -> c - 'A' + 10
    else -> -1
}

private fun hexToBytes(hex: String): ByteArray =
    ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
