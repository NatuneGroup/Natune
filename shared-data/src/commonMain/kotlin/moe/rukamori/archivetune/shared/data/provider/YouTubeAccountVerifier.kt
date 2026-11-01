package moe.rukamori.archivetune.shared.data.provider

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.utils.io.errors.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import moe.rukamori.archivetune.shared.domain.settings.YouTubeAccountChannel
import moe.rukamori.archivetune.shared.domain.settings.YouTubeAccountIdentity
import moe.rukamori.archivetune.shared.domain.settings.YouTubeSessionCandidate
import kotlin.time.Clock

/*
 * Signed-in account reads shared by every host, mirroring the baseline core `YouTube.accountInfo`, `accountChannels`
 * and `accountDataSyncId`: account_menu on WEB_REMIX and accounts_list on a cookie-enabled WEB client, both
 * authenticated by the cookie and its SAPISIDHASH. These functions only read the account. A host commits a verified
 * result itself, in one settings edit, and keeps the previous session when verification fails.
 */

private const val MAX_ACCOUNT_RESPONSE_CHARS = 2 * 1024 * 1024
private const val MAX_SW_JS_DATA_CHARS = 2 * 1024 * 1024
private const val MAX_VISITOR_SEARCH_DEPTH = 6
private const val RETRY_ATTEMPTS = 3
private const val RETRY_INITIAL_DELAY_MS = 500L
private const val SW_JS_DATA_URL = "https://music.youtube.com/sw.js_data"
private const val YOUTUBE_WEB_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"

/** Login cookie names in the order the baseline checks them. */
private val LOGIN_COOKIE_NAMES = listOf("SAPISID", "__Secure-3PAPISID", "__Secure-1PAPISID")
private val VISITOR_DATA_REGEX = Regex("^Cg[t|s]")
private val DELEGATION_KEYS = setOf("onBehalfOfUser", "obfuscatedSelectedGaiaId", "obfuscatedGaiaId", "accountId", "delegatedSessionId")
private val DELEGATION_FALLBACK_KEYS = setOf("selectedSerializedDelegationContext", "serializedDelegationContext")

/** One InnerTube client identity for an account call. Both calls resolve to the music origin, as the core client does. */
private class AccountRequestClient(val clientName: String, val clientVersion: String, val clientId: String, val userAgent: String)

/** The client account_menu is read with: the WEB_REMIX identity the upload and player requests already send. */
private val ACCOUNT_MENU_CLIENT =
    AccountRequestClient(WEB_REMIX_CLIENT_NAME, WEB_REMIX_CLIENT_VERSION, WEB_REMIX_CLIENT_ID, WEB_REMIX_USER_AGENT)

/** The baseline accountSwitcherClient: WEB with cookie authentication, which accounts_list is read with. */
private val ACCOUNT_SWITCHER_CLIENT = AccountRequestClient("WEB", "2.20260114.00.00", "1", YOUTUBE_WEB_USER_AGENT)

/**
 * What accounts_list reports for a cookie: the active data-sync id, the visitor data and the channels. The values
 * identify the account, so the string form stays redacted.
 */
class YouTubeAccountSession(
    val dataSyncId: String?,
    val visitorData: String?,
    val channels: List<YouTubeAccountChannel>,
) {
    override fun toString(): String = "YouTubeAccountSession(redacted)"
}

/** The request locale of the account calls. Each host reads it from its own platform locale. */
data class YouTubeAccountLocale(val region: String, val language: String)

/**
 * Asks account_menu who [candidate] belongs to. Null means the session is not signed in or the answer names no
 * account; transport faults propagate so the caller reports them as a failed verification.
 */
suspend fun verifyYouTubeAccountMenu(
    http: HttpClient,
    candidate: YouTubeSessionCandidate,
    locale: YouTubeAccountLocale,
): YouTubeAccountIdentity? {
    val response = postSignedIn(
        http, ACCOUNT_MENU_CLIENT, "account/account_menu",
        candidate.cookie, candidate.visitorData, candidate.dataSyncId, locale,
    ) ?: return null
    val header = (response["actions"] as? JsonArray)?.firstOrNull()
        .child("openPopupAction").child("popup").child("multiPageMenuRenderer").child("header")
        .child("activeAccountHeaderRenderer") as? JsonObject ?: return null
    val name = header["accountName"].firstRunText() ?: return null
    return YouTubeAccountIdentity(
        name = name,
        email = header["email"].firstRunText(),
        channelHandle = header["channelHandle"].firstRunText(),
        imageUrl = header["accountPhoto"].lastThumbnailUrl(),
    )
}

/**
 * Reads accounts_list for [cookie]. The data-sync id comes from the response context first and otherwise from the
 * selected enabled channel, as the baseline does. Null means YouTube refused the request.
 */
