package moe.rukamori.archivetune.shared.data.lyrics

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.content.TextContent
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import moe.rukamori.archivetune.shared.domain.crypto.HmacSha256

/**
 * The bundled Lyprio server: its base URL and the 32-byte request signing key the server shares with its
 * clients. The string form is redacted so the key never reaches a log line.
 */
class LyprioEndpoint private constructor(
    val baseUrl: String,
    internal val signingKey: ByteArray,
) {
    override fun toString(): String = "LyprioEndpoint(redacted)"

    override fun equals(other: Any?): Boolean =
        other is LyprioEndpoint && other.baseUrl == baseUrl && other.signingKey.contentEquals(signingKey)

    override fun hashCode(): Int = 31 * baseUrl.hashCode() + signingKey.contentHashCode()

    companion object {
        /**
         * Null unless [baseUrl] is an absolute HTTPS URL on port 443 without credentials and [signingKeyBase64]
         * decodes to exactly 32 bytes — the only shape Metrolist's bundled endpoint accepts. An explicit
         * `:443` names that same default port, so the stored base URL drops it.
         */
        @OptIn(ExperimentalEncodingApi::class)
        fun parse(baseUrl: String, signingKeyBase64: String): LyprioEndpoint? {
            val normalized = baseUrl.trim().trimEnd('/')
            if (normalized.isEmpty() || normalized.any(Char::isWhitespace)) return null
            val parsed = runCatching { Url(normalized) }.getOrNull() ?: return null
            if (parsed.protocol != URLProtocol.HTTPS || parsed.port != URLProtocol.HTTPS.defaultPort) return null
            if (parsed.host.isBlank() || parsed.user != null || parsed.password != null) return null
            if (!normalized.startsWith("${parsed.protocol.name}://", ignoreCase = true)) return null
            val key = runCatching { Base64.Default.decode(signingKeyBase64.trim()) }.getOrNull() ?: return null
            if (key.size != SIGNING_KEY_BYTES) return null
            val canonical =
                if (parsed.specifiedPort == URLProtocol.HTTPS.defaultPort) {
                    normalized.replaceFirst("${parsed.host}:${parsed.specifiedPort}", parsed.host)
                } else {
                    normalized
                }
            return LyprioEndpoint(canonical, key)
        }

        private const val SIGNING_KEY_BYTES = 32
    }
}

/**
 * The endpoint a Lyprio lookup uses. Lyprio is a built-in provider: the only server it can reach is the
 * pair baked into the build ([buildBaseUrl]/[buildSigningKeyBase64]), matching Metrolist, whose server
 * is run by its developers and has no user setting. A missing or malformed build value simply leaves
 * Lyprio unconfigured — the provider is then never created and never called. The build values are read
 * at lookup time and are never written to preferences.
 */
fun resolveLyprioEndpoint(
    buildBaseUrl: String,
    buildSigningKeyBase64: String,
): LyprioEndpoint? {
    if (buildBaseUrl.isBlank() || buildSigningKeyBase64.isBlank()) return null
    return LyprioEndpoint.parse(buildBaseUrl, buildSigningKeyBase64)
}

@Serializable
internal data class LyprioLyricsRequest(
    val title: String,
    val artist: String,
    val album: String? = null,
    val duration: Long? = null,
    val videoId: String? = null,
    val allSources: Boolean = false,
)

@Serializable
internal data class LyprioLyricsResponse(
    val results: List<LyprioLyricsResult> = emptyList(),
    val status: String? = null,
    val complete: Boolean? = null,
    val retryAfterMs: Long? = null,
)

@Serializable
internal data class LyprioLyricsResult(
    val subprovider: String,
    val format: String,
    val syncType: String,
    val lyrics: String,
)

/**
 * Port of Metrolist's Lyprio client for the bundled server. Requests are signed POSTs: the signature is the
 * hex HMAC-SHA256 of "timestamp\nbody" under the shared key. Protocol 2 (`/v2/lyrics/search`) may answer
 * pending/unavailable with a retry delay; a server without protocol 2 is asked over `/v1` for ten minutes.
 *
 * Unlike Metrolist (25 s per request, 50 s per search, 20 s of retry waits) the whole lookup is held to
 * [SOURCE_TIMEOUT_MS], the budget every non-SimpMusic provider gets on this port, and a retry that cannot finish
 * inside it fails at once instead of sleeping into the deadline. MLX documents are skipped because this port's
 * parser reads LRC and TTML only; LRC, TTML and plain results are returned as the server sent them.
 */
