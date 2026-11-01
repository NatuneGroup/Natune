package moe.rukamori.archivetune.shared.data.provider

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.*
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlinx.serialization.json.*
import moe.rukamori.archivetune.shared.domain.platform.ANONYMOUS_ACCOUNT_SCOPE
import moe.rukamori.archivetune.shared.domain.platform.ContentDigest
import moe.rukamori.archivetune.shared.domain.repository.*
import moe.rukamori.archivetune.shared.domain.source.*

class ProviderHttpFailure(val status: Int) : IllegalStateException("Provider HTTP $status")
class ProviderProtocolFailure : IllegalStateException("Invalid or unavailable provider response")
class ProviderRequestTimeout : IllegalStateException("Provider request timed out")

internal fun endpoint(value: String): String {
    val url = Url(value)
    require(url.protocol == URLProtocol.HTTPS && url.host.isNotBlank() && url.user.isNullOrEmpty() &&
        url.password.isNullOrEmpty() && url.parameters.isEmpty() && url.fragment.isEmpty())
    return value.trimEnd('/')
}
internal fun JsonObject.text(key: String): String? =
    (get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
internal fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject
internal fun JsonObject.array(key: String): JsonArray? = get(key) as? JsonArray
internal fun JsonObject.long(key: String): Long? = (get(key) as? JsonPrimitive)?.longOrNull
internal fun JsonObject.flag(key: String): Boolean = (get(key) as? JsonPrimitive)?.booleanOrNull == true
internal fun JsonObject.preview(): Boolean = flag("sample") || flag("preview") ||
    text("type")?.lowercase() in setOf("preview", "sample") ||
    text("assetPresentation").equals("PREVIEW", true) || text("trackPresentation").equals("PREVIEW", true) ||
    (obj("data")?.preview() == true)
internal fun streamUrl(value: String): String = value.also {
    val url = Url(it)
    require(url.protocol == URLProtocol.HTTPS && url.host.isNotBlank() &&
        url.user.isNullOrEmpty() && url.password.isNullOrEmpty()) { "Provider stream requires HTTPS" }
}
/**
 * The account a stream carries into host admission. The genuinely anonymous scope maps to null, so no admission or
 * personal cookie read runs for an instance that signs nothing in. Any other scope names a personal or pool account,
 * which the host must admit before it reads that account's cookies.
 */
internal fun streamAccount(scope: String): String? = scope.takeUnless { it == ANONYMOUS_ACCOUNT_SCOPE }
internal fun pathId(id: String): String = id.also {
    require(it.isNotBlank() && it.length <= 256 && it.all { c -> c.isLetterOrDigit() || c in "_-" })
}

/** Per-request wall timeout, bounded streaming reads, no redirect of account headers/cookies. */
internal class ProviderHttp(client: HttpClient) {
    private val http = client.config { followRedirects = false; expectSuccess = false }
    suspend fun json(url: String, params: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(), body: JsonObject? = null,
        method: HttpMethod? = null): JsonElement {
        val raw = text(url, params, headers, body, method)
        return try { Json.parseToJsonElement(raw) } catch (failure: Exception) { throw ProviderProtocolFailure() }
    }
    suspend fun text(url: String, params: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(), body: JsonObject? = null,
        method: HttpMethod? = null): String {
        val reply = exchange(url, params, headers, body, method)
        if (reply.status !in 200..299) throw ProviderHttpFailure(reply.status)
        return reply.body
    }
    suspend fun exchange(url: String, params: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(), body: JsonObject? = null,
        method: HttpMethod? = null): ProviderReply = withTimeoutOrNull(12_000) {
        try {
            val requestMethod = method ?: if (body == null) HttpMethod.Get else HttpMethod.Post
            http.prepareRequest(url) {
                this.method = requestMethod
                header(HttpHeaders.Accept, "application/json")
                header(HttpHeaders.UserAgent, "ArchiveTune-Android")
                params.forEach { (key, value) -> parameter(key, value) }
                headers.forEach { (key, value) -> header(key, value) }
                if (body != null) { contentType(ContentType.Application.Json); setBody(body.toString()) }
            }.execute { response ->
                ProviderReply(response.status.value, response.readBoundedText(2 * 1024 * 1024),
                    response.headers[HttpHeaders.RetryAfter])
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: ProviderHttpFailure) { throw failure }
        catch (failure: Exception) { throw ProviderProtocolFailure() } // never propagate credential-bearing URLs/bodies
    } ?: throw ProviderRequestTimeout()
    /** Even a CDN ignoring Range cannot make this probe buffer the entire media file. */
    suspend fun probe(url: String): AudioProbe? = withTimeoutOrNull(12_000) {
        http.prepareGet(streamUrl(url)) {
            header("Range", "bytes=0-41")
            header("x-request-source", "muzo")
        }.execute { response ->
            if (response.status.value !in 200..299) return@execute null
            val type = response.headers[HttpHeaders.ContentType]?.substringBefore(';')?.lowercase().orEmpty()
            if ("json" in type || "html" in type) return@execute null
            val channel = response.bodyAsChannel()
            val prefix = ByteArray(42)
            var size = 0
            while (size < prefix.size) {
                val count = channel.readAvailable(prefix, size, prefix.size - size)
                if (count < 0) break
                if (count == 0) { yield(); continue }
                size += count
            }
            channel.cancel(null)
            val flac = size == 42 && prefix.copyOfRange(0, 4).decodeToString() == "fLaC" &&
                prefix[4].toInt() and 0x7f == 0 && prefix[7].toInt() == 34
            if (!flac && !type.startsWith("audio/") && type != "video/mp4") return@execute null
            var packed = 0L
            if (flac) for (i in 18..25) packed = (packed shl 8) or (prefix[i].toLong() and 255)
            val rate = if (flac) (packed ushr 44).toInt().takeIf { it > 0 } else null
            val depth = if (flac) ((packed ushr 36) and 31).toInt() + 1 else null
            val total = response.headers[HttpHeaders.ContentRange]?.substringAfter('/')?.toLongOrNull()
                ?: response.headers[HttpHeaders.ContentLength]?.toLongOrNull()?.takeIf { response.status.value != 206 }
            AudioProbe(if (flac) "audio/flac" else type, total?.takeIf { it > 0 }, rate, depth)
        }
    }
}
internal data class AudioProbe(val mime: String, val length: Long?, val sampleRate: Int?, val bitDepth: Int?)
internal class ProviderReply(val status: Int, val body: String, val retryAfter: String?)

data class ProviderTrackPage(val tracks: List<ProviderTrack>, val total: Long?, val hasMore: Boolean? = null)

/** Shared paging invariants, not a generic provider protocol or a second metadata owner. */
abstract class PagedAudioProvider(private val cursorScope: String) : TypedContentRepository, AudioProviderRepository {
    protected abstract suspend fun tracks(request: ContentRequest, offset: Int, limit: Int): ProviderTrackPage
    abstract suspend fun track(id: String): ProviderTrack
    final override suspend fun item(ref: ContentRef): ContentItem {
        require(ref.provider == provider.id && ref.kind == ContentKind.SONG)
        return track(ref.id).item
    }
    final override suspend fun page(request: ContentRequest, continuation: String?): ContentPage {
        require(request.provider == provider && request.section == null)
        require(request.filter in setOf(ContentSearchFilter.ALL, ContentSearchFilter.SONGS))
        val identity = listOf(cursorScope, request.toString()).joinToString("") { "${it.length}:$it" }
        val hash = ContentDigest.sha256(identity.encodeToByteArray())
        val offset = if (continuation == null) 0 else {
            val pieces = continuation.split(':')
            require(pieces.size == 3 && pieces[0] == "v1" && pieces[1] == hash) { "Foreign continuation" }
            pieces[2].toInt().also { require(it in 1..1_000_000) }
        }
        val page = tracks(request, offset, PAGE_SIZE)
        require(page.tracks.size <= PAGE_SIZE)
        if (request.surface in setOf(ContentSurface.ALBUM, ContentSurface.PLAYLIST) && page.total == null && page.hasMore == null) {
            throw moe.rukamori.archivetune.shared.domain.queue.IncompleteFinitePlaylistException("Collection total is unknown")
        }
        val end = offset + page.tracks.size
        require(page.total == null || page.total >= end) { "Invalid provider total" }
        val more = page.hasMore ?: page.total?.let { end < it } ?: (page.tracks.size == PAGE_SIZE)
        require(page.total == null || page.hasMore == null || page.hasMore == (end < page.total))
        require(!more || page.tracks.isNotEmpty()) { "Empty nonterminal provider page" }
        return ContentPage(page.tracks.mapIndexed { index, track ->
            ContentOccurrence("${provider.id}:$hash:${offset + index}", track.queueItem())
        }, if (more) "v1:$hash:$end" else null)
    }
    protected suspend fun match(request: AudioResolveRequest): ProviderTrack? {
        request.directIds[source]?.let { return track(it) }
        val query = (listOf(request.target.title) + request.target.artists.take(1)).joinToString(" ")
        val tracks = tracks(ContentRequest(provider, ContentSurface.SEARCH, text = query,
            filter = ContentSearchFilter.SONGS), 0, PAGE_SIZE).tracks
        val best = TrackMatching.best(request.target, tracks.map { it.candidate() }) ?: return null
        return tracks.first { it.item.ref.id == best.id }
    }
    protected fun direct(request: AudioResolveRequest, track: ProviderTrack, uri: String, mime: String,
        codecs: String, length: Long? = null, sampleRate: Int? = null, bitDepth: Int? = null,
        accountScope: String? = null): DirectStream =
        DirectStream(streamUrl(uri), mime, codecs, length, source.name, source,
            track.item.title, track.artists.joinToString(", "), track.album, track.item.durationMs,
            request.directIds[source] == track.item.ref.id, sampleRate, bitDepth, accountScope = accountScope,
            expiresAtEpochMs = StreamUrlExpiry.fromUrl(uri))
    private companion object { const val PAGE_SIZE = 50 }
}
