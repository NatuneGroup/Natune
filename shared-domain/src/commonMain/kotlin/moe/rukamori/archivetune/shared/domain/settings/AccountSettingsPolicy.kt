/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.settings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random

/**
 * YouTube account settings: the saved-account list, the identity facts the page shows, the pasted login block and the
 * one-edit commits for switch, channel change, manual import and sign-out. Verifying a candidate against the YouTube
 * account menu is host-owned. This file decides only what a verified candidate writes. The cookie, visitor data,
 * data-sync id, OAuth tokens and PO tokens are vault-routed by the store, so they are written only through a
 * [PreferenceEditor] and never copied into a log or a display string.
 */

/** Login cookie names in the order the baseline checks them. A signed-in session carries at least one of them. */
private val YouTubeSignInCookieNames = listOf("SAPISID", "__Secure-3PAPISID", "__Secure-1PAPISID")

/**
 * Marker keys of the pasted login block. They match the baseline export layout, so a block copied from an existing
 * install still imports without conversion.
 */
private const val ManualCookieKey = "***INNERTUBE COOKIE***"
private const val ManualVisitorKey = "***VISITOR DATA***"
private const val ManualDataSyncKey = "***DATASYNC ID***"
private const val ManualPoTokenKey = "***PO TOKEN***"

/**
 * A saved YouTube account, stored as a JSON array under the vault-routed `savedAccounts` key. Every field is required,
 * as in the baseline decoder: one unreadable entry rejects the whole list, so a partial read is never written back over
 * the stored list. The string form is redacted, so a log line or debugger never prints the cookie.
 */
data class YouTubeSavedAccount(
    val id: String,
    val name: String,
    val email: String,
    val channelHandle: String,
    val innerTubeCookie: String,
    val visitorData: String,
    val dataSyncId: String,
    val ytmSync: Boolean,
    val selectedYtmPlaylists: String,
) {
    override fun toString(): String = "YouTubeSavedAccount(redacted)"
}

/** Identity facts a host verification reported. Absent optional facts are null, never an empty placeholder. */
data class YouTubeAccountIdentity(
    val name: String,
    val email: String?,
    val channelHandle: String?,
    val imageUrl: String?,
)

/** One channel of a multi-channel account. The host reports channels; the page never fetches them itself. */
data class YouTubeAccountChannel(
    val dataSyncId: String,
    val name: String,
    val handle: String?,
    val imageUrl: String?,
)

/**
 * Facts from one pasted login block. The string form is redacted. [poToken] is the legacy token the baseline import
 * carried; it stays optional.
 */
class YouTubeManualAuth(
    val cookie: String,
    val visitorData: String?,
    val dataSyncId: String,
    val poToken: String?,
) {
    override fun toString(): String = "YouTubeManualAuth(redacted)"
}

/**
 * The facts a host verification checks before a session is committed: the cookie, the visitor data when known and the
 * data-sync id. One object crosses the host boundary, so a verification log line never carries loose secret strings.
 */
class YouTubeSessionCandidate(
    val cookie: String,
    val visitorData: String?,
    val dataSyncId: String,
) {
    override fun toString(): String = "YouTubeSessionCandidate(redacted)"
}

fun YouTubeSavedAccount.toSessionCandidate(): YouTubeSessionCandidate = YouTubeSessionCandidate(
    cookie = innerTubeCookie,
    visitorData = visitorData.takeIf { it.isNotBlank() },
    dataSyncId = dataSyncId,
)

fun YouTubeManualAuth.toSessionCandidate(): YouTubeSessionCandidate = YouTubeSessionCandidate(
    cookie = cookie,
    visitorData = visitorData,
    dataSyncId = dataSyncId,
)

/**
 * The candidate for moving the signed-in session to another channel: the current cookie and visitor data with the new
 * data-sync id. Null when no signed-in session is active, so nothing is verified against an empty session.
 */
fun PreferenceSnapshot.youTubeChannelCandidate(dataSyncId: String): YouTubeSessionCandidate? {
    val cookie = this[InnerTubeCookieKey, ""]
    if (!hasYouTubeSignInCookie(cookie) || dataSyncId.isBlank()) return null
    return YouTubeSessionCandidate(cookie, this[VisitorDataKey]?.takeIf { it.isNotBlank() }, dataSyncId)
}

/** True when the cookie carries a signed-in session cookie. A blank value does not count, as in the baseline check. */
fun hasYouTubeSignInCookie(cookie: String?): Boolean = youTubeSignInCookieValue(cookie) != null

