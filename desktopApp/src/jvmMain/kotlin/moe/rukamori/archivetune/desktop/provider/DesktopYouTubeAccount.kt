package moe.rukamori.archivetune.desktop.provider

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import java.security.MessageDigest
import java.util.Locale
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

/*
 * Signed-in account requests for the desktop host, mirroring the baseline core `YouTube.accountInfo`,
 * `accountChannels` and `accountDataSyncId`: account_menu on WEB_REMIX and accounts_list on a cookie-enabled WEB
 * client, both authenticated by the cookie and its SAPISIDHASH. These requests read the account only. The player layer
 * in DesktopYouTubeClients signs its cookie-capable requests with the same SAPISIDHASH through [youTubeSapisidAuthorization].
 */

private const val MAX_ACCOUNT_RESPONSE_CHARS = 2 * 1024 * 1024

/** Login cookie names in the order the baseline checks them. */
private val LOGIN_COOKIE_NAMES = listOf("SAPISID", "__Secure-3PAPISID", "__Secure-1PAPISID")

/** The baseline accountSwitcherClient: WEB with cookie authentication enabled. */
private val ACCOUNT_SWITCHER_CLIENT =
    DesktopYouTubeClients.WEB.copy(loginSupported = true, supportsCookieAuthentication = true)

/** What accounts_list reports for a fresh cookie: the active data-sync id, the visitor data and the channels. */
internal class DesktopYouTubeAccountSession(
    val dataSyncId: String?,
    val visitorData: String?,
    val channels: List<YouTubeAccountChannel>,
) {
    override fun toString(): String = "DesktopYouTubeAccountSession(redacted)"
}

/** The login cookie value the SAPISIDHASH signs, or null when the cookie is not a signed-in session. */
internal fun youTubeLoginCookieValue(cookie: String): String? {
    val fields = parseCookieFields(cookie)
    return LOGIN_COOKIE_NAMES.firstNotNullOfOrNull { name -> fields[name]?.takeIf(String::isNotBlank) }
}

/**
 * Asks account_menu who [candidate] belongs to. Null means the session is not signed in or the answer names no
 * account; transport faults propagate so the caller reports them as a failed verification.
 */
internal suspend fun verifyYouTubeAccountMenu(http: HttpClient, candidate: YouTubeSessionCandidate): YouTubeAccountIdentity? {
    val response = postSignedIn(
        http, DesktopYouTubeClients.MAIN_CLIENT, "account/account_menu",
        candidate.cookie, candidate.visitorData, candidate.dataSyncId,
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
internal suspend fun fetchYouTubeAccountSession(
    http: HttpClient,
    cookie: String,
    visitorData: String?,
    dataSyncId: String?,
): DesktopYouTubeAccountSession? {
    val response = postSignedIn(http, ACCOUNT_SWITCHER_CLIENT, "account/accounts_list", cookie, visitorData, dataSyncId)
        ?: return null
    val context = response["responseContext"] as? JsonObject
    val channels = response.objectsNamed("accountItemRenderer").mapNotNull(::parseAccountChannel).toList()
    val selected = channels.sortedByDescending { it.selected }.firstOrNull()?.channel?.dataSyncId
    val sessionDataSyncId = (context.child("mainAppWebResponseContext").child("dataSyncId") as? JsonPrimitive)
        ?.contentOrNull?.normalizeAccountChannelDataSyncId() ?: selected
    return DesktopYouTubeAccountSession(
        dataSyncId = sessionDataSyncId,
        visitorData = (context?.get("visitorData") as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank),
        channels = channels.sortedByDescending { it.selected }.map { it.channel }.distinctBy { it.dataSyncId },
    )
}

private suspend fun postSignedIn(
    http: HttpClient,
    client: DesktopYouTubeClient,
    endpoint: String,
    cookie: String,
    visitorData: String?,
    dataSyncId: String?,
): JsonObject? {
    val loginValue = youTubeLoginCookieValue(cookie) ?: return null
    val locale = Locale.getDefault()
    val region = locale.country.takeIf { it.length == 2 }?.uppercase(Locale.US) ?: "US"
    val language = locale.toLanguageTag().ifBlank { "en" }
    val body = buildJsonObject {
        putJsonObject("context") {
            putJsonObject("client") {
                put("clientName", client.clientName)
                put("clientVersion", client.clientVersion)
                put("gl", region)
                put("hl", language)
                visitorData?.let { put("visitorData", it) }
            }
            putJsonObject("user") {
                dataSyncId.delegatedSessionIdOrNull()?.let { put("onBehalfOfUser", it) }
            }
        }
    }
    val origin = client.requestOrigin()
    val text = withTransientRetry {
        val now = System.currentTimeMillis() / 1000
        val response = http.post(client.requestApiUrl(endpoint)) {
            parameter("prettyPrint", false)
            contentType(ContentType.Application.Json)
            header(HttpHeaders.UserAgent, client.userAgent)
            header("X-Goog-Api-Format-Version", "1")
            header("X-YouTube-Client-Name", client.clientId)
            header("X-YouTube-Client-Version", client.clientVersion)
            header("X-Origin", origin)
            header("Referer", client.requestReferer())
            visitorData?.let { header("X-Goog-Visitor-Id", it) }
            header(HttpHeaders.Cookie, cookie)
            header(HttpHeaders.Authorization, youTubeSapisidAuthorization(loginValue, origin, now))
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

/** The baseline `delegatedSessionIdOrNull`: the delegated part of "delegated||gaia", or null for a primary account. */
internal fun String?.delegatedSessionIdOrNull(): String? {
    val value = this?.trim()?.takeIf(String::isNotBlank) ?: return null
    val separator = value.indexOf("||")
    if (separator <= 0 || separator + 2 >= value.length) return null
    return value.substring(0, separator).trim().takeIf(String::isNotBlank)
}

/** The baseline `normalizeAccountChannelDataSyncId`. */
private fun String.normalizeAccountChannelDataSyncId(): String? =
    trim().takeIf(String::isNotBlank)?.let { value ->
        value.takeIf { !it.contains("||") }
            ?: value.takeIf { it.endsWith("||") }?.substringBefore("||")
            ?: value.substringAfter("||")
    }?.trim()?.takeIf(String::isNotBlank)

private val DELEGATION_KEYS = setOf("onBehalfOfUser", "obfuscatedSelectedGaiaId", "obfuscatedGaiaId", "accountId", "delegatedSessionId")
private val DELEGATION_FALLBACK_KEYS = setOf("selectedSerializedDelegationContext", "serializedDelegationContext")

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

private fun parseCookieFields(cookie: String): Map<String, String> =
    cookie.split(";").map { it.trim() }.filter { it.isNotBlank() }.mapNotNull { part ->
        val split = part.indexOf('=')
        if (split == -1) null else part.substring(0, split).trim().takeIf { it.isNotEmpty() }?.let { it to part.substring(split + 1).trim() }
    }.toMap()

/** The baseline SAPISIDHASH Authorization value for [origin], signed with the login cookie value at [nowSeconds]. */
internal fun youTubeSapisidAuthorization(loginValue: String, origin: String, nowSeconds: Long): String =
    "SAPISIDHASH ${nowSeconds}_${sha1Hex("$nowSeconds $loginValue $origin")}"

private fun sha1Hex(value: String): String =
    MessageDigest.getInstance("SHA-1").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
