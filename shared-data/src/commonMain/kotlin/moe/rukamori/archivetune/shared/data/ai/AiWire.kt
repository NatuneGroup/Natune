/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.data.ai

import kotlinx.serialization.json.*
import moe.rukamori.archivetune.shared.domain.lyrics.ai.AiProvider
import moe.rukamori.archivetune.shared.domain.lyrics.ai.AiSegmentBatchCheck
import moe.rukamori.archivetune.shared.domain.lyrics.ai.AiSegmentBatchPolicy

/** Open so one owned subtype can be retried while every other refusal from the transport stays final. */
open class AiTransportException(message: String) : Exception(message)

/** A bounded wait ran out. Unlike validation or configuration failures, a timeout is transient. */
class AiRequestTimeoutException(message: String) : AiTransportException(message)

internal val aiJson = Json { ignoreUnknownKeys = true }
internal fun JsonElement.objectOrFail(): JsonObject =
    this as? JsonObject ?: throw AiTransportException("Invalid AI response object")
internal fun JsonElement?.stringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
internal fun JsonObject.array(name: String): JsonArray =
    this[name] as? JsonArray ?: throw AiTransportException("Missing AI response array")
internal fun parseObject(raw: String): JsonObject = try {
    if (raw.length > 2_000_000) throw AiTransportException("AI response is too large")
    aiJson.parseToJsonElement(raw).objectOrFail()
} catch (e: IllegalArgumentException) {
    throw AiTransportException("Malformed AI response")
}

internal fun parseSegments(raw: String, expected: Int): List<String> {
    val start = raw.indexOf('[')
    val end = raw.lastIndexOf(']')
    if (start < 0 || end < start) throw AiTransportException("Missing AI lyric segments")
    val array = try { aiJson.parseToJsonElement(raw.substring(start, end + 1)) as? JsonArray }
        catch (e: IllegalArgumentException) { null }
        ?: throw AiTransportException("Invalid AI lyric segments")
    return validateSegments(array.map { it.stringOrNull() }, expected)
}

/**
 * Count and blank checks come from the shared batch policy, so the transport and the M6 document
 * consumers reject the same replies. A null segment is a non-string and counts as blank.
 */
internal fun validateSegments(lines: List<String?>, expected: Int): List<String> =
    when (val check = AiSegmentBatchPolicy.validate(expected, lines.map { it ?: "" })) {
        is AiSegmentBatchCheck.Accepted -> check.segments
        is AiSegmentBatchCheck.Incomplete ->
            throw AiTransportException("AI response changed or omitted lyric segments")
    }

internal fun defaultModel(provider: AiProvider): String = when (provider) {
    AiProvider.CHATGPT -> "gpt-4o"
    AiProvider.GEMINI -> "gemini-3.5-flash"
    AiProvider.OPENROUTER -> "~openai/gpt-latest"
    AiProvider.MISTRAL -> "mistral-small-latest"
    else -> throw AiTransportException("No AI model configured")
}

private const val OPEN_ROUTER_CHAT = "https://openrouter.ai/api/v1/chat/completions"
private const val OPEN_ROUTER_MODELS = "https://openrouter.ai/api/v1/models"

/**
 * Endpoint rules match the Android helper: a bare base gets `/v1` added, a full chat path is used as
 * written, and a blank base falls back to the public router. Both helpers trim trailing slashes first.
 */
internal fun openRouterCompletionEndpoint(baseUrl: String): String {
    val value = baseUrl.trim().trimEnd('/')
    return when {
        value.isBlank() -> OPEN_ROUTER_CHAT
        value.endsWith("/chat/completions") -> value
        value.endsWith("/v1") -> "$value/chat/completions"
        else -> "$value/v1/chat/completions"
    }
}

internal fun openRouterModelsEndpoint(baseUrl: String): String {
    val value = baseUrl.trim().trimEnd('/')
    return when {
        value.isBlank() -> OPEN_ROUTER_MODELS
        value.endsWith("/models") -> value
        value.endsWith("/chat/completions") -> value.removeSuffix("/chat/completions") + "/models"
        value.endsWith("/v1") -> "$value/models"
        else -> "$value/v1/models"
    }
}
