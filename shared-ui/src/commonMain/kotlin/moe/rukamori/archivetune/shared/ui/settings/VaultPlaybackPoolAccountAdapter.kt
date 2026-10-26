package moe.rukamori.archivetune.shared.ui.settings

import moe.rukamori.archivetune.shared.domain.platform.PlatformSecret
import moe.rukamori.archivetune.shared.domain.settings.StringPreferenceKey

/*
 * Playback Pool lists keep the baseline shape: one vault value per provider holding a JSON array with one object per
 * pool entry, addressed by its positive feed id. The store keeps an entry's account identity, generation and cookie rows
 * inside that same object, so one vault edit replaces the entry's credentials and account state together. Unknown fields
 * and sibling entries survive every write, and a list that cannot be read is refused rather than replaced.
 */

/** Account metadata that the store owns inside a pool entry. A commit never names these as credentials. */
internal const val POOL_ACCOUNT_ID_FIELD = "accountId"
internal const val POOL_ACCOUNT_GENERATION_FIELD = "accountGeneration"
internal const val POOL_ACCOUNT_COOKIES_FIELD = "accountCookies"

/** The baseline feed id. A positive value makes an entry addressable; manual or legacy entries carry none. */
private const val POOL_FEED_ID_FIELD = "id"

private val POOL_METADATA_FIELDS = setOf(POOL_ACCOUNT_ID_FIELD, POOL_ACCOUNT_GENERATION_FIELD, POOL_ACCOUNT_COOKIES_FIELD)
private val JSON_KEYWORDS = listOf("true", "false", "null")
private const val POOL_SCOPE_PREFIX = "PLAYBACK_POOL:"

/** The opaque account scope that addresses one pool entry. Callers pass it through and never parse it. */
internal fun poolScopeForFeedId(feedId: Long): String = "$POOL_SCOPE_PREFIX$feedId"

/** The feed id that [accountScope] addresses, or null unless it is exactly the canonical scope of a positive feed id. */
internal fun poolFeedIdOfScope(accountScope: String): Long? {
    if (!accountScope.startsWith(POOL_SCOPE_PREFIX)) return null
    val feedId = accountScope.removePrefix(POOL_SCOPE_PREFIX).toLongOrNull()?.takeIf { it > 0L } ?: return null
    return feedId.takeIf { poolScopeForFeedId(it) == accountScope }
}

internal enum class PoolFieldKind {
    /** A credential. Returned only through an admitted read, and cleared when a commit omits it. */
    SECRET,

    /** Non-secret text, written when supplied and cleared when a commit omits it. */
    PLAIN,

    /** A JSON boolean, such as `premium`. */
    FLAG,

    /** A JSON integer, such as a Turnstile expiry. */
    NUMBER,
}

/**
 * One playback pool provider. [fieldKinds] lists every JSON field a commit may write. The values of one commit are the
 * entry's complete account bundle, so every listed field that the commit omits is cleared and cannot describe a different
 * account. [legacyFields] are older names of managed fields: they are cleared on every commit and never written. [usable]
 * mirrors the baseline parser, so an entry that the baseline would skip is refused rather than stored as a dead account.
 */
internal class PoolService(
    val provider: String,
    val listKey: StringPreferenceKey,
    val fieldKinds: Map<String, PoolFieldKind>,
    val usable: (PoolJsonObject) -> Boolean,
    val legacyFields: Set<String> = emptySet(),
) {
    fun slotKey(feedId: Long): String = "pool/$provider/$feedId"
}

/*
 * The provider keys match the vault routes in settingsCredentialRoutes() and the canonical playback providers. Apple's
 * pool list keeps its baseline key, poolAppleMusicAccounts, but its service is the canonical "apple" provider that the
 * personal account slot also uses, so no alias or second Apple identity exists.
 */
