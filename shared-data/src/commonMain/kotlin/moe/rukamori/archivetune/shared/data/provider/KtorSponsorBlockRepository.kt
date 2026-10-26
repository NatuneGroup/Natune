package moe.rukamori.archivetune.shared.data.provider

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import moe.rukamori.archivetune.shared.domain.platform.ContentDigest

data class SponsorBlockConfig(val enabled: Boolean, val categories: Set<String> = setOf("music_offtopic"),
    val origin: String = "https://sponsor.ajay.app") {
    init {
        val url = Url(origin)
        require(url.protocol == URLProtocol.HTTPS && url.host.isNotBlank() && url.user.isNullOrEmpty() &&
            url.password.isNullOrEmpty() && url.parameters.isEmpty() && url.fragment.isEmpty() &&
            (url.encodedPath.isEmpty() || url.encodedPath == "/"))
    }
}
data class SkipSegment(val category: String, val startMs: Long, val endMs: Long)

/** Privacy-preserving hash-prefix protocol; feature failures never block playback, cancellation does. */
class KtorSponsorBlockRepository(private val client: HttpClient) {
    private data class Key(val id: String, val origin: String, val categories: Set<String>)
    private val mutex = Mutex()
    private val cache = linkedMapOf<Key, List<SkipSegment>>()

    suspend fun segments(videoId: String, config: SponsorBlockConfig): List<SkipSegment> {
        if (!config.enabled || config.categories.isEmpty() || !YOUTUBE_ID.matches(videoId)) return emptyList()
        val key = Key(videoId, config.origin, config.categories.toSet())
        mutex.withLock { cache.remove(key)?.let { cache[key] = it; return it } }
        val result = try { withTimeoutOrNull(12_000) { fetch(videoId, config) } ?: emptyList() }
        catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) { emptyList() }
        mutex.withLock {
            cache[key] = result
            while (cache.size > 64) cache.remove(cache.keys.first())
        }
        return result
    }

    private suspend fun fetch(videoId: String, config: SponsorBlockConfig): List<SkipSegment> {
        val prefix = ContentDigest.sha256(videoId.encodeToByteArray()).take(4)
        val raw = client.prepareGet("${config.origin.trimEnd('/')}/api/skipSegments/$prefix") {
            config.categories.sorted().forEach { parameter("category", it) }
        }.execute { response ->
            if (response.status == HttpStatusCode.NotFound || response.status.value !in 200..299) null
            else response.readBoundedText(2_000_000)
        } ?: return emptyList()
        val payload = Json.parseToJsonElement(raw).jsonArray
        val segments = payload.firstOrNull { it.jsonObject["videoID"]?.jsonPrimitive?.content == videoId }
            ?.jsonObject?.get("segments")?.jsonArray.orEmpty()
        val parsed = segments.mapNotNull {
            val obj = it.jsonObject
            val category = obj["category"]?.jsonPrimitive?.content ?: return@mapNotNull null
            if (category !in config.categories || (obj["actionType"]?.jsonPrimitive?.content ?: "skip") != "skip")
                return@mapNotNull null
            val interval = obj["segment"]?.jsonArray ?: return@mapNotNull null
            if (interval.size != 2) return@mapNotNull null
            val start = interval[0].jsonPrimitive.doubleOrNull ?: return@mapNotNull null
            val end = interval[1].jsonPrimitive.doubleOrNull ?: return@mapNotNull null
            if (!start.isFinite() || !end.isFinite() || start < 0 || end <= start || end * 1000 > Long.MAX_VALUE)
                return@mapNotNull null
            SkipSegment(category, (start * 1000).toLong(), (end * 1000).toLong())
        }.sortedBy { it.startMs }
        return parsed.fold(mutableListOf()) { merged: MutableList<SkipSegment>, segment ->
            val last = merged.lastOrNull()
            if (last != null && segment.startMs <= last.endMs)
                merged[merged.lastIndex] = last.copy(endMs = maxOf(last.endMs, segment.endMs))
            else merged += segment
            merged
        }
    }
    private companion object { val YOUTUBE_ID = Regex("^[A-Za-z0-9_-]{11}$") }
}
