package moe.rukamori.archivetune.shared.data.potoken

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * The request/response contract of YouTube's BotGuard attestation endpoints, ported from the Android
 * `utils/potoken/ChallengeParser.kt` so the desktop and iOS hosts read `api/jnn/v1/Create` and
 * `api/jnn/v1/GenerateIT` responses byte-for-byte the same way. Values returned by [parseCreateChallenge]
 * are embedded into JavaScript, so the challenge object is serialized back out with every field the
 * WebView recipe forwards, nothing more.
 */
private val poTokenJson = Json { ignoreUnknownKeys = true }

/** One parsed `Create` challenge, exactly the fields `runBotGuard` reads in `po_token.html`. */
class BotGuardChallenge(
    val program: String,
    val globalName: String,
    val interpreterJavascript: String?,
    val interpreterUrl: String?,
)

/** One parsed `GenerateIT` answer: the raw integrity-token bytes and their lifetime in seconds. */
class BotGuardIntegrityToken(
    val tokenBytes: ByteArray,
    val lifetimeSeconds: Long,
) {
    override fun equals(other: Any?): Boolean =
        other is BotGuardIntegrityToken &&
            tokenBytes.contentEquals(other.tokenBytes) && lifetimeSeconds == other.lifetimeSeconds

    override fun hashCode(): Int = 31 * tokenBytes.contentHashCode() + lifetimeSeconds.hashCode()
}

/** A recoverable minting failure: timeout, HTTP error, malformed answer or a refused JS step. */
class PoTokenMintException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * Parses the raw `api/jnn/v1/Create` body. The outer array either carries the challenge object at
 * index 0 or a scrambled base64 payload at index 1; the scrambled form adds 97 to each decoded byte,
 * the same obfuscation the Android recipe reverses.
 */
fun parseCreateChallenge(rawResponse: String): BotGuardChallenge {
    val outer =
        try {
            poTokenJson.parseToJsonElement(rawResponse).jsonArray
        } catch (failure: Exception) {
            throw malformedCreate()
        }

    val challenge =
        try {
            if (outer.size > 1 && outer[1].jsonPrimitive.isString) {
                val decoded = descramble(outer[1].jsonPrimitive.content)
                poTokenJson.parseToJsonElement(decoded).jsonArray
            } else {
                outer[0].jsonArray
            }
        } catch (failure: PoTokenMintException) {
            throw failure
        } catch (failure: Exception) {
            throw malformedCreate()
        }

    return try {
        BotGuardChallenge(
            program = challenge[4].jsonPrimitive.content,
            globalName = challenge[5].jsonPrimitive.content,
            interpreterJavascript =
                challenge[1]
                    .takeIf { it !is JsonNull }
                    ?.jsonArray
                    ?.firstOrNull { it.jsonPrimitive.isString }
                    ?.jsonPrimitive
                    ?.content,
            interpreterUrl =
                challenge[2]
                    .takeIf { it !is JsonNull }
                    ?.jsonArray
                    ?.firstOrNull { it.jsonPrimitive.isString }
                    ?.jsonPrimitive
                    ?.content,
        )
    } catch (failure: Exception) {
        throw malformedCreate()
    }
}

/** Serializes a parsed challenge into the object literal the JS driver consumes. */
fun BotGuardChallenge.toDriverJson(): String =
    poTokenJson.encodeToString(
        JsonObject.serializer(),
        JsonObject(
            mapOf(
                "program" to JsonPrimitive(program),
                "globalName" to JsonPrimitive(globalName),
                "interpreterJavascript" to
                    JsonObject(
                        mapOf(
                            "privateDoNotAccessOrElseSafeScriptWrappedValue" to
                                (interpreterJavascript?.let(::JsonPrimitive) ?: JsonNull),
                            "privateDoNotAccessOrElseTrustedResourceUrlWrappedValue" to
                                (interpreterUrl?.let(::JsonPrimitive) ?: JsonNull),
                        ),
                    ),
            ),
        ),
    )

/** Parses the raw `api/jnn/v1/GenerateIT` body: `[base64Token, lifetimeSeconds]`. */
fun parseIntegrityToken(rawResponse: String): BotGuardIntegrityToken {
    val arr =
        try {
            poTokenJson.parseToJsonElement(rawResponse).jsonArray
        } catch (failure: Exception) {
            throw malformedIntegrityToken()
        }
    return try {
        BotGuardIntegrityToken(
            tokenBytes = base64ToByteArray(arr[0].jsonPrimitive.content),
            lifetimeSeconds = arr[1].jsonPrimitive.long,
        )
    } catch (failure: PoTokenMintException) {
        throw failure
    } catch (failure: Exception) {
        throw malformedIntegrityToken()
    }
}

/** Converts a plain-string content binding to a JavaScript `Uint8Array(...)` literal. */
fun identifierToJsUint8Array(identifier: String): String = bytesToJsUint8Array(identifier.encodeToByteArray())

/** The byte array as a JavaScript `Uint8Array(...)` literal, unsigned decimal elements. */
fun bytesToJsUint8Array(bytes: ByteArray): String =
    "new Uint8Array([${bytes.joinToString(",") { (it.toInt() and 0xFF).toString() }}])"