/** A signed-in session, judged by its login cookie alone. The page shows the account only while this holds. */
fun PreferenceSnapshot.youTubeSignedIn(): Boolean = hasYouTubeSignInCookie(this[InnerTubeCookieKey, ""])

/** The identity facts the page shows. Blank optional facts read as null, so the page can fall back deliberately. */
fun PreferenceSnapshot.youTubeIdentity(): YouTubeAccountIdentity = YouTubeAccountIdentity(
    name = this[AccountNameKey, ""].trim(),
    email = this[AccountEmailKey, ""].trim().takeIf { it.isNotEmpty() },
    channelHandle = this[AccountChannelHandleKey, ""].trim().takeIf { it.isNotEmpty() },
    imageUrl = this[AccountImageUrlKey, ""].trim().takeIf { it.isNotEmpty() },
)

fun PreferenceSnapshot.savedYouTubeAccounts(): List<YouTubeSavedAccount> =
    decodeSavedYouTubeAccounts(this[SavedAccountsKey, ""])

/**
 * The saved entry whose cookie matches the active session, or null when the session is not saved. The cookie itself
 * stays inside this file; callers receive only the id.
 */
fun PreferenceSnapshot.activeYouTubeSavedAccountId(): String? {
    val cookie = this[InnerTubeCookieKey, ""]
    if (!hasYouTubeSignInCookie(cookie)) return null
    return savedYouTubeAccounts().firstOrNull { it.innerTubeCookie == cookie }?.id
}

/** An OAuth code session is present while a refresh token is stored, the same test the baseline sign-out uses. */
fun PreferenceSnapshot.youTubeOAuthSignedIn(): Boolean = this[InnerTubeOAuthRefreshTokenKey, ""].isNotBlank()

/**
 * Decodes the stored list. Blank, malformed or incomplete input yields an empty list, as the baseline decoder does.
 * Unknown extra fields are ignored, so a newer export still reads here.
 */
fun decodeSavedYouTubeAccounts(raw: String): List<YouTubeSavedAccount> {
    if (raw.isBlank()) return emptyList()
    val array = runCatching { Json.parseToJsonElement(raw) }.getOrNull() as? JsonArray ?: return emptyList()
    val accounts = ArrayList<YouTubeSavedAccount>(array.size)
    for (element in array) {
        val account = (element as? JsonObject)?.toYouTubeSavedAccount() ?: return emptyList()
        accounts.add(account)
    }
    return accounts
}

/** Encodes the list in the baseline field order, so an older build still reads what this build writes. */
fun encodeSavedYouTubeAccounts(accounts: List<YouTubeSavedAccount>): String =
    JsonArray(accounts.map { it.toYouTubeJsonObject() }).toString()

/** A random id for a new saved entry, 32 lowercase hex digits. The id is compared for equality and never parsed. */
fun newYouTubeSavedAccountId(random: Random = Random.Default): String =
    random.nextBytes(16).joinToString(separator = "") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

/**
 * Parses a pasted login block: `***KEY*** = value` lines in the baseline export layout. Returns null unless a signed-in
 * cookie and a data-sync id are both present. The visitor data and the legacy PO token stay optional. The result is
 * never shown; the page only uses it to enable confirm and to hand the facts to verification.
 */
fun parseYouTubeManualAuth(text: String): YouTubeManualAuth? {
    val fields = text.lineSequence()
        .filter { '=' in it }
        .associate { line -> line.substringBefore('=').trim() to line.substringAfter('=').trim() }
    val cookie = fields[ManualCookieKey].orEmpty()
    val dataSyncId = fields[ManualDataSyncKey].orEmpty()
    if (!hasYouTubeSignInCookie(cookie) || dataSyncId.isBlank()) return null
    return YouTubeManualAuth(
        cookie = cookie,
        visitorData = fields[ManualVisitorKey]?.takeIf { it.isNotEmpty() },
        dataSyncId = dataSyncId,
        poToken = fields[ManualPoTokenKey],
    )
}

/**
 * Appends the signed-in account to the saved list, unless its cookie is already saved or no session is active. The
 * identity, visitor data, data-sync id and playlist-sync choices come from the same edit, so the saved entry describes
 * the session the user is looking at.
 */