suspend fun fetchYouTubeAccountSession(
    http: HttpClient,
    cookie: String,
    visitorData: String?,
    dataSyncId: String?,
    locale: YouTubeAccountLocale,
): YouTubeAccountSession? {
    val response = postSignedIn(
        http, ACCOUNT_SWITCHER_CLIENT, "account/accounts_list", cookie, visitorData, dataSyncId, locale,
    ) ?: return null
    val context = response["responseContext"] as? JsonObject
    val channels = response.objectsNamed("accountItemRenderer").mapNotNull(::parseAccountChannel).toList()
    val selected = channels.sortedByDescending { it.selected }.firstOrNull()?.channel?.dataSyncId
    val sessionDataSyncId = (context.child("mainAppWebResponseContext").child("dataSyncId") as? JsonPrimitive)
        ?.contentOrNull?.normalizeAccountChannelDataSyncId() ?: selected
    return YouTubeAccountSession(
        dataSyncId = sessionDataSyncId,
        visitorData = (context?.get("visitorData") as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank),
        channels = channels.sortedByDescending { it.selected }.map { it.channel }.distinctBy { it.dataSyncId },
    )
}

/**
 * Reads the anonymous visitor identity from the web player's service-worker data, the same source the shipped
 * `YouTube.visitorData()` uses. A failure is not fatal: the request is still sent, just without the visitor header,
 * which is the baseline's behaviour for a cold visitor.
 */
suspend fun fetchYouTubeVisitorData(http: HttpClient): String? {
    val raw = withTransientRetry {
        val response = http.get(SW_JS_DATA_URL) {
            header(HttpHeaders.UserAgent, YOUTUBE_WEB_USER_AGENT)
        }
        if (response.status.value !in 200..299) null else response.bodyAsText()
    } ?: return null
    if (raw.length > MAX_SW_JS_DATA_CHARS) return null
    // The payload is prefixed by five guard characters before its JSON array.
    val root = runCatching { Json.parseToJsonElement(raw.drop(5)) }.getOrNull() as? JsonArray ?: return null
    val section = (root.getOrNull(0) as? JsonArray)?.getOrNull(2) ?: return null
    return firstVisitorData(section, depth = 0)
}

/** The login cookie value the SAPISIDHASH signs, or null when the cookie is not a signed-in session. */
fun youTubeLoginCookieValue(cookie: String): String? {
    val fields = parseCookieFields(cookie)
    return LOGIN_COOKIE_NAMES.firstNotNullOfOrNull { name -> fields[name]?.takeIf(String::isNotBlank) }
}

/** The baseline SAPISIDHASH Authorization value for [origin], signed with the login cookie value at [nowSeconds]. */
fun youTubeSapisidAuthorization(loginValue: String, origin: String, nowSeconds: Long): String =
    YouTubeUploadAuth.authorization(loginValue, origin, nowSeconds)

/** The baseline `delegatedSessionIdOrNull`: the delegated part of "delegated||gaia", or null for a primary account. */
fun String?.delegatedSessionIdOrNull(): String? {
    val value = this?.trim()?.takeIf(String::isNotBlank) ?: return null
    val separator = value.indexOf("||")
    if (separator <= 0 || separator + 2 >= value.length) return null
    return value.substring(0, separator).trim().takeIf(String::isNotBlank)
}