/**
 * Converts the CSV byte list `Uint8Array.join(",")` yields in JS to the base64url-with-padding
 * form YouTube expects for a poToken, matching `commaSeparatedBytesToBase64` on Android.
 */
@OptIn(ExperimentalEncodingApi::class)
fun commaSeparatedBytesToPoToken(commaBytes: String): String =
    commaBytes
        .split(",")
        .map { it.trim().toInt().toByte() }
        .toByteArray()
        .let { Base64.UrlSafe.encode(it) }

/**
 * Appends `pot=` to a googlevideo URL the way `YouTube.appendGvsPoToken` does: never twice, and the
 * token goes in verbatim because base64url padding is legal inside a query value.
 */
fun appendPoTokenToStreamUrl(url: String, poToken: String?): String {
    if (poToken.isNullOrBlank() || url.contains("pot=")) return url
    val separator = if (url.contains("?")) "&" else "?"
    return "$url${separator}pot=$poToken"
}

/** The client names the shipped resolver allows web poTokens on (`supportsWebPoToken` in core). */
fun supportsWebPoToken(clientName: String): Boolean =
    when (clientName.uppercase()) {
        "WEB", "WEB_REMIX", "WEB_CREATOR", "MWEB" -> true
        else -> false
    }

/**
 * The BotGuard content binding for the session token, mirroring `PlaybackAuthState.sessionId`
 * verbatim: the user session id half of a normalized `dataSyncId` when a login context exists —
 * with no visitor fallback, exactly like the shipped getter — the visitor data otherwise.
 */
fun poTokenSessionId(dataSyncId: String?, visitorData: String?, hasLoginContext: Boolean): String? =
    if (hasLoginContext) {
        dataSyncId.normalizeDataSyncId().userSessionIdOrNull()
    } else {
        visitorData?.takeIf(String::isNotBlank)
    }

/** Percent-decodes and normalizes `dataSyncId` exactly like core's `normalizeDataSyncId`. */
internal fun String?.normalizeDataSyncId(): String? {
    val normalized = this?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
        ?.decodePercentEscapes() ?: return null
    val separatorIndex = normalized.indexOf("||")
    if (separatorIndex < 0) return normalized
    val delegatedSessionId = normalized.substringBefore("||").trim()
    val userSessionId = normalized.substring(separatorIndex + 2).trim()
    return when {
        delegatedSessionId.isBlank() -> userSessionId.takeIf(String::isNotBlank)
        userSessionId.isBlank() -> delegatedSessionId
        else -> "$delegatedSessionId||$userSessionId"
    }
}

/** The `userSessionIdOrNull` tail of a normalized `dataSyncId`. */
internal fun String?.userSessionIdOrNull(): String? {
    val normalized = this?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) } ?: return null
    val separatorIndex = normalized.indexOf("||")
    return normalized
        .substring(if (separatorIndex >= 0) separatorIndex + 2 else 0)
        .trim()
        .takeIf(String::isNotBlank)
}

private fun String.decodePercentEscapes(): String {
    if (!contains('%')) return this
    val builder = StringBuilder(length)
    var index = 0
    while (index < length) {
        val char = this[index]
        if (char == '%' && index + 2 < length) {
            val high = this[index + 1].digitToIntOrNull(16)
            val low = this[index + 2].digitToIntOrNull(16)
            if (high != null && low != null) {
                builder.append(((high shl 4) + low).toChar())
                index += 3
                continue
            }
        }
        builder.append(char)
        index += 1
    }
    return builder.toString()
}


/** The `+97` byte shift the scrambled `Create` payload carries. */
private fun descramble(base64Payload: String): String =
    base64ToByteArray(base64Payload)
        .map { (it + 97).toByte() }
        .toByteArray()
        .decodeToString()

@OptIn(ExperimentalEncodingApi::class)
private fun base64ToByteArray(base64: String): ByteArray {
    val normalised =
        base64
            .replace('-', '+')
            .replace('_', '/')
            .replace('.', '=')
    return try {
        Base64.decode(normalised)
    } catch (failure: Exception) {
        throw PoTokenMintException("BotGuard response payload is not valid base64")
    }
}

/**
 * The malformed-body classifications, deliberately fixed text: a parse or decode failure's own
 * message and its cause can echo the response it choked on, so neither travels in the exception.
 */
private fun malformedCreate(): PoTokenMintException = PoTokenMintException("BotGuard Create response is malformed")

private fun malformedIntegrityToken(): PoTokenMintException =
    PoTokenMintException("BotGuard GenerateIT response is malformed")

/** A JavaScript double-quoted string literal: every non-ASCII unit escapes, including separators. */
internal fun poTokenJsString(value: String): String = buildString(value.length + 2) {
    append('"')
    for (character in value) {
        when {
            character == '"' -> append("\\\"")
            character == '\\' -> append("\\\\")
            character.code in 0x20..0x7e -> append(character)
            else -> append("\\u").append(character.code.toString(16).padStart(4, '0'))
        }
    }
    append('"')
}
