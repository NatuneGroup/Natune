package moe.rukamori.archivetune.shared.domain.settings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * One direct Qobuz API credential set: the user auth token plus the app id and secret that sign each request. Stored as
 * a JSON array under [QobuzTokensKey] in the baseline layout, so saved accounts read back unchanged. The token and the
 * secrets reach storage only through the logical vault adapter.
 */
data class QobuzTokenEntry(
    val token: String,
    val userId: String = "",
    val appId: String,
    val appSecret: String,
    val label: String = "",
    val subscription: String = "",
    val poolId: Long? = null,
) {
    /** Stable key for health results and row anchors. The baseline keys by the first twelve token characters. */
    val id: String get() = token.take(12)

    /** An entry is usable only when all three credentials are present, the same rule the baseline reader applies. */
    fun isComplete(): Boolean = token.isNotBlank() && appId.isNotBlank() && appSecret.isNotBlank()
}

/** Stored instance URLs in saved order, one per line. Blank storage means no instances; there are no hidden defaults. */
fun PreferenceSnapshot.qobuzInstanceList(): List<String> = parseQobuzInstanceLines(this[QobuzInstancesKey, ""])

/** Stored accounts in saved order. Entries missing a token, app id or app secret are dropped on read. */
fun PreferenceSnapshot.qobuzTokens(): List<QobuzTokenEntry> = decodeQobuzTokens(this[QobuzTokensKey, ""])

/** Saves the instance list without duplicates. An empty list is stored as blank, which reads as no instances. */
fun PreferenceEditor.setQobuzInstances(instances: List<String>) {
    this[QobuzInstancesKey] = instances.distinct().joinToString("\n")
}

/** Saves the account list without duplicate tokens. An empty list is stored as an empty JSON array, as the baseline does. */
fun PreferenceEditor.setQobuzTokens(tokens: List<QobuzTokenEntry>) {
    this[QobuzTokensKey] = encodeQobuzTokens(tokens.distinctBy { it.token })
}

/** Forgets every stored account in one edit. The baseline reset stores an empty string rather than an empty array. */
fun PreferenceEditor.clearQobuzTokens() {
    this[QobuzTokensKey] = ""
}

/**
 * Appends parsed or signed-in accounts to the stored list in one edit. The list is read here, inside the edit, so a
 * concurrent write is not overwritten. A token that is already stored keeps its first copy.
 */
fun PreferenceEditor.addQobuzTokens(added: List<QobuzTokenEntry>) {
    setQobuzTokens(decodeQobuzTokens(this[QobuzTokensKey, ""]) + added)
}

/**
 * Replaces the stored account with [id] in one edit, or removes it when [replacement] is null. The stored list is read
 * inside the edit for the same reason as [addQobuzTokens].
 */
fun PreferenceEditor.replaceQobuzToken(id: String, replacement: QobuzTokenEntry?) {
    val next = decodeQobuzTokens(this[QobuzTokensKey, ""]).flatMap { entry ->
        if (entry.id == id) listOfNotNull(replacement) else listOf(entry)
    }
    setQobuzTokens(next)
}

/**
 * Parses pasted token blocks the way the baseline SourceInputParsing does. A line carrying a token starts a block. User
 * id and subscription lines, and app_id or app_secret fragments, fill the current block. A plain line with no field
 * separator labels the block that follows it. Blocks missing a token, app id or app secret are dropped.
 */