class LyprioLyricsProvider(
    private val client: HttpClient,
    private val endpoint: LyprioEndpoint,
    private val nowEpochSeconds: () -> Long = { Clock.System.now().epochSeconds },
    private val nextJitterMs: () -> Long = { Random.nextLong(1L, MAX_JITTER_MS + 1) },
) : LyricsProvider {
    override val name: String = NAME

    private var v2UnsupportedUntil: Long? = null

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        durationSec: Int,
        isrc: String?,
    ): Result<String> =
        suspendRunCatching {
            require(title.isNotBlank() && artist.isNotBlank()) { "Song title and artist are required" }
            search(id, title, artist, album, durationSec, allSources = false).firstOrNull()
                ?: throw IllegalStateException("Lyrics unavailable")
        }

    override suspend fun getAllLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        durationSec: Int,
        isrc: String?,
        onFailure: (Throwable) -> Unit,
        callback: (String) -> Unit,
    ) {
        if (title.isBlank() || artist.isBlank()) return
        try {
            search(id, title, artist, album, durationSec, allSources = true).forEach(callback)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            if (error.isTransientLyricsFailure()) onFailure(error)
        }
    }

    private suspend fun search(
        id: String,
        title: String,
        artist: String,
        album: String?,
        durationSec: Int,
        allSources: Boolean,
    ): List<String> {
        val request =
            LyprioLyricsRequest(
                title = title,
                artist = artist,
                album = album?.takeIf { it.isNotBlank() },
                duration = durationSec.takeIf { it > 0 }?.toLong(),
                videoId = id.trim().takeIf { YOUTUBE_VIDEO_ID.matches(it) },
                allSources = allSources,
            )
        val body = LyprioJson.encodeToString(LyprioLyricsRequest.serializer(), request)
        val started = TimeSource.Monotonic.markNow()
        return withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
            if (isV2Unsupported()) requestV1(body) else requestV2(body) { started.elapsedNow().inWholeMilliseconds }
        } ?: throw IllegalStateException("Lyrics source timed out")
    }

    private fun isV2Unsupported(): Boolean {
        val until = v2UnsupportedUntil ?: return false
        if (nowEpochSeconds() < until) return true
        v2UnsupportedUntil = null
        return false
    }

    private suspend fun requestV2(body: String, elapsedMs: () -> Long): List<String> {
        var attempt = 0
        while (true) {
            val response = send(V2_SEARCH_PATH, body, clientRomanization = false)
            val protocol = response.headers[PROTOCOL_HEADER]
            if ((response.status == HttpStatusCode.NotFound && protocol == null) ||
                response.status == HttpStatusCode.MethodNotAllowed
            ) {
                val now = nowEpochSeconds()
                v2UnsupportedUntil = if (now > Long.MAX_VALUE - UNSUPPORTED_TTL_SECONDS) Long.MAX_VALUE else now + UNSUPPORTED_TTL_SECONDS
                return requestV1(body)
            }
            check(protocol == PROTOCOL_VERSION) { "Lyprio returned an invalid protocol marker" }
            v2UnsupportedUntil = null
            val parsed = LyprioJson.decodeFromString(LyprioLyricsResponse.serializer(), boundedText(response))
            val status = checkNotNull(parsed.status) { "Lyprio response has no status" }
            val complete = checkNotNull(parsed.complete) { "Lyprio response has no completion state" }
            when (response.status) {
                HttpStatusCode.OK -> {
                    check(status == "ready") { "Lyprio returned an invalid ready response" }
                    val results = parsed.toLyricsTexts()
                    check(results.isNotEmpty()) { "Lyprio returned no usable lyrics" }
                    return results
                }
                HttpStatusCode.NotFound -> {
                    check(status == "not_found" && complete) { "Lyprio returned an invalid miss response" }
                    check(parsed.results.isEmpty()) { "Lyprio returned results with a miss status" }
                    return emptyList()
                }
                HttpStatusCode.Accepted, HttpStatusCode.TooManyRequests, HttpStatusCode.ServiceUnavailable -> {
                    val temporary =
                        if (response.status == HttpStatusCode.Accepted) status == "pending" && !complete
                        else status == "unavailable" && !complete
                    check(temporary) { "Lyprio returned an invalid temporary response" }
                    check(attempt < MAX_RETRIES) { "Lyprio retry limit reached" }
                    val wait = retryDelayMs(parsed.retryAfterMs, response.headers[HttpHeaders.RetryAfter])
                    check(wait < SOURCE_TIMEOUT_MS - elapsedMs()) { "Lyprio retry delay exceeds the wait budget" }
                    delay(wait)
                    attempt++
                }
                else -> throw IllegalStateException("Lyprio request failed with ${response.status.value}")
            }
        }
    }

    private suspend fun requestV1(body: String): List<String> {
        val response = send(V1_SEARCH_PATH, body, clientRomanization = true)
        val text = boundedText(response)
        if (response.status == HttpStatusCode.NotFound) return emptyList()
        check(response.status == HttpStatusCode.OK) { "Lyprio request failed with ${response.status.value}" }
        return LyprioJson.decodeFromString(LyprioLyricsResponse.serializer(), text).toLyricsTexts()
    }

    /** The server's delay (the larger of Retry-After seconds and the body's retryAfterMs) plus 1-1000 ms of jitter. */
    private fun retryDelayMs(retryAfterMs: Long?, retryAfterHeader: String?): Long {
        val headerDelay =
            retryAfterHeader?.trim()?.takeIf { it.isNotEmpty() }?.let { value ->
                val seconds = value.toLongOrNull()
                if (seconds == null) {
                    check(value.any { !it.isDigit() }) { "Lyprio Retry-After is too large" }
                    null
                } else {
                    check(seconds >= 0) { "Lyprio returned an invalid Retry-After" }
                    check(seconds <= Long.MAX_VALUE / 1000) { "Lyprio Retry-After is too large" }
                    seconds * 1000
                }
            }
        retryAfterMs?.let { check(it >= 0) { "Lyprio returned an invalid retryAfterMs" } }
        val base = listOfNotNull(headerDelay, retryAfterMs).maxOrNull() ?: throw IllegalStateException("Lyprio omitted its retry delay")
        val jitter = nextJitterMs()
        check(jitter in 1..MAX_JITTER_MS) { "Lyprio retry jitter is invalid" }
        check(base <= Long.MAX_VALUE - jitter) { "Lyprio retry delay exceeds the wait budget" }
        return base + jitter
    }

    private suspend fun send(path: String, body: String, clientRomanization: Boolean): HttpResponse {
        val timestamp = nowEpochSeconds().toString()
        return client.post(endpoint.baseUrl + path) {
            header(TIMESTAMP_HEADER, timestamp)
            header(SIGNATURE_HEADER, HmacSha256.signHex(endpoint.signingKey, "$timestamp\n$body"))
            if (clientRomanization) header(ROMANIZATION_HEADER, CLIENT_ROMANIZATION)
            header(HttpHeaders.UserAgent, LyricsHttp.USER_AGENT)
            setBody(TextContent(body, ContentType.Application.Json))
        }
    }

    private suspend fun boundedText(response: HttpResponse): String {
        response.headers[HttpHeaders.ContentLength]?.toLongOrNull()?.let {
            check(it <= MAX_RESPONSE_BYTES) { "Lyprio response is too large" }
        }
        val text = response.bodyAsText()
        check(text.length <= MAX_RESPONSE_BYTES) { "Lyprio response is too large" }
        return text
    }

    private fun LyprioLyricsResponse.toLyricsTexts(): List<String> =
        results.sortedByDescending { it.syncQuality() }.mapNotNull { it.toLyricsText() }

    private fun LyprioLyricsResult.syncQuality(): Int =
        when (syncType.lowercase().replace('_', '-')) {
            "word", "words", "word-synced", "word-synchronized" -> 2
            "line", "lines", "line-synced", "line-synchronized" -> 1
            else -> 0
        }

    private fun LyprioLyricsResult.toLyricsText(): String? {
        val content = lyrics.takeIf { it.isNotBlank() } ?: return null
        return when (format.lowercase()) {
            "lrc", "ttml" -> content
            "plain" -> content.lineSequence().filter { it.isNotBlank() }.joinToString("\n") { it.trimEnd() }
            else -> null
        }
    }

    companion object {
        const val NAME = "Lyprio"

        /** Per-provider budget on this port: SimpMusic keeps 4000 ms, every other provider 3000 ms. */
        const val SOURCE_TIMEOUT_MS = 3_000L

        private const val V2_SEARCH_PATH = "/v2/lyrics/search"
        private const val V1_SEARCH_PATH = "/v1/lyrics/search"
        private const val PROTOCOL_HEADER = "X-Lyprio-Protocol"
        private const val PROTOCOL_VERSION = "2"
        private const val TIMESTAMP_HEADER = "X-Lyprio-Timestamp"
        private const val SIGNATURE_HEADER = "X-Lyprio-Signature"
        private const val ROMANIZATION_HEADER = "X-Lyprio-Romanization"
        private const val CLIENT_ROMANIZATION = "client"
        private const val MAX_RESPONSE_BYTES = 8L * 1024 * 1024
        private const val MAX_RETRIES = 5
        private const val MAX_JITTER_MS = 1_000L
        private const val UNSUPPORTED_TTL_SECONDS = 600L
    }
}

/** Telegram, local and other provider ids must not reach the server as a YouTube video id. */
private val YOUTUBE_VIDEO_ID = Regex("""[A-Za-z0-9_-]{11}""")

/** Defaults are not encoded, matching the Metrolist body byte for byte, since the signature covers the exact text. */
private val LyprioJson: Json = Json { ignoreUnknownKeys = true }