fun PreferenceEditor.saveCurrentYouTubeAccount(id: String) {
    val cookie = this[InnerTubeCookieKey, ""]
    if (!hasYouTubeSignInCookie(cookie)) return
    val existing = decodeSavedYouTubeAccounts(this[SavedAccountsKey, ""])
    if (existing.any { it.innerTubeCookie == cookie }) return
    val account = YouTubeSavedAccount(
        id = id,
        name = this[AccountNameKey, ""],
        email = this[AccountEmailKey, ""],
        channelHandle = this[AccountChannelHandleKey, ""],
        innerTubeCookie = cookie,
        visitorData = this[VisitorDataKey, ""],
        dataSyncId = this[DataSyncIdKey, ""],
        ytmSync = this[YtmSyncKey, true],
        selectedYtmPlaylists = this[SelectedYtmPlaylistsKey, ""],
    )
    this[SavedAccountsKey] = encodeSavedYouTubeAccounts(existing + account)
}

/** Removes one saved entry by id. The remaining entries keep their stored order. */
fun PreferenceEditor.removeSavedYouTubeAccount(id: String) {
    val remaining = decodeSavedYouTubeAccounts(this[SavedAccountsKey, ""]).filter { it.id != id }
    this[SavedAccountsKey] = encodeSavedYouTubeAccounts(remaining)
}

/**
 * Switches to a verified saved account in one edit. The previous session is cleared first, including its OAuth and PO
 * tokens and its identity. The saved cookie, visitor data and data-sync id are then written with the verified identity,
 * and the saved playlist-sync choices are restored, as the baseline switch does. Nothing is written for a saved entry
 * that lacks a signed-in cookie or a data-sync id.
 */
fun PreferenceEditor.commitYouTubeAccountSwitch(account: YouTubeSavedAccount, identity: YouTubeAccountIdentity) {
    if (!hasYouTubeSignInCookie(account.innerTubeCookie) || account.dataSyncId.isBlank()) return
    clearYouTubeSession()
    this[InnerTubeCookieKey] = account.innerTubeCookie
    this[VisitorDataKey] = account.visitorData.takeIf { it.isNotBlank() }
    this[DataSyncIdKey] = account.dataSyncId
    commitYouTubeIdentity(identity)
    this[YtmSyncKey] = account.ytmSync
    this[SelectedYtmPlaylistsKey] = account.selectedYtmPlaylists
}

/**
 * Moves the signed-in account to another channel by changing its data-sync id. The cookie and visitor data are kept.
 * The baseline channel switch rebuilds the session through the same clear as a sign-in, so OAuth and PO tokens are
 * cleared too. That behaviour is preserved here and recorded as a risk in the lane report.
 */
fun PreferenceEditor.commitYouTubeChannelSwitch(dataSyncId: String, identity: YouTubeAccountIdentity) {
    val cookie = this[InnerTubeCookieKey, ""]
    if (!hasYouTubeSignInCookie(cookie) || dataSyncId.isBlank()) return
    val visitorData = this[VisitorDataKey]
    clearYouTubeSession()
    this[InnerTubeCookieKey] = cookie
    this[VisitorDataKey] = visitorData
    this[DataSyncIdKey] = dataSyncId
    commitYouTubeIdentity(identity)
}

/**
 * Replaces the session with a verified pasted login block in one edit. The saved-account list and playlist-sync choices
 * are left alone, as the baseline import leaves them. The legacy PO token is written only when the block carries one.
 */
fun PreferenceEditor.commitYouTubeManualImport(auth: YouTubeManualAuth, identity: YouTubeAccountIdentity) {
    if (!hasYouTubeSignInCookie(auth.cookie) || auth.dataSyncId.isBlank()) return
    clearYouTubeSession()
    this[InnerTubeCookieKey] = auth.cookie
    this[VisitorDataKey] = auth.visitorData?.takeIf { it.isNotBlank() }
    this[DataSyncIdKey] = auth.dataSyncId
    commitYouTubeIdentity(identity)
    auth.poToken?.let { putLegacyYouTubePoToken(it) }
}

/**
 * The local sign-out. It clears the session, the OAuth code session, the PO tokens and the identity, and keeps the
 * saved-account list and the web-client PO token switch, as the baseline logout does. The host revokes any server-side
 * token before or alongside this and never lets a failed revoke keep the local session.
 */
fun PreferenceEditor.clearYouTubeSession() {
    this[InnerTubeCookieKey] = null
    this[VisitorDataKey] = null
    this[DataSyncIdKey] = null
    this[PoTokenKey] = null
    this[PoTokenGvsKey] = null
    this[PoTokenPlayerKey] = null
    this[PoTokenSourceUrlKey] = null
    clearYouTubeOAuthSession()
    this[AccountNameKey] = null
    this[AccountImageUrlKey] = null
    this[AccountEmailKey] = null
    this[AccountChannelHandleKey] = null
}

