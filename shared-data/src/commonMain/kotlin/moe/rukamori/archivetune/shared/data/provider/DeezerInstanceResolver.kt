package moe.rukamori.archivetune.shared.data.provider

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareRequest
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.Url
import io.ktor.http.encodeURLParameter
import io.ktor.utils.io.readRemaining
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A faithful port of the retained `DeezerInstances` object: the user's instance list plus
 * pool-discovered mirrors, cooldown-ordered probing of `$base/stream/?isrc=` /
 * `$base/stream/{trackId}`, and the first-bytes audio probe. Instances serve already-decrypted
 * audio, so the caller emits a plain Progressive stream — never `deezer://`.
 */
class DeezerInstanceResolver(
    private val client: HttpClient,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val poolEnabled: () -> Boolean = { false },
    private val sourceProviderUrl: () -> String = { "" },
    private val sourceProviderKey: () -> String = { "" },
    discoveryStore: PoolDiscoveryStore = NoPoolDiscoveryStore,
) {
    data class Stream(
        val url: String,
        val flac: Boolean,
        val contentLength: Long?,
        val instance: String,
    )

    private enum class Shape { ISRC, TRACK_ID }

    private val stateMutex = Mutex()

    @Volatile
    private var userInstances: List<String> = emptyList()

    @Volatile
    private var discovered: List<String> = emptyList()

    /** Six-hour jittered cadence, persisted list, ETag revalidation and refusal backoff. */
    private val discoveryFeed =
        PoolDiscoveryFeed(
            service = "deezer",
            nowMs = nowMs,
            candidateFeeds = ::poolFeedUrls,
            request = { url, headers -> exchange(url, HEALTH_TIMEOUT_MS, emptyList(), headers).toPoolResponse() },
            parse = { body -> PoolDiscoveryPayload.urls(body) },
            enabled = poolEnabled,
            readKey = sourceProviderKey,
            store = discoveryStore,
        )

    private val cooldownUntil = mutableMapOf<String, Long>()
    private val workingShape = mutableMapOf<String, Shape>()

    fun parse(raw: String): List<String> =
        raw
            .split('\n', '\r', ',', ' ')
            .mapNotNull(::normalize)
            .distinct()

    /** Applies the user's own instance list. Cheap and idempotent. */
    fun setUserInstances(raw: String) {
        userInstances = parse(raw)
    }

    fun setUserInstances(instances: List<String>) {
        userInstances = instances.mapNotNull(::normalize).distinct()
    }

    suspend fun invalidatePoolInstancesCache() {
        discovered = emptyList()
        discoveryFeed.forget()
    }

    /**
     * True when a resolve could reach at least one instance. Cheap, no network: counts user entries,
     * cached pool entries, and a configured pool whose feed has not been fetched yet.
     */
    fun hasInstances(): Boolean =
        userInstances.isNotEmpty() || (poolEnabled() && poolFeedUrls().isNotEmpty())

    suspend fun instances(): List<String> {
        val pool = if (poolEnabled()) discoverIfStale() else emptyList()
        val merged = LinkedHashSet<String>().apply {
            addAll(userInstances)
            addAll(pool)
        }
        val now = nowMs()
        return stateMutex.withLock { merged.sortedBy { (cooldownUntil[it] ?: 0L) > now } }
    }

    /**
     * Asks each instance for the recording and returns the first that answers with audio. ISRC is
     * tried before track id unless a shape already worked for that instance.
     */
    suspend fun resolve(
        isrc: String?,
        trackId: String?,
    ): Stream? {
        if (isrc.isNullOrBlank() && trackId.isNullOrBlank()) return null
        for (base in instances()) {
            val shapes =
                buildList {
                    stateMutex.withLock { workingShape[base] }?.let(::add)
                    if (!isrc.isNullOrBlank()) add(Shape.ISRC)
                    if (!trackId.isNullOrBlank()) add(Shape.TRACK_ID)
                }.distinct()
            for (shape in shapes) {
                val url =
                    when (shape) {
                        Shape.ISRC -> isrc?.takeIf { it.isNotBlank() }?.let { "$base/stream/?isrc=${urlEncode(it)}" }
                        Shape.TRACK_ID -> trackId?.takeIf { it.isNotBlank() }?.let { "$base/stream/${urlEncode(it)}" }
                    } ?: continue
                val probed = probe(url, base)
                if (probed != null) {
                    stateMutex.withLock { workingShape[base] = shape }
                    return probed
                }
            }
        }
        return null
    }

    data class Health(
        val instance: String,
        val ok: Boolean,
        val detail: String,
    )

    /** GET `$base/health` for the first configured instance. */
    suspend fun checkFirst(): Health? {
        val base = instances().firstOrNull() ?: return null
        return runCatching {
            val reply = exchange("$base/health", HEALTH_TIMEOUT_MS, emptyList(), emptyMap())
            val body = reply.body.take(20_000)
            val json = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            when {
                reply.status !in 200..299 -> Health(base, false, "HTTP ${reply.status}")
                json == null -> Health(base, false, "not a health payload")
                json["ok"]?.jsonPrimitive?.content == "false" -> Health(base, false, "instance reports it cannot serve")
                else -> {
                    val accounts = json.obj("accounts")
                    if (accounts != null &&
                        (accounts.long("total") ?: 0L) > 0 &&
                        (accounts.long("available") ?: 0L) == 0L &&
                        (accounts.long("cooling") ?: 0L) == 0L
                    ) {
                        Health(base, false, "all backing accounts are dead")
                    } else {
                        Health(base, true, "ok")
                    }
                }
            }
        }.getOrElse { Health(base, false, it.message ?: "unreachable") }
    }

    /** Opens [url] for its first 16 bytes only and accepts it when they are FLAC or MP3. */
    private suspend fun probe(
        url: String,
        base: String,
    ): Stream? {
        return runCatching {
            val probed = headBytes(url)
            if (probed.status != 200 && probed.status != 206) {
                markInstanceFailed(base)
                return@runCatching null
            }
            val head = probed.head
            val read = head.size
            val flac = read >= 4 && head[0] == 'f'.code.toByte() && head[1] == 'L'.code.toByte() &&
                head[2] == 'a'.code.toByte() && head[3] == 'C'.code.toByte()
            val mp3 =
                (read >= 3 && head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte()) ||
                    (read >= 2 && (head[0].toInt() and 0xFF) == 0xFF && (head[1].toInt() and 0xE0) == 0xE0)
            if (!flac && !mp3) {
                markInstanceFailed(base)
                return@runCatching null
            }
            val total =
                probed.contentRange?.substringAfter('/', "")?.toLongOrNull()
                    ?: probed.contentLength?.takeIf { probed.status == 200 && it > 0L }
            Stream(url = url, flac = flac, contentLength = total, instance = base)
        }.onFailure {
            if (it is CancellationException) throw it
            markInstanceFailed(base)
        }.getOrNull()
    }

    private suspend fun markInstanceFailed(base: String) {
        stateMutex.withLock { cooldownUntil[base] = nowMs() + FAILURE_COOLDOWN_MS }
    }

    private fun normalize(raw: String?): String? {
        val trimmed = raw?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return null
        val withScheme =
            if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
        return runCatching { Url(withScheme).host }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { withScheme }
    }

    private fun poolFeedUrls(): List<String> =
        ArchivePoolEndpoints.candidates(sourceProviderUrl()).map { "$it/api/instances/deezer" }

    /** The persisted/feed answer; a failed fetch keeps the previous list. Never throws. */
    private suspend fun discoverIfStale(): List<String> {
        if (!poolEnabled()) return emptyList()
        val result = discoveryFeed.instances().mapNotNull(::normalize).distinct()
        if (!poolEnabled()) return emptyList()
        if (result.isNotEmpty()) discovered = result
        return discovered
    }

    // region HTTP plumbing
    private class Reply(
        val status: Int,
        val body: String,
        val contentLength: Long?,
        val contentRange: String?,
        val retryAfter: String? = null,
        val etag: String? = null,
    ) {
        fun toPoolResponse(): PoolResponse = PoolResponse(status, body, PoolResponse.retryAfterMs(retryAfter), etag)
    }

    private class HeadBytes(
        val status: Int,
        val head: ByteArray,
        val contentLength: Long?,
        val contentRange: String?,
    )

    private suspend fun exchange(
        url: String,
        timeoutMs: Long,
        params: List<Pair<String, String>>,
        headers: Map<String, String>,
    ): Reply =
        withTimeoutOrNull(timeoutMs) {
            client.prepareRequest(url) {
                method = HttpMethod.Get
                header(HttpHeaders.UserAgent, USER_AGENT)
                header(HttpHeaders.Accept, "application/json")
                params.forEach { (key, value) -> parameter(key, value) }
                headers.forEach { (key, value) -> header(key, value) }
            }.execute { response ->
                Reply(
                    status = response.status.value,
                    body = response.bodyAsText(),
                    contentLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull(),
                    contentRange = response.headers[HttpHeaders.ContentRange],
                    retryAfter = response.headers[HttpHeaders.RetryAfter],
                    etag = response.headers["ETag"],
                )
            }
        } ?: throw ProviderRequestTimeout()

    private suspend fun headBytes(url: String): HeadBytes =
        withTimeoutOrNull(HEALTH_TIMEOUT_MS) {
            client.prepareRequest(url) {
                method = HttpMethod.Get
                header(HttpHeaders.UserAgent, USER_AGENT)
                header(HttpHeaders.Accept, "audio/*")
                header(HttpHeaders.Range, "bytes=0-15")
            }.execute { response ->
                val head = response.bodyAsChannel().readRemaining(16).readByteArray()
                HeadBytes(
                    status = response.status.value,
                    head = head,
                    contentLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull(),
                    contentRange = response.headers[HttpHeaders.ContentRange],
                )
            }
        } ?: throw CancellationException("Deezer instance probe timed out")
    // endregion

    private fun urlEncode(value: String): String = value.encodeURLParameter()

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray
    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
    private fun JsonObject.long(key: String): Long? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()

    private companion object {
        private const val FAILURE_COOLDOWN_MS = 10 * 60 * 1000L
        private const val HEALTH_TIMEOUT_MS = 6_000L
        private const val USER_AGENT = "ArchiveTune-Android"
    }
}