private val POOL_SERVICES: List<PoolService> = listOf(
    PoolService(
        provider = "tidal",
        listKey = StringPreferenceKey("poolTidalAccounts"),
        fieldKinds = mapOf(
            "token" to PoolFieldKind.SECRET,
            "refreshToken" to PoolFieldKind.SECRET,
            "countryCode" to PoolFieldKind.PLAIN,
            "premium" to PoolFieldKind.FLAG,
        ),
        usable = { it.hasText("token") },
    ),
    PoolService(
        provider = "qobuz",
        listKey = StringPreferenceKey("poolQobuzAccounts"),
        fieldKinds = mapOf(
            "token" to PoolFieldKind.SECRET,
            "appId" to PoolFieldKind.SECRET,
            "appSecret" to PoolFieldKind.SECRET,
            "premium" to PoolFieldKind.FLAG,
        ),
        usable = { it.hasText("token") && it.hasText("appId") && it.hasText("appSecret") },
    ),
    PoolService(
        provider = "deezer",
        listKey = StringPreferenceKey("poolDeezerAccounts"),
        fieldKinds = mapOf(
            "arl" to PoolFieldKind.SECRET,
            "masterSecret" to PoolFieldKind.SECRET,
            "premium" to PoolFieldKind.FLAG,
        ),
        usable = { it.hasText("arl") },
    ),
    PoolService(
        provider = "apple",
        listKey = StringPreferenceKey("poolAppleMusicAccounts"),
        fieldKinds = mapOf(
            "token" to PoolFieldKind.SECRET,
            "premium" to PoolFieldKind.FLAG,
        ),
        // Media-user tokens always start with "0.", as the baseline parser requires.
        usable = { it.text("token")?.startsWith("0.") == true },
    ),
    PoolService(
        provider = "amazon",
        listKey = StringPreferenceKey("poolAmazonAccounts"),
        fieldKinds = mapOf(
            "session" to PoolFieldKind.SECRET,
            "bypassToken" to PoolFieldKind.SECRET,
            "turnstileJwt" to PoolFieldKind.SECRET,
            "turnstileJwtExpiresAtMs" to PoolFieldKind.NUMBER,
            "baseUrl" to PoolFieldKind.PLAIN,
            "premium" to PoolFieldKind.FLAG,
        ),
        // Either tier is usable on its own: a session or a base URL.
        usable = { it.hasText("baseUrl") || it.hasText("session") },
        // The baseline also reads an ISO expiry. The store never writes it, but a commit must not leave a stale one.
        legacyFields = setOf("turnstileJwtExpiresAt"),
    ),
)

/** The pool service for [provider], or null when that provider has no baseline playback pool list. */
internal fun poolServiceFor(provider: String): PoolService? = POOL_SERVICES.firstOrNull { it.provider == provider }

/** One parsed JSON value. Scalars keep their literal text, so values the store does not read round-trip unchanged. */
internal sealed interface PoolJson

/** A JSON object. Field order is kept, so an entry round-trips without reordering. */
internal class PoolJsonObject(val fields: LinkedHashMap<String, PoolJson>) : PoolJson {
    fun text(name: String): String? = fields[name]?.let { poolText(it) }

    fun hasText(name: String): Boolean = !text(name).isNullOrBlank()

    /** The positive feed id, or null for a manual or legacy entry that has none. */
    fun feedId(): Long? = text(POOL_FEED_ID_FIELD)?.toLongOrNull()?.takeIf { it > 0L }

    fun accountId(): String? = text(POOL_ACCOUNT_ID_FIELD)?.takeIf { it.isNotBlank() }

    /** The stored generation, or null when it is missing or malformed. A null read fails closed. */
    fun generation(): Long? = text(POOL_ACCOUNT_GENERATION_FIELD)?.toLongOrNull()?.takeIf { it >= 0L }
}

internal class PoolJsonArray(val items: MutableList<PoolJson>) : PoolJson

internal class PoolJsonString(val value: String) : PoolJson

/** A number, boolean or null, kept as its exact JSON text. */
internal class PoolJsonLiteral(val text: String) : PoolJson

/** The text of a string, number or boolean. Objects, arrays and null have no text. */
internal fun poolText(value: PoolJson): String? = when (value) {
    is PoolJsonString -> value.value
    is PoolJsonLiteral -> value.text.takeIf { it != "null" }
    else -> null
}

/** The feed id of a pool entry, or null for any value that is not an addressable entry. */
internal fun poolFeedId(item: PoolJson): Long? = (item as? PoolJsonObject)?.feedId()

/** A new entry that holds only its feed id. The commit that creates it writes the rest of its fields. */
internal fun newPoolEntry(feedId: Long): PoolJsonObject {
    val fields = LinkedHashMap<String, PoolJson>()
    fields[POOL_FEED_ID_FIELD] = PoolJsonLiteral(feedId.toString())
    return PoolJsonObject(fields)
}

/**
 * One parsed pool list. Entries are addressed by feed id. Lookups are exact: when two entries share an id, the list is
 * ambiguous, so callers accept exactly one match and refuse any other count rather than guessing.
 */
internal class PoolEntryList private constructor(private val items: MutableList<PoolJson>) {
    /** Positions of every entry whose feed id is [feedId]. Callers accept exactly one match and refuse any other count. */
    fun matchingIndices(feedId: Long): List<Int> = items.indices.filter { poolFeedId(items[it]) == feedId }

    fun objectAt(index: Int): PoolJsonObject = items[index] as PoolJsonObject

