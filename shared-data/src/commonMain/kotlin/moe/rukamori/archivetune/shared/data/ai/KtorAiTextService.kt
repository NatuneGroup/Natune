/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.data.ai

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import moe.rukamori.archivetune.shared.domain.lyrics.ai.*
import moe.rukamori.archivetune.shared.domain.repository.AiTextService
import moe.rukamori.archivetune.shared.data.provider.readBoundedText

/**
 * Ktor transport for lyric translation, romanization, model listing, the health check and budgeted chat
 * completions. The host supplies the engine (proxy, TLS, credential-redacted logging) and one
 * [AiRateLimiter] shared by every AI consumer, so the hourly budget is global rather than per caller.
 */
class KtorAiTextService(
    private val client: HttpClient,
    private val limiter: AiRateLimiter = AiRateLimiter(),
    /**
     * Caps items per request. The character budget from the shared domain policy still applies on top,
     * so a caller that passes a whole document gets the same request count as the Android helpers.
     */
    private val batchSize: Int = AiSegmentBatchPolicy.MAX_ITEMS_PER_BATCH,
) : AiTextService {
    init { require(batchSize in 1..AiSegmentBatchPolicy.MAX_ITEMS_PER_BATCH) }

    override suspend fun translateLines(config: AiServiceSettings, targetLanguage: String,
        lines: List<String>, formatName: String): List<String> =
        transform(config, lines, formatName, targetLanguage, romanize = false)

    override suspend fun romanizeLines(config: AiServiceSettings, lines: List<String>, formatName: String): List<String> {
        if (!config.supportsRomanization) throw AiTransportException("Provider does not support romanization")
        return transform(config, lines, formatName, "Latin", romanize = true)
    }

    private suspend fun transform(config: AiServiceSettings, lines: List<String>, format: String,
        target: String, romanize: Boolean): List<String> {
        if (lines.isEmpty()) return emptyList()
        configured(config)
        if (lines.size > MAX_INPUT_LINES || lines.sumOf { it.length.toLong() } > MAX_INPUT_CHARS)
            throw AiTransportException("AI lyric input is too large")
        val feature = if (romanize) AiRateLimiter.Feature.LYRICS_ROMANIZATION else AiRateLimiter.Feature.LYRICS_TRANSLATION
        val result = ArrayList<String>(lines.size)
        for (indices in batchIndices(lines)) {
            currentCoroutineContext().ensureActive()
            val batch = indices.map { lines[it] }
            result += limiter.withLimit(feature) {
                when (config.provider) {
                    AiProvider.DEEPL -> deepL(config, target, batch)
                    AiProvider.MISTRAL -> mistral(config, target, batch, romanize)
                    else -> {
                        val system = if (romanize) romanizationSystemPrompt(format, batch.size)
                            else translationSystemPrompt(target, format, batch.size)
                        val reply = chat(config, system, JsonArray(batch.map { JsonPrimitive(it) }).toString(),
                            if (romanize) 0.0 else 0.15, 8192)
                        parseSegments(reply, batch.size)
                    }
                }
            }
        }
        return result
    }

    /** Budget chunks from the shared domain policy, further capped by [batchSize] for smaller requests. */
    private fun batchIndices(lines: List<String>): List<List<Int>> =
        AiSegmentBatchPolicy.chunkByBudget(lines.map { it.length })
            .flatMap { range -> range.toList().chunked(batchSize) }

    override suspend fun listModels(config: AiServiceSettings): List<String> =
        fetchModels(config).map { it.id }

    override suspend fun fetchModels(config: AiServiceSettings): List<AiModelOption> {
        configured(config)
        return when (config.provider) {
            AiProvider.CHATGPT, AiProvider.OPENROUTER -> {
                val endpoint = if (config.provider == AiProvider.CHATGPT) OPEN_AI_MODELS
                    else openRouterModelsEndpoint(config.customEndpoint)
                get(endpoint, config).modelEntries("data").mapNotNull { entry ->
                    entry["id"].stringOrNull()?.takeIf(String::isNotBlank)?.let { AiModelOption(it, it) }
                }.sortedBy { it.id }
            }
            AiProvider.GEMINI -> get("$GEMINI/models", config).modelEntries("models").mapNotNull { entry ->
                val methods = entry["supportedGenerationMethods"] as? JsonArray
                if (methods?.any { it.stringOrNull() == "generateContent" } != true) return@mapNotNull null
                val id = entry["name"].stringOrNull()?.removePrefix("models/")?.takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                AiModelOption(id, entry["displayName"].stringOrNull()?.takeIf(String::isNotBlank) ?: id)
            }
            else -> emptyList()
        }
    }

    override suspend fun test(config: AiServiceSettings) {
        configured(config)
        when (config.provider) {
            // DeepL and Mistral have no chat endpoint, so their health check is one real translation.
            AiProvider.DEEPL, AiProvider.MISTRAL -> translateLines(config, "English", listOf("Hello"), "plain")
            else -> {
                val reply = chat(config, "You are a health check endpoint. Reply with OK only.",
                    "Reply exactly OK.", 0.0, 32)
                if (!reply.contains("OK", ignoreCase = true)) throw AiTransportException("Unexpected AI API test response")
            }
        }
    }

    override suspend fun complete(config: AiServiceSettings, budget: AiRequestBudget, systemPrompt: String,
        userPrompt: String, temperature: Double, maxTokens: Int): String {
        configured(config)
        return limiter.withLimit(budget.limiterFeature()) {
            chat(config, systemPrompt, userPrompt, temperature, maxTokens)
        }
    }

    private suspend fun chat(config: AiServiceSettings, system: String, user: String,
        temperature: Double, maxTokens: Int): String {
        val model = config.model.ifBlank { defaultModel(config.provider) }
        if (config.provider == AiProvider.GEMINI) {
            // The model is a single path component, not an arbitrary user-controlled URL.
            val modelId = model.removePrefix("models/")
            if (!modelId.matches(Regex("[a-zA-Z0-9._-]+"))) throw AiTransportException("Invalid Gemini model")
            val body = buildJsonObject {
                putJsonObject("systemInstruction") { putJsonArray("parts") { addJsonObject { put("text", system) } } }
                putJsonArray("contents") { addJsonObject {
                    put("role", "user"); putJsonArray("parts") { addJsonObject { put("text", user) } }
                } }
                putJsonObject("generationConfig") { put("temperature", temperature); put("maxOutputTokens", maxTokens) }
            }
            val response = post("$GEMINI/models/$modelId:generateContent", config, body)
            return response.array("candidates").firstOrNull()?.objectOrFail()?.get("content")
                ?.objectOrFail()?.array("parts")?.firstOrNull()?.objectOrFail()?.get("text").stringOrNull()
                ?.takeIf(String::isNotBlank) ?: throw AiTransportException("Empty AI response")
        }
        val endpoint = when (config.provider) {
            AiProvider.CHATGPT -> OPEN_AI_CHAT
            AiProvider.OPENROUTER -> openRouterCompletionEndpoint(config.customEndpoint)
            AiProvider.CUSTOM -> config.customEndpoint.trim()
            else -> throw AiTransportException("Provider does not support chat completion")
        }
        return content(post(endpoint, config, chatBody(model, system, user, temperature, maxTokens)))
    }

    private suspend fun deepL(config: AiServiceSettings, target: String, lines: List<String>): List<String> {
        val code = DeepLTargets.targetCode(target) ?: throw AiTransportException("Unsupported DeepL target language")
        val endpoint = if (config.apiKey.endsWith(":fx")) "https://api-free.deepl.com/v2/translate"
            else "https://api.deepl.com/v2/translate"
        val body = buildJsonObject {
            put("text", JsonArray(lines.map { JsonPrimitive(it) })); put("target_lang", code)
            if (config.deeplFormality != "default") put("formality", config.deeplFormality)
            put("preserve_formatting", true)
        }
        return retryTranslation {
            validateSegments(post(endpoint, config, body).array("translations").map {
                it.objectOrFail()["text"].stringOrNull()
            }, lines.size)
        }
    }

    private suspend fun mistral(config: AiServiceSettings, target: String, lines: List<String>, romanize: Boolean): List<String> {
        val system = if (romanize) MISTRAL_ROMANIZE_SYSTEM
            else "You are a translation assistant. Translate each line of the following lyrics into $target. " +
                "Preserve line breaks exactly. Output only the translated lines, one per input line, no numbering."
        // Romanize requests say "Romanize" here: the Android wording asked for a translation, which invites meaning drift.
        val user = (if (romanize) "Romanize each line below. " else "Translate each line below. ") +
            "Keep the same number of lines.\n\n" +
            lines.mapIndexed { i, line -> "${i + 1}. $line\n" }.joinToString("")
        return retryTranslation {
            val result = content(post(MISTRAL_CHAT, config,
                chatBody(config.model.ifBlank { defaultModel(config.provider) }, system, user, 0.2, null)))
            validateSegments(result.trim().split('\n').map { it.trim() }, lines.size)
        }
    }

    /**
     * DeepL and Mistral retry 5xx replies, transport faults and owned timeouts, as the Android helpers do.
     * Client errors and response-validation failures are final, since repeating them cannot change the
     * answer and each attempt spends budget.
     */
    private suspend fun <T> retryTranslation(block: suspend () -> T): T {
        var attempt = 1
        while (true) {
            try {
                return block()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: AiHttpException) {
                if (failure.status < 500 || attempt >= TRANSLATION_ATTEMPTS) throw failure
            } catch (timeout: AiRequestTimeoutException) {
                if (attempt >= TRANSLATION_ATTEMPTS) throw timeout
            } catch (failure: AiTransportException) {
                throw failure
            } catch (failure: Exception) {
                if (attempt >= TRANSLATION_ATTEMPTS) throw failure
            }
            delay(1_000L * attempt)
            attempt++
        }
    }

    private fun content(response: JsonObject): String =
        response.array("choices").firstOrNull()?.objectOrFail()?.get("message")
            ?.objectOrFail()?.get("content").stringOrNull()?.takeIf(String::isNotBlank)
            ?: throw AiTransportException("Empty AI response")

    private fun JsonObject.modelEntries(key: String): List<JsonObject> =
        (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

    private fun configured(config: AiServiceSettings) {
        if (!config.canCallApi) throw AiTransportException("AI provider is not configured")
    }
    private fun validateEndpoint(endpoint: String, config: AiServiceSettings) {
        val url = Url(endpoint)
        val explicitCustomHttp = url.protocol == URLProtocol.HTTP &&
            config.provider in setOf(AiProvider.CUSTOM, AiProvider.OPENROUTER)
        if ((url.protocol != URLProtocol.HTTPS && !explicitCustomHttp) || url.host.isBlank() ||
            !url.user.isNullOrEmpty() || !url.password.isNullOrEmpty())
            throw AiTransportException("Invalid AI endpoint or URL credentials")
    }
    private fun HttpRequestBuilder.auth(config: AiServiceSettings) {
        when (config.provider) {
            AiProvider.GEMINI -> header("x-goog-api-key", config.apiKey.trim())
            AiProvider.DEEPL -> header(HttpHeaders.Authorization, "DeepL-Auth-Key ${config.apiKey.trim()}")
            else -> header(HttpHeaders.Authorization, "Bearer ${config.apiKey.trim()}")
        }
    }
    private suspend fun get(endpoint: String, config: AiServiceSettings): JsonObject {
        validateEndpoint(endpoint, config)
        return withTimeoutOrNull(60_000) {
            client.prepareGet(endpoint) { auth(config) }.execute { read(it) }
        } ?: throw AiRequestTimeoutException("AI request timed out")
    }
    private suspend fun post(endpoint: String, config: AiServiceSettings, body: JsonObject): JsonObject {
        validateEndpoint(endpoint, config)
        return withTimeoutOrNull(60_000) {
            client.preparePost(endpoint) { auth(config); contentType(ContentType.Application.Json); setBody(body.toString()) }
                .execute { read(it) }
        } ?: throw AiRequestTimeoutException("AI request timed out")
    }
    private suspend fun read(response: HttpResponse): JsonObject {
        if (response.status.value !in 200..299) throw AiHttpException(response.status.value)
        return parseObject(response.readBoundedText(2_000_000))
    }
    private companion object {
        const val OPEN_AI_CHAT = "https://api.openai.com/v1/chat/completions"
        const val OPEN_AI_MODELS = "https://api.openai.com/v1/models"
        const val GEMINI = "https://generativelanguage.googleapis.com/v1beta"
        const val MISTRAL_CHAT = "https://api.mistral.ai/v1/chat/completions"
        const val MISTRAL_ROMANIZE_SYSTEM = "You are a romanization assistant. Convert each line of the following " +
            "lyrics into the Latin alphabet. Preserve line breaks exactly. Do not translate meaning; only " +
            "transliterate. Output only the romanized lines."
        const val MAX_INPUT_LINES = 20_000
        const val MAX_INPUT_CHARS = 2_000_000L
        const val TRANSLATION_ATTEMPTS = 3
    }
}

class AiHttpException(val status: Int) : Exception("AI API failed (HTTP $status)")

private fun AiRequestBudget.limiterFeature(): AiRateLimiter.Feature = when (this) {
    AiRequestBudget.LYRICS_TRANSLATION -> AiRateLimiter.Feature.LYRICS_TRANSLATION
    AiRequestBudget.LYRICS_ROMANIZATION -> AiRateLimiter.Feature.LYRICS_ROMANIZATION
    AiRequestBudget.AI_MIX -> AiRateLimiter.Feature.AI_MIX
}

/**
 * Prompts are kept word-for-word with the Android helper because the model's behavior was tuned against
 * them. Changing them is a provider-quality decision, not a refactor.
 */
private fun translationSystemPrompt(target: String, formatName: String, count: Int): String = listOf(
    "You are an expert song lyrics translator.",
    "Translate each input string into $target with natural, accurate lyric phrasing.",
    "Preserve meaning, tone, profanity level, names, repeated hooks, and line-level intent.",
    "Do not add timestamps, IDs, XML, markdown, explanations, or extra lines.",
    "Return only a JSON array of strings with exactly $count items in the same order.",
    "The caller will reconstruct the $formatName lyrics container separately.",
).joinToString("\n")

private fun romanizationSystemPrompt(formatName: String, count: Int): String = listOf(
    "You are an expert lyrics romanisation (transliteration) assistant.",
    "Transliterate each input string into the Latin alphabet exactly as it is sung.",
    "DO NOT TRANSLATE. Never convey meaning — only how the words sound.",
    "Use the standard scheme for the script: Hepburn for Japanese, Revised",
    "Romanization for Korean, Hanyu Pinyin without tone marks for Chinese,",
    "IAST-style for Devanagari, and the common romanisation otherwise.",
    "Keep the original word order, punctuation and casing style.",
    "If a line is already written in the Latin alphabet, return it unchanged.",
    "Do not add timestamps, IDs, XML, markdown, explanations, or extra lines.",
    "Return only a JSON array of strings with exactly $count items in the same order.",
    "The caller will reconstruct the $formatName lyrics container separately.",
).joinToString("\n")

private fun chatBody(model: String, system: String, user: String, temperature: Double, maxTokens: Int?) =
    buildJsonObject {
        put("model", model)
        putJsonArray("messages") {
            addJsonObject { put("role", "system"); put("content", system) }
            addJsonObject { put("role", "user"); put("content", user) }
        }
        put("temperature", temperature)
        if (maxTokens != null) put("max_tokens", maxTokens)
    }