fun parseQobuzTokenBlocks(input: String): List<QobuzTokenEntry> {
    if (input.isBlank()) return emptyList()
    val drafts = ArrayList<QobuzTokenDraft>()
    var current: QobuzTokenDraft? = null
    var pendingLabel = ""
    for (rawLine in input.lineSequence()) {
        val line = rawLine.trim()
        if (line.isEmpty()) continue
        val token = matchQobuzField(line, "token")
        val userId = matchQobuzField(line, "user id", "user_id", "userid", "user")
        val subscription = matchQobuzField(line, "subscription", "plan")
        val appId = QobuzAppIdPattern.find(line)?.groupValues?.get(1)
        val appSecret = QobuzAppSecretPattern.find(line)?.groupValues?.get(1)
        when {
            token != null -> {
                val draft = QobuzTokenDraft(label = pendingLabel)
                pendingLabel = ""
                drafts += draft
                current = draft
                draft.token = token
            }
            userId != null -> current?.userId = userId
            subscription != null -> current?.subscription = subscription
            !line.contains(QobuzFieldSeparator) && !line.contains("app_") -> pendingLabel = line.take(40)
        }
        if (appId != null) current?.appId = appId
        if (appSecret != null) current?.appSecret = appSecret
    }
    return drafts.mapNotNull { it.toEntry() }
}

private val QobuzAppIdPattern = Regex("app_id:?\\s*([0-9]+)", RegexOption.IGNORE_CASE)
private val QobuzAppSecretPattern = Regex("app_secret:?\\s*([a-f0-9]{16,})", RegexOption.IGNORE_CASE)
private val QobuzFieldSeparator = Regex("[➠→➔⇒:]")

/** Returns the value after a field label, or null when the label is absent or has no value. */
private fun matchQobuzField(line: String, vararg names: String): String? {
    val lower = line.lowercase()
    for (name in names) {
        if (!lower.startsWith(name)) continue
        val after = line.substring(name.length)
        val value = after.dropWhile { it == ' ' || it in "➠→➔⇒:=\t" }.trim()
        if (value.isNotEmpty()) return value.trim('"', '\'')
    }
    return null
}

private class QobuzTokenDraft(val label: String) {
    var token: String = ""
    var userId: String = ""
    var appId: String = ""
    var appSecret: String = ""
    var subscription: String = ""

    fun toEntry(): QobuzTokenEntry? {
        val entry = QobuzTokenEntry(
            token = token.trim(),
            userId = userId.trim(),
            appId = appId.trim(),
            appSecret = appSecret.trim(),
            label = label.trim(),
            subscription = subscription.trim(),
        )
        return entry.takeIf { it.isComplete() }
    }
}

private fun parseQobuzInstanceLines(raw: String): List<String> =
    raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }

/** Decodes the baseline JSON layout. Unreadable text reads as no accounts, as the baseline reader does. */
internal fun decodeQobuzTokens(raw: String): List<QobuzTokenEntry> {
    if (raw.isBlank()) return emptyList()
    val array = runCatching { Json.parseToJsonElement(raw) }.getOrNull() as? JsonArray ?: return emptyList()
    return array.mapNotNull { element -> (element as? JsonObject)?.toQobuzTokenEntry() }
}

internal fun encodeQobuzTokens(tokens: List<QobuzTokenEntry>): String =
    JsonArray(tokens.map { it.toJsonObject() }).toString()

private fun JsonObject.toQobuzTokenEntry(): QobuzTokenEntry? {
    val token = text("token")
    val appId = text("appId")
    val appSecret = text("appSecret")
    if (token.isEmpty() || appId.isEmpty() || appSecret.isEmpty()) return null
    return QobuzTokenEntry(
        token = token,
        userId = text("userId"),
        appId = appId,
        appSecret = appSecret,
        label = text("label"),
        subscription = text("subscription"),
        poolId = (this["poolId"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0L },
    )
}

private fun QobuzTokenEntry.toJsonObject(): JsonObject {
    val fields = linkedMapOf<String, JsonElement>(
        "token" to JsonPrimitive(token),
        "userId" to JsonPrimitive(userId),
        "appId" to JsonPrimitive(appId),
        "appSecret" to JsonPrimitive(appSecret),
        "label" to JsonPrimitive(label),
        "subscription" to JsonPrimitive(subscription),
    )
    poolId?.let { fields["poolId"] = JsonPrimitive(it) }
    return JsonObject(fields)
}

private fun JsonObject.text(key: String): String =
    ((this[key] as? JsonPrimitive)?.contentOrNull ?: "").trim()
