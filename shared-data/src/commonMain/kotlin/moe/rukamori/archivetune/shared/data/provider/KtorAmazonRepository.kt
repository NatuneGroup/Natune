package moe.rukamori.archivetune.shared.data.provider

import io.ktor.client.HttpClient
import kotlinx.serialization.json.*
import moe.rukamori.archivetune.shared.domain.repository.*
import moe.rukamori.archivetune.shared.domain.source.*

class AmazonInstanceAccess(val scope: String, val operatorToken: String?, val turnstileJwt: String?,
    val isActive: () -> Boolean) {
    init { require(scope.isNotBlank() && (!operatorToken.isNullOrBlank() || !turnstileJwt.isNullOrBlank())) }
    override fun toString() = "AmazonInstanceAccess(REDACTED)"
}

/** First authorization does not require constructing an already-authorized playback repository. */
class KtorAmazonAuthorization(client: HttpClient, instanceBase: String) {
    private val http = ProviderHttp(client)
    private val base = endpoint(instanceBase)
    suspend fun exchangeTurnstile(responseToken: String, operatorToken: String? = null): String {
        require(responseToken.isNotBlank())
        val root = http.json("$base/api/auth/turnstile", headers = buildMap {
            put("Origin", "https://monochrome.tf")
            operatorToken?.takeIf { it.isNotBlank() }?.let { put("Authorization", "Bearer $it") }
        }, body = buildJsonObject {
            put("cf_turnstile_response", responseToken); put("turnstile_token", responseToken)
        }).jsonObject
        return listOf("access_token", "jwt", "token").firstNotNullOfOrNull { root.text(it) }
            ?: throw ProviderProtocolFailure()
    }
}