    /** Every addressable entry, in stored order. Manual and legacy entries are left out of this view. */
    fun addressableEntries(): List<PoolJsonObject> =
        items.mapNotNull { item -> (item as? PoolJsonObject)?.takeIf { it.feedId() != null } }

    fun replaceAt(index: Int, value: PoolJsonObject) {
        items[index] = value
    }

    fun append(value: PoolJsonObject) {
        items += value
    }

    fun removeAt(index: Int) {
        items.removeAt(index)
    }

    /** The whole list as JSON. Untouched entries and unknown fields are written back exactly as they were read. */
    fun encode(): String = StringBuilder().also { encodePoolJson(PoolJsonArray(items), it) }.toString()

    companion object {
        /** An absent or blank value is an empty list. Any other value must be a JSON array, or it is refused. */
        fun parse(stored: String?): PoolEntryList {
            if (stored.isNullOrBlank()) return PoolEntryList(mutableListOf())
            val root = PoolJsonParser(stored).parseDocument()
            require(root is PoolJsonArray) { "Playback pool list is not a JSON array" }
            return PoolEntryList(root.items)
        }
    }
}

/**
 * The credential-bearing copy of one entry for an admitted read. Identity, generation, cookie rows and the feed id are
 * excluded. Every scalar is returned as text, so a consumer parses only the value it needs.
 */
internal fun PoolJsonObject.credentialFields(): Map<String, PlatformSecret> {
    val out = LinkedHashMap<String, PlatformSecret>()
    fields.forEach { (name, value) ->
        if (name != POOL_FEED_ID_FIELD && name !in POOL_METADATA_FIELDS) {
            poolText(value)?.let { out[name] = PlatformSecret(it) }
        }
    }
    return out
}

/**
 * The entry after one commit. [values] are the account's complete credential bundle, so every managed field that the
 * commit omits is removed and nothing from the previous account survives. Metadata is always rewritten: identity,
 * generation and the cookie rows of the verified attempt. Unknown fields of the entry are kept as they were.
 */
internal fun PoolService.applyCommit(
    current: PoolJsonObject,
    accountId: String,
    generation: Long,
    values: Map<String, String>,
    cookieRows: String?,
): PoolJsonObject {
    require(values.keys.all { it in fieldKinds }) { "Credential is outside the pool entry fields" }
    val next = LinkedHashMap(current.fields)
    (fieldKinds.keys + legacyFields).forEach { next.remove(it) }
    fieldKinds.forEach { (name, kind) ->
        values[name]?.let { next[name] = poolJsonFor(kind, name, it) }
    }
    next[POOL_ACCOUNT_ID_FIELD] = PoolJsonString(accountId)
    next[POOL_ACCOUNT_GENERATION_FIELD] = PoolJsonString(generation.toString())
    if (cookieRows.isNullOrEmpty()) {
        next.remove(POOL_ACCOUNT_COOKIES_FIELD)
    } else {
        next[POOL_ACCOUNT_COOKIES_FIELD] = PoolJsonString(cookieRows)
    }
    return PoolJsonObject(next)
}

private fun poolJsonFor(kind: PoolFieldKind, name: String, value: String): PoolJson = when (kind) {
    PoolFieldKind.SECRET, PoolFieldKind.PLAIN -> PoolJsonString(value)
    PoolFieldKind.FLAG -> PoolJsonLiteral(
        requireNotNull(value.takeIf { it == "true" || it == "false" }) { "Playback pool flag $name is malformed" },
    )
    PoolFieldKind.NUMBER -> PoolJsonLiteral(
        requireNotNull(value.toLongOrNull()?.takeIf { it >= 0L }?.toString()) { "Playback pool number $name is malformed" },
    )
}

private fun encodePoolJson(value: PoolJson, out: StringBuilder) {
    when (value) {
        is PoolJsonObject -> {
            out.append('{')
            value.fields.entries.forEachIndexed { index, (key, child) ->
                if (index > 0) out.append(',')
                appendJsonString(key, out)
                out.append(':')
                encodePoolJson(child, out)
            }
            out.append('}')
        }
        is PoolJsonArray -> {
            out.append('[')
            value.items.forEachIndexed { index, child ->
                if (index > 0) out.append(',')
                encodePoolJson(child, out)
            }
            out.append(']')
        }
        is PoolJsonString -> appendJsonString(value.value, out)
        is PoolJsonLiteral -> out.append(value.text)
    }
}