/** Retries transient transport failures only. Protocol refusals are answers, not transient faults. */
suspend fun <T> withTransientRetry(block: suspend () -> T): T {
    var delayMs = RETRY_INITIAL_DELAY_MS
    var attempt = 1
    while (true) {
        try {
            return block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (transient: IOException) {
            if (attempt >= RETRY_ATTEMPTS) throw transient
            delay(delayMs)
            delayMs *= 2
            attempt++
        }
    }
}

private suspend fun postSignedIn(
    http: HttpClient,
    client: AccountRequestClient,
    endpoint: String,
    cookie: String,
    visitorData: String?,
    dataSyncId: String?,
    locale: YouTubeAccountLocale,
): JsonObject? {
    val loginValue = youTubeLoginCookieValue(cookie) ?: return null
    val body = buildJsonObject {
        putJsonObject("context") {
            putJsonObject("client") {
                put("clientName", client.clientName)
                put("clientVersion", client.clientVersion)
                put("gl", locale.region)
                put("hl", locale.language)
                visitorData?.let { put("visitorData", it) }
            }
            putJsonObject("user") {
                dataSyncId.delegatedSessionIdOrNull()?.let { put("onBehalfOfUser", it) }
            }
        }
    }
    val text = withTransientRetry {
        val now = Clock.System.now().epochSeconds
        val response = http.post("$YOUTUBE_MUSIC_ORIGIN/youtubei/v1/$endpoint") {
            parameter("prettyPrint", false)
            contentType(ContentType.Application.Json)
            header(HttpHeaders.UserAgent, client.userAgent)
            header("X-Goog-Api-Format-Version", "1")
            header("X-YouTube-Client-Name", client.clientId)
            header("X-YouTube-Client-Version", client.clientVersion)
            header("X-Origin", YOUTUBE_MUSIC_ORIGIN)
            header("Referer", "$YOUTUBE_MUSIC_ORIGIN/")
            visitorData?.let { header("X-Goog-Visitor-Id", it) }
            header(HttpHeaders.Cookie, cookie)
            header(HttpHeaders.Authorization, youTubeSapisidAuthorization(loginValue, YOUTUBE_MUSIC_ORIGIN, now))
            header("X-Goog-AuthUser", "0")
            setBody(body.toString())
        }
        if (response.status.value !in 200..299) null else response.bodyAsText()
    } ?: return null
    if (text.length > MAX_ACCOUNT_RESPONSE_CHARS) return null
    return runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject
}

private class ParsedChannel(val channel: YouTubeAccountChannel, val selected: Boolean)

private fun parseAccountChannel(renderer: JsonObject): ParsedChannel? {
    if (renderer.booleanValue("isDisabled") == true || renderer.booleanValue("hasChannel") == false) return null
    val dataSyncId = renderer["serviceEndpoint"]?.findDelegationValue()?.normalizeAccountChannelDataSyncId() ?: return null
    val name = renderer["accountName"].textValue() ?: return null
    return ParsedChannel(
        YouTubeAccountChannel(
            dataSyncId = dataSyncId,
            name = name,
            handle = renderer["channelHandle"].textValue(),
            imageUrl = renderer["accountPhoto"].lastThumbnailUrl(),
        ),
        selected = renderer.booleanValue("isSelected") == true,
    )
}

/** The baseline `normalizeAccountChannelDataSyncId`. */
private fun String.normalizeAccountChannelDataSyncId(): String? =
    trim().takeIf(String::isNotBlank)?.let { value ->
        value.takeIf { !it.contains("||") }
            ?: value.takeIf { it.endsWith("||") }?.substringBefore("||")
            ?: value.substringAfter("||")
    }?.trim()?.takeIf(String::isNotBlank)

private fun JsonElement.findDelegationValue(): String? = findStringValue(DELEGATION_KEYS) ?: findStringValue(DELEGATION_FALLBACK_KEYS)

private fun JsonElement.findStringValue(keys: Set<String>): String? = when (this) {
    is JsonObject -> keys.firstNotNullOfOrNull { key ->
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotBlank)
    } ?: values.firstNotNullOfOrNull { it.findStringValue(keys) }
    is JsonArray -> firstNotNullOfOrNull { it.findStringValue(keys) }
    else -> null
}

private fun JsonElement.objectsNamed(name: String): Sequence<JsonObject> = sequence {
    when (val element = this@objectsNamed) {
        is JsonObject -> {
            (element[name] as? JsonObject)?.let { yield(it) }
            element.values.forEach { yieldAll(it.objectsNamed(name)) }
        }
        is JsonArray -> element.forEach { yieldAll(it.objectsNamed(name)) }
        else -> {}
    }
}

private fun JsonElement?.child(key: String): JsonElement? = (this as? JsonObject)?.get(key)

private fun JsonObject.booleanValue(key: String): Boolean? = (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()

private fun JsonElement?.firstRunText(): String? =
    ((this.child("runs") as? JsonArray)?.firstOrNull().child("text") as? JsonPrimitive)?.contentOrNull

private fun JsonElement?.textValue(): String? {
    val obj = this as? JsonObject ?: return null
    (obj["simpleText"] as? JsonPrimitive)?.contentOrNull?.let { return it.trim().takeIf(String::isNotBlank) }
    return (obj["runs"] as? JsonArray)
        ?.joinToString(separator = "") { run -> (run.child("text") as? JsonPrimitive)?.contentOrNull.orEmpty() }
        ?.trim()?.takeIf(String::isNotBlank)
}

private fun JsonElement?.lastThumbnailUrl(): String? =
    (this.child("thumbnails") as? JsonArray)
        ?.mapNotNull { (it.child("url") as? JsonPrimitive)?.contentOrNull }
        ?.lastOrNull()
        ?.let { url -> if (url.startsWith("//")) "https:$url" else url }

/*
 * Last occurrence wins for a repeated cookie name, as the desktop host has always read its cookie header. The upload
 * path keeps first-wins for its own header, so the two parsers stay separate.
 */
private fun parseCookieFields(cookie: String): Map<String, String> =
    cookie.split(";").map { it.trim() }.filter { it.isNotBlank() }.mapNotNull { part ->
        val split = part.indexOf('=')
        if (split == -1) null else part.substring(0, split).trim().takeIf { it.isNotEmpty() }?.let { it to part.substring(split + 1).trim() }
    }.toMap()

private fun firstVisitorData(
    element: JsonElement,
    depth: Int,
): String? {
    if (depth > MAX_VISITOR_SEARCH_DEPTH) return null
    return when (element) {
        is JsonPrimitive ->
            if (element.isString) {
                element.contentOrNull?.takeIf { VISITOR_DATA_REGEX.containsMatchIn(it) }
            } else {
                null
            }
        is JsonArray -> element.firstNotNullOfOrNull { firstVisitorData(it, depth + 1) }
        is JsonObject -> element.values.firstNotNullOfOrNull { firstVisitorData(it, depth + 1) }
    }
}