/** Real authorized instance protocols; challenge solving/expiry/cooldown remain with the account owner. */
class KtorAmazonRepository(client: HttpClient, instanceBase: String, private val access: AmazonInstanceAccess) :
    AudioProviderRepository {
    override val source = AudioSourceType.AMAZON
    private val base = endpoint(instanceBase)
    private val http = ProviderHttp(client)
    suspend fun exchangeTurnstile(responseToken: String): String {
        require(responseToken.isNotBlank() && access.isActive())
        val root = http.json("$base/api/auth/turnstile", headers = buildMap {
            put("Origin", "https://monochrome.tf")
            access.operatorToken?.let { put("Authorization", "Bearer $it") }
        }, body = buildJsonObject {
            put("cf_turnstile_response", responseToken); put("turnstile_token", responseToken)
        }).jsonObject
        check(access.isActive())
        return listOf("access_token", "jwt", "token").firstNotNullOfOrNull { root.text(it) }
            ?: throw ProviderProtocolFailure()
    }
    override suspend fun resolve(request: AudioResolveRequest): PreparedAudio? {
        check(access.isActive())
        if (request.target.title.isBlank() || request.target.artists.isEmpty()) return null
        val quality = request.qualities[source] ?: "LOSSLESS"
        val params = mutableMapOf("track" to request.target.title, "artist" to request.target.artists.joinToString(", "))
        request.target.album?.takeIf { it.isNotBlank() }?.let { params["album"] = it }
        request.target.durationMs?.takeIf { it > 0 }?.let { params["duration"] = "${it / 1000 + if (it % 1000 >= 500) 1 else 0}" }
        val headers = buildMap {
            put("Origin", "https://monochrome.tf")
            access.turnstileJwt?.takeIf { it.isNotBlank() }?.let { put("X-Turnstile-JWT", it) }
        }
        var root: JsonObject? = null
        var gen2 = false
        if (!access.operatorToken.isNullOrBlank()) {
            val reply = http.exchange("$base/api/v2/track/", params + mapOf("quality" to quality2(quality), "intent" to "stream"),
                headers + ("Authorization" to "Bearer ${access.operatorToken}"))
            val parsed = try { Json.parseToJsonElement(reply.body) as? JsonObject } catch (e: Exception) { null }
            val envelope = parsed?.containsKey("playback") == true || parsed?.containsKey("schema_version") == true
            when {
                reply.status in 200..299 -> { root = parsed ?: throw ProviderProtocolFailure(); gen2 = true }
                reply.status in setOf(401, 403, 428) -> Unit
                reply.status in setOf(404, 502) && !envelope -> Unit
                else -> throw ProviderHttpFailure(reply.status)
            }
            if (envelope && root == null && reply.status !in setOf(401, 403, 428)) return null
        }
        if (root == null) {
            val old = params + mapOf("album" to request.target.album.orEmpty(), "duration" to params["duration"].orEmpty(),
                "quality" to quality1(quality)) +
                (access.operatorToken?.let { mapOf("bypass_token" to it) } ?: emptyMap())
            root = http.json("$base/api/track/", old, headers).jsonObject
        }
        val response = requireNotNull(root)
        if (response.preview()) return null
        val payload: JsonObject
        val metadata: JsonObject
        if (gen2) {
            val major = response.text("schema_version")?.substringBefore('.')
            if (major != null && major !in setOf("1", "2")) throw ProviderProtocolFailure()
            payload = response.array("playback")?.mapNotNull { it as? JsonObject }?.firstOrNull {
                it.text("kind").equals("audio", true) && it.text("delivery").equals("direct", true) &&
                    (it.text("source") ?: response.text("selected_source")).let { s -> s == null || s.equals("amazon", true) }
            } ?: return null
            metadata = response.obj("track") ?: return null
        } else {
            payload = sequenceOf(response, response.obj("data"), response.obj("track"), response.obj("result"))
                .filterNotNull().firstOrNull { it.text("stream_url") != null } ?: return null
            metadata = payload
        }
        if (payload.preview()) return null
        val url = streamUrl(payload.text(if (gen2) "url" else "stream_url") ?: return null)
        val title = metadata.text("title") ?: metadata.text("name") ?: return null
        fun name(e: JsonElement?): String? = (e as? JsonPrimitive)?.contentOrNull
            ?: (e as? JsonObject)?.let { it.text("name") ?: it.text("title") }
        val artists = metadata.array("artists")?.mapNotNull(::name)?.takeIf { it.isNotEmpty() }
            ?: listOfNotNull(name(metadata["artist"]))
        val seconds = (metadata["duration"] as? JsonPrimitive)?.doubleOrNull
        val duration = metadata.long("duration_ms") ?: seconds?.takeIf { it.isFinite() && it > 0 }?.let {
            if (it > 10_000) it.toLong() else (it * 1000).toLong()
        }
        // Never substitute the wanted metadata for omitted instance metadata.
        val codec = payload.text("codec").orEmpty()
        val stream = DirectStream(url, "audio/mp4", codec, payload.long("content_length"), "Amazon Music", source,
            title, artists.joinToString(", "), name(metadata["album"]), duration,
            sampleRate = positive(payload, "sample_rate_hz", "sampleRateHz", "sample_rate", "sampleRate"),
            bitDepth = positive(payload, "bit_depth", "bitDepth"), accountScope = streamAccount(access.scope))
        val key = sequenceOf(payload.text("decryption_key"), payload.text("decryptionKey"),
            payload.obj("encryption")?.obj("key")?.text("value"), payload.obj("decryption")?.obj("key")?.text("value"),
            payload.obj("decryption")?.text("key"), payload.obj("drm")?.text("decryption_key"),
            payload.obj("drm")?.text("decryptionKey")).filterNotNull().firstOrNull()
        check(access.isActive())
        return if (key != null) PreparedAudio.Native(stream,
            NativeAudioRecipe.AmazonCenc(url, AudioSecret(key), access.scope)) else PreparedAudio.Progressive(stream)
    }
    private fun positive(o: JsonObject, vararg keys: String): Int? =
        keys.firstNotNullOfOrNull { o.long(it)?.takeIf { v -> v in 1..Int.MAX_VALUE }?.toInt() }
    private fun quality2(q: String) = when (q.uppercase()) {
        "ULTRA_HD", "HI_RES_LOSSLESS", "AUTO", "ADAPTIVE", "DOLBY_ATMOS" -> "HI_RES_LOSSLESS"
        "HD", "LOSSLESS" -> "LOSSLESS"; "STANDARD", "HIGH" -> "HIGH"; "LOW" -> "LOW"; "NORMAL" -> "NORMAL"
        else -> throw IllegalArgumentException("Unknown Amazon quality")
    }
    private fun quality1(q: String) = when (quality2(q)) {
        "HI_RES_LOSSLESS" -> "UHD"; "LOSSLESS" -> "HD"; "HIGH" -> "SD_HIGH"; "LOW" -> "SD_LOW"; else -> "SD_MEDIUM"
    }
}