private fun appendJsonString(value: String, out: StringBuilder) {
    out.append('"')
    for (c in value) {
        when (c) {
            '"' -> out.append("\\\"")
            '\\' -> out.append("\\\\")
            '\n' -> out.append("\\n")
            '\r' -> out.append("\\r")
            '\t' -> out.append("\\t")
            '\b' -> out.append("\\b")
            '\u000C' -> out.append("\\f")
            else -> if (c < ' ') {
                out.append("\\u").append(c.code.toString(16).padStart(4, '0'))
            } else {
                out.append(c)
            }
        }
    }
    out.append('"')
}

/** A strict JSON reader for the pool list. Any input it does not fully understand is refused, never guessed. */
private class PoolJsonParser(private val source: String) {
    private var index = 0

    fun parseDocument(): PoolJson {
        val value = parseValue()
        skipWhitespace()
        require(index == source.length) { "Playback pool list has trailing content" }
        return value
    }

    private fun parseValue(): PoolJson {
        skipWhitespace()
        return when (peek()) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> PoolJsonString(parseString())
            't', 'f', 'n' -> parseKeyword()
            else -> parseNumber()
        }
    }

    private fun parseObject(): PoolJsonObject {
        consume()
        val fields = LinkedHashMap<String, PoolJson>()
        skipWhitespace()
        if (peek() == '}') {
            consume()
            return PoolJsonObject(fields)
        }
        while (true) {
            skipWhitespace()
            val key = parseString()
            // A repeated key would make the stored bytes differ from the parsed entry, so it is refused rather than merged.
            require(!fields.containsKey(key)) { "Playback pool list object repeats a key" }
            skipWhitespace()
            require(consume() == ':') { "Playback pool list object is malformed" }
            fields[key] = parseValue()
            skipWhitespace()
            val next = consume()
            if (next == '}') return PoolJsonObject(fields)
            require(next == ',') { "Playback pool list object is malformed" }
        }
    }

    private fun parseArray(): PoolJsonArray {
        consume()
        val items = mutableListOf<PoolJson>()
        skipWhitespace()
        if (peek() == ']') {
            consume()
            return PoolJsonArray(items)
        }
        while (true) {
            items += parseValue()
            skipWhitespace()
            val next = consume()
            if (next == ']') return PoolJsonArray(items)
            require(next == ',') { "Playback pool list array is malformed" }
        }
    }

    private fun parseString(): String {
        require(consume() == '"') { "Playback pool list string is malformed" }
        val out = StringBuilder()
        while (true) {
            val c = consume()
            if (c == '"') return out.toString()
            if (c == '\\') {
                out.append(parseEscape())
            } else {
                require(c >= ' ') { "Playback pool list string has a control character" }
                out.append(c)
            }
        }
    }

    private fun parseEscape(): Char = when (val escaped = consume()) {
        '"', '\\', '/' -> escaped
        'b' -> '\b'
        'f' -> '\u000C'
        'n' -> '\n'
        'r' -> '\r'
        't' -> '\t'
        'u' -> {
            var code = 0
            repeat(4) { code = code * 16 + hexValue(consume()) }
            code.toChar()
        }
        else -> throw IllegalArgumentException("Playback pool list escape is malformed")
    }

    private fun parseKeyword(): PoolJson {
        for (word in JSON_KEYWORDS) {
            if (source.startsWith(word, index)) {
                index += word.length
                return PoolJsonLiteral(word)
            }
        }
        throw IllegalArgumentException("Playback pool list literal is malformed")
    }

    private fun parseNumber(): PoolJson {
        val start = index
        if (peek() == '-') consume()
        requireDigit()
        while (hasMore() && source[index] in '0'..'9') index++
        if (hasMore() && source[index] == '.') {
            index++
            requireDigit()
            while (hasMore() && source[index] in '0'..'9') index++
        }
        if (hasMore() && (source[index] == 'e' || source[index] == 'E')) {
            index++
            if (hasMore() && (source[index] == '+' || source[index] == '-')) index++
            requireDigit()
            while (hasMore() && source[index] in '0'..'9') index++
        }
        return PoolJsonLiteral(source.substring(start, index))
    }

    private fun requireDigit() {
        require(hasMore() && source[index] in '0'..'9') { "Playback pool list number is malformed" }
    }

    private fun hasMore(): Boolean = index < source.length

    private fun peek(): Char {
        require(hasMore()) { "Playback pool list ended early" }
        return source[index]
    }

    private fun consume(): Char = peek().also { index++ }

    private fun skipWhitespace() {
        while (hasMore() && source[index] in " \t\r\n") index++
    }

    private fun hexValue(digit: Char): Int = when (digit) {
        in '0'..'9' -> digit - '0'
        in 'a'..'f' -> digit - 'a' + 10
        in 'A'..'F' -> digit - 'A' + 10
        else -> throw IllegalArgumentException("Playback pool list escape is malformed")
    }
}