/** Removes the OAuth code session from this device. Revoking the refresh token with Google is host work. */
fun PreferenceEditor.clearYouTubeOAuthSession() {
    this[InnerTubeOAuthTokenKey] = null
    this[InnerTubeOAuthRefreshTokenKey] = null
    this[InnerTubeOAuthExpiresAtKey] = null
}

private fun PreferenceEditor.commitYouTubeIdentity(identity: YouTubeAccountIdentity) {
    this[AccountNameKey] = identity.name
    this[AccountEmailKey] = identity.email?.trim().orEmpty()
    this[AccountChannelHandleKey] = identity.channelHandle?.trim().orEmpty()
    this[AccountImageUrlKey] = identity.imageUrl?.trim()?.takeIf { it.isNotEmpty() }
}

/**
 * The legacy PO token write. A blank value or the literal "null" clears the token, which matches the baseline
 * normalization. The GVS and player tokens are always cleared, because a legacy token replaces them.
 */
private fun PreferenceEditor.putLegacyYouTubePoToken(value: String) {
    val normalized = value.trim().takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
    this[PoTokenKey] = normalized
    this[PoTokenGvsKey] = null
    this[PoTokenPlayerKey] = null
}

private fun youTubeSignInCookieValue(cookie: String?): String? {
    val cookies = parseYouTubeCookieFields(cookie.orEmpty())
    return YouTubeSignInCookieNames.firstNotNullOfOrNull { name -> cookies[name]?.takeIf(String::isNotBlank) }
}

/** The baseline cookie parse: `;`-separated pairs with trimmed keys and values, and the last duplicate wins. */
private fun parseYouTubeCookieFields(cookie: String): Map<String, String> =
    cookie.split(";")
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .mapNotNull { part ->
            val splitIndex = part.indexOf('=')
            if (splitIndex == -1) {
                null
            } else {
                val key = part.substring(0, splitIndex).trim()
                if (key.isEmpty()) null else key to part.substring(splitIndex + 1).trim()
            }
        }.toMap()

private fun YouTubeSavedAccount.toYouTubeJsonObject(): JsonObject {
    val fields = linkedMapOf<String, JsonElement>(
        "id" to JsonPrimitive(id),
        "name" to JsonPrimitive(name),
        "email" to JsonPrimitive(email),
        "channelHandle" to JsonPrimitive(channelHandle),
        "innerTubeCookie" to JsonPrimitive(innerTubeCookie),
        "visitorData" to JsonPrimitive(visitorData),
        "dataSyncId" to JsonPrimitive(dataSyncId),
        "ytmSync" to JsonPrimitive(ytmSync),
        "selectedYtmPlaylists" to JsonPrimitive(selectedYtmPlaylists),
    )
    return JsonObject(fields)
}

private fun JsonObject.toYouTubeSavedAccount(): YouTubeSavedAccount? {
    val id = requiredYouTubeJsonString("id") ?: return null
    val name = requiredYouTubeJsonString("name") ?: return null
    val email = requiredYouTubeJsonString("email") ?: return null
    val channelHandle = requiredYouTubeJsonString("channelHandle") ?: return null
    val innerTubeCookie = requiredYouTubeJsonString("innerTubeCookie") ?: return null
    val visitorData = requiredYouTubeJsonString("visitorData") ?: return null
    val dataSyncId = requiredYouTubeJsonString("dataSyncId") ?: return null
    val ytmSync = requiredYouTubeJsonBoolean("ytmSync") ?: return null
    val selectedYtmPlaylists = requiredYouTubeJsonString("selectedYtmPlaylists") ?: return null
    return YouTubeSavedAccount(
        id = id,
        name = name,
        email = email,
        channelHandle = channelHandle,
        innerTubeCookie = innerTubeCookie,
        visitorData = visitorData,
        dataSyncId = dataSyncId,
        ytmSync = ytmSync,
        selectedYtmPlaylists = selectedYtmPlaylists,
    )
}

/** A JSON string primitive only. A number, a boolean or a null does not satisfy a string field. */
private fun JsonObject.requiredYouTubeJsonString(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** A JSON boolean literal only. A quoted "true" does not satisfy a boolean field, matching the strict decoder. */
private fun JsonObject.requiredYouTubeJsonBoolean(key: String): Boolean? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    return if (primitive.isString) null else primitive.content.toBooleanStrictOrNull()
}
