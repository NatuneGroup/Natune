package moe.rukamori.archivetune.shared.data.provider

import io.ktor.client.HttpClient
import kotlin.concurrent.Volatile
import kotlin.math.abs
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType
import moe.rukamori.archivetune.shared.domain.source.DirectStream
import moe.rukamori.archivetune.shared.domain.source.StreamUrlCache
import moe.rukamori.archivetune.shared.domain.source.StreamUrlExpiry

/**
 * A faithful port of the proxy-instance side of the retained `QobuzAudioProvider`: manual plus
 * pool-discovered squid.wtf/kennyy-style instances, each answering `$base/api/get-music` and
 * `$base/api/download-music`. Cooldowns, scoring, caches, health probes and pool discovery mirror
 * the original exactly. The signed-in Qobuz API (app_id + signed getFileUrl) is a separate backend
 * that stays in `KtorQobuzRepository`; per the original's `orderedBackends`, tokens run first and
 * this resolver supplies the proxy backends that follow.
 */
class QobuzInstanceResolver(
    private val client: HttpClient,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val poolEnabled: () -> Boolean = { false },
    private val sourceProviderUrl: () -> String = { "" },
    private val sourceProviderKey: () -> String = { "" },
) {
    /** Lookup metadata for a track, matching Tidal's Query shape. */
    data class Query(
        val mediaId: String,
        val title: String,
        val artists: List<String>,
        val album: String?,
        val durationMs: Long?,
        val directTrackId: String?,
    )

    /** A successful resolution: the mirror URL and the matched Qobuz metadata. */
    class Resolved(
        val stream: DirectStream,
        val trackId: String,
        val label: String,
    )

    enum class InstanceHealth { HEALTHY, PREVIEW_ONLY, UNREACHABLE }

    private class Instance(val label: String, val baseUrl: String)
    private class CachedSearch(val match: Match?, val expiresAt: Long)
    private class CachedStream(val stream: Resolved, val expiresAt: Long)
    private class Match(
        val id: String,
        val title: String,
        val artist: String?,
        val album: String?,
        val durationMs: Long?,
    )
    private class DownloadResult(val url: String, val mimeType: String, val codecs: String, val isPreview: Boolean)

    private val http = ProviderHttp(client)
    private val stateMutex = Mutex()

    private var manualInstances: List<Instance> = emptyList()
    private var poolInstances: List<Instance> = emptyList()
    private var instances: List<Instance> = emptyList()

    private val searchCache = mutableMapOf<String, CachedSearch>()
    private val streamCache = StreamUrlCache<String, CachedStream>()
    private val failureCache = StreamUrlCache<String, Long>()
    private val instanceCooldownUntilMs = mutableMapOf<String, Long>()

    private var discoveryCache: List<String> = emptyList()
    private var discoveryCacheExpiresAt = 0L

    /** The last Qobuz track id that resolved successfully, usable as a health-probe track. */
    @Volatile
    var lastResolvedTrackId: String? = null
        private set

    fun seedProbeTrack(trackId: String) {
        if (lastResolvedTrackId == null) lastResolvedTrackId = trackId
    }

    val configuredInstances: List<String>
        get() = instances.map { it.baseUrl }

    /** Replaces the active instance list. Invalid/duplicate URLs are dropped. Empty stays empty. */
    fun setInstances(baseUrls: List<String>) {
        manualInstances =
            baseUrls
                .mapNotNull(::normalizeInstanceUrl)
                .distinct()
                .map { normalized -> Instance(instanceLabel(normalized), normalized) }
        rebuildInstances()
    }

    private fun rebuildInstances() {
        instances = manualInstances + poolInstances
    }

    fun clearPoolDiscovery() {
        poolInstances = emptyList()
        rebuildInstances()
        discoveryCache = emptyList()
        discoveryCacheExpiresAt = 0L
    }

    fun hasInstances(): Boolean =
        manualInstances.isNotEmpty() ||
            (poolEnabled() && (poolInstances.isNotEmpty() || discoveryCacheExpiresAt == 0L) && poolFeedUrls().isNotEmpty())

    fun normalizeInstanceUrl(raw: String): String? {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) return null
        val withScheme =
            if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
        val host = runCatching { io.ktor.http.Url(withScheme).host }.getOrNull() ?: return null
        if (host.isBlank() || !host.contains('.')) return null
        return withScheme
    }

    private fun instanceLabel(baseUrl: String): String =
        runCatching { io.ktor.http.Url(baseUrl).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: baseUrl

    private suspend fun markInstanceHealthy(baseUrl: String) {
        stateMutex.withLock { instanceCooldownUntilMs.remove(baseUrl) }
    }

    private suspend fun markInstanceFailed(baseUrl: String, hardFailure: Boolean) {
        val cooldownMs = if (hardFailure) INSTANCE_HARD_COOLDOWN_MS else INSTANCE_SOFT_COOLDOWN_MS
        stateMutex.withLock { instanceCooldownUntilMs[baseUrl] = nowMs() + cooldownMs }
    }

    private fun isInstanceCoolingDown(baseUrl: String, now: Long): Boolean =
        (instanceCooldownUntilMs[baseUrl] ?: 0L) > now

    /** Lightweight reachability probe: GET `$base/` and return round-trip latency, or null. */
    suspend fun checkInstance(baseUrl: String): Long? {
        val normalized = normalizeInstanceUrl(baseUrl) ?: return null
        val started = nowMs()
        return try {
            val reply = http.exchange("$normalized/", headers = mapOf("Accept" to "application/json"))
            if (reply.status !in 200..299) null else nowMs() - started
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Deep health probe: an actual search + download for [probeTrackId] (or a canned query when
     * blank), reporting PREVIEW_ONLY when the backing account can only serve samples.
     */
    suspend fun verifyInstance(
        baseUrl: String,
        probeTrackId: String?,
        formatId: Int,
    ): InstanceHealth {
        val normalized = normalizeInstanceUrl(baseUrl) ?: return InstanceHealth.UNREACHABLE
        val trackId =
            probeTrackId
                ?.trim()
                .orEmpty()
                .ifBlank {
                    runCatching { searchTrackId(normalized, "adele hello") }.getOrNull().orEmpty()
                }
        if (trackId.isEmpty()) return InstanceHealth.UNREACHABLE
        return try {
            when (val result = fetchDownload(normalized, trackId, formatId)) {
                null -> InstanceHealth.UNREACHABLE
                else -> if (result.isPreview) InstanceHealth.PREVIEW_ONLY else InstanceHealth.HEALTHY
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            InstanceHealth.UNREACHABLE
        }
    }

    /** Feeds an external health result into the runtime cooldown map used by the resolver. */
    suspend fun applyHealthResult(baseUrl: String, healthy: Boolean) {
        val normalized = normalizeInstanceUrl(baseUrl) ?: return
        if (healthy) markInstanceHealthy(normalized) else markInstanceFailed(normalized, hardFailure = true)
    }

    /** Any track id for a canned query — proves the API answers, not the account entitlement. */
    private suspend fun searchTrackId(baseUrl: String, searchQuery: String): String? {
        val items = searchItems(baseUrl, searchQuery) ?: return null
        for (index in 0 until items.size) {
            items[index].jsonObject.trackId()?.let { return it }
        }
        return null
    }

    /**
     * Resolves [query] through the configured proxy instances. When [Query.directTrackId] is set,
     * the title/artist search is skipped and the exact track is downloaded. Returns null when no
     * instance can serve a full track.
     */
    suspend fun resolve(query: Query, formatId: Int): Resolved? {
        val available = instances.filter { !isInstanceCoolingDown(it.baseUrl, nowMs()) }
        if (available.isEmpty()) {
            if (instances.isEmpty()) return null
            // All cooling down: a rate-limited miss should not burn a search round trip.
            if (failureCache[query.streamCacheKey(formatId)]?.let { it > nowMs() } == true) return null
        }
        val cacheKey = query.streamCacheKey(formatId)
        stateMutex.withLock {
            streamCache[cacheKey]?.let { cached ->
                if (cached.expiresAt > nowMs()) return cached.stream
                streamCache.discard(cacheKey, cached)
            }
            failureCache[cacheKey]?.let { failedUntil -> if (failedUntil > nowMs()) return null }
        }
        val streamGeneration = streamCache.generation
        val failureGeneration = failureCache.generation

        for (instance in available) {
            // When directTrackId is set (a specific Qobuz search result), skip the title/artist
            // search entirely and download the exact track.
            val match =
                if (query.directTrackId != null) {
                    Match(query.directTrackId, query.title, query.artists.firstOrNull(), query.album, query.durationMs)
                } else {
                    runCatching { resolveTrackId(instance, query) }.getOrNull()
                }
            if (match == null) {
                markInstanceFailed(instance.baseUrl, hardFailure = false)
                continue
            }
            val trackId = match.id
            val download =
                try {
                    fetchDownload(instance.baseUrl, trackId, formatId)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (failure: Exception) {
                    markInstanceFailed(instance.baseUrl, isHardTransportError(failure))
                    continue
                }
            if (download == null) {
                markInstanceFailed(instance.baseUrl, hardFailure = false)
                continue
            }
            if (download.isPreview) {
                // Unsubscribed/expired backing account: skip this instance for a while, try the next.
                markInstanceFailed(instance.baseUrl, hardFailure = false)
                continue
            }
            markInstanceHealthy(instance.baseUrl)
            lastResolvedTrackId = trackId
            val label = "Qobuz ${qualityLabel(formatId)}"
            val resolved =
                Resolved(
                    stream = DirectStream(
                        uri = download.url,
                        mimeType = download.mimeType,
                        codecs = download.codecs,
                        contentLength = null,
                        label = label,
                        source = AudioSourceType.QOBUZ,
                        matchedTitle = match.title,
                        matchedArtist = match.artist,
                        matchedAlbum = match.album,
                        matchedDurationMs = match.durationMs,
                        trustedDirectId = query.directTrackId == trackId,
                        expiresAtEpochMs = StreamUrlExpiry.fromUrl(download.url),
                    ),
                    trackId = trackId,
                    label = label,
                )
            stateMutex.withLock {
                if (!streamCache.putIfCurrent(
                        cacheKey,
                        CachedStream(resolved, StreamUrlExpiry.cacheDeadline(nowMs(), STREAM_CACHE_MS, resolved.stream.expiresAtEpochMs)),
                        streamGeneration,
                    )
                ) return null
            }
            return resolved
        }

        stateMutex.withLock { failureCache.putIfCurrent(cacheKey, nowMs() + FAILURE_CACHE_MS, failureGeneration) }
        return null
    }

    /** Drops any cached stream/failure entry for [query], forcing the next resolve to hit the network. */
    suspend fun invalidate(query: Query, formatId: Int) {
        val key = query.streamCacheKey(formatId)
        stateMutex.withLock {
            streamCache.remove(key)
            failureCache.remove(key)
        }
    }

    suspend fun invalidate(mediaId: String) = stateMutex.withLock {
        streamCache.invalidateWhere { it.startsWith("$mediaId::") }
        failureCache.invalidateWhere { it.startsWith("$mediaId::") }
    }

    private fun Query.streamCacheKey(formatId: Int): String =
        "$mediaId::${directTrackId.orEmpty()}::${cacheKey()}:$formatId"

    private fun qualityLabel(formatId: Int): String =
        when (formatId) {
            6 -> "FLAC"
            7 -> "HI_RES"
            27 -> "MAX"
            else -> "FLAC"
        }

    // region discovery
    private fun poolFeedUrls(): List<String> =
        ArchivePoolEndpoints.candidates(sourceProviderUrl()).map { "$it/api/discovery/qobuz" }

    /**
     * Best-effort discovery of community Qobuz proxy instances from the Source Pool website.
     * Returns newly discovered valid base URLs (may be empty). Never throws.
     */
    suspend fun discoverInstances(): List<String> {
        if (!poolEnabled()) return emptyList()
        val now = nowMs()
        if (now < discoveryCacheExpiresAt) return discoveryCache
        val discovered = LinkedHashSet<String>()
        val result =
            try {
                val headers =
                    sourceProviderKey().takeIf { it.isNotBlank() }
                        ?.let { mapOf("Authorization" to "Bearer $it") }
                        ?: emptyMap()
                var root: JsonObject? = null
                for (url in poolFeedUrls()) {
                    val reply =
                        try {
                            http.exchange(url, headers = headers)
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (_: Exception) {
                            continue
                        }
                    if (ArchivePoolEndpoints.isFailoverStatus(reply.status)) continue
                    ArchivePoolEndpoints.remember(url.substringBefore("/api/"))
                    if (reply.status == 200) {
                        // The status is classified before decoding: a 200 with an unusable
                        // payload is definitive (a payload failure), not a failover.
                        root = runCatching { Json.parseToJsonElement(reply.body).jsonObject }.getOrNull()
                    }
                    break
                }
                if (!poolEnabled() || root == null) {
                    emptyList()
                } else {
                    for (key in listOf("streaming", "instances", "api")) {
                        val arr = root[key] as? JsonArray ?: continue
                        for (item in arr) {
                            when (item) {
                                is JsonObject ->
                                    (item["url"] ?: item["baseUrl"])
                                        ?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                                        ?.let { normalizeInstanceUrl(it)?.let(discovered::add) }
                                else ->
                                    item.jsonPrimitive.content.takeIf { it.isNotBlank() && it != "null" }
                                        ?.let { normalizeInstanceUrl(it)?.let(discovered::add) }
                            }
                        }
                    }
                    discovered.toList()
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                emptyList()
            }
        if (result.isNotEmpty()) {
            discoveryCache = result
            poolInstances = result.map { normalized -> Instance(instanceLabel(normalized), normalized) }
            rebuildInstances()
        }
        // The quiet period covers empty and failed answers too, as the Deezer resolver's does.
        discoveryCacheExpiresAt = nowMs() + DISCOVERY_CACHE_MS
        return result
    }
    // endregion

    // region per-instance requests
    private suspend fun searchItems(baseUrl: String, searchQuery: String): JsonArray? {
        val reply =
            try {
                http.json(
                    "$baseUrl/api/get-music",
                    mapOf("q" to searchQuery, "offset" to "0"),
                    mapOf("Accept" to "application/json"),
                )
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                return null
            }
        val root = runCatching { reply.jsonObject }.getOrNull() ?: return null
        return root.findTrackItems()
    }

    /**
     * Calls download-music and extracts the direct URL + a best-effort preview flag. Non-2xx
     * answers and transport failures propagate so the caller can apply the soft/hard cooldown
     * split (like the original, which classifies connect-class errors as hard).
     */
    private suspend fun fetchDownload(baseUrl: String, trackId: String, formatId: Int): DownloadResult? {
        val reply =
            http.json(
                "$baseUrl/api/download-music",
                mapOf("track_id" to trackId, "quality" to formatId.toString()),
                mapOf("Accept" to "application/json"),
            )
        val root = runCatching { reply.jsonObject }.getOrNull() ?: return null
        val streamUrl = root.findStreamUrl() ?: return null
        val isPreview = root.looksLikePreview()
        val mime =
            when {
                streamUrl.contains(".flac", ignoreCase = true) -> AUDIO_FLAC_MIME_TYPE
                streamUrl.contains(".mp3", ignoreCase = true) -> "audio/mpeg"
                else -> AUDIO_FLAC_MIME_TYPE
            }
        val codecs = if (mime == AUDIO_FLAC_MIME_TYPE) "flac" else "mp4a.40.2"
        return DownloadResult(url = streamUrl, mimeType = mime, codecs = codecs, isPreview = isPreview)
    }
    // endregion

    // region matching (mirrors Tidal's heuristics)
    private suspend fun resolveTrackId(instance: Instance, query: Query): Match? {
        val now = nowMs()
        val key = instance.baseUrl + "|" + query.cacheKey()
        stateMutex.withLock {
            searchCache[key]?.let { cached ->
                if (cached.expiresAt > now) return cached.match
                searchCache.remove(key)
            }
        }
        val searchQuery =
            listOf(query.artists.firstOrNull()?.searchArtist().orEmpty(), query.title.searchTitle())
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .ifBlank { query.title }
        val match = bestMatch(instance, searchQuery, query)
        stateMutex.withLock { searchCache[key] = CachedSearch(match, now + SEARCH_CACHE_MS) }
        return match
    }

    /** Runs search and scores results against [query], returning the best match above threshold. */
    private suspend fun bestMatch(instance: Instance, searchQuery: String, query: Query): Match? {
        val items = searchItems(instance.baseUrl, searchQuery) ?: return null
        val wantedTitle = query.title.titleMatchNormalized()
        val wantedArtists = query.artists.map { it.normalized() }.filter { it.isNotBlank() }
        var bestId: String? = null
        var bestTitle = ""
        var bestArtist: String? = null
        var bestAlbum: String? = null
        var bestDurationMs: Long? = null
        var bestScore = Int.MIN_VALUE
        for (index in 0 until items.size) {
            val item = items[index].jsonObject
            val id = item.trackId() ?: continue
            val rawTitle = item.stringOrNull("title") ?: continue
            val candidateTitle = rawTitle.titleMatchNormalized()
            val candidateArtist =
                item.obj("performer")?.stringOrNull("name")
                    ?: item.stringOrNull("artist")
                    ?: item.obj("album")?.obj("artist")?.stringOrNull("name")
                    ?: ""
            val candidateDurationMs = item.longOrNull("duration")?.times(1000L)
            val candidateAlbum = item.obj("album")?.stringOrNull("title")
            val score =
                scoreMatch(
                    wantedTitle = wantedTitle,
                    wantedArtists = wantedArtists,
                    candidateTitle = candidateTitle,
                    candidateArtist = candidateArtist.normalized(),
                    wantedDurationMs = query.durationMs,
                    candidateDurationMs = candidateDurationMs,
                )
            if (score > bestScore) {
                bestScore = score
                bestId = id
                bestTitle = rawTitle
                bestArtist = candidateArtist.takeIf { it.isNotBlank() }
                bestAlbum = candidateAlbum
                bestDurationMs = candidateDurationMs
            }
        }
        val id = bestId
        return if (bestScore >= MIN_MATCH_SCORE && id != null) {
            Match(id, bestTitle, bestArtist, bestAlbum, bestDurationMs)
        } else {
            null
        }
    }

    private fun scoreMatch(
        wantedTitle: String,
        wantedArtists: List<String>,
        candidateTitle: String,
        candidateArtist: String,
        wantedDurationMs: Long?,
        candidateDurationMs: Long?,
    ): Int {
        if (wantedTitle.isBlank() || candidateTitle.isBlank()) return Int.MIN_VALUE
        if (hasVersionMismatch(" $wantedTitle ", " $candidateTitle ")) return Int.MIN_VALUE
        val titleOverlap = tokenOverlap(significantTokens(wantedTitle), significantTokens(candidateTitle))
        if (titleOverlap < 0.5) return Int.MIN_VALUE
        var score = (titleOverlap * 100).toInt()
        if (wantedArtists.isNotEmpty() && candidateArtist.isNotBlank()) {
            val artistOverlap =
                wantedArtists.maxOf { tokenOverlap(significantTokens(it), significantTokens(candidateArtist)) }
            score += (artistOverlap * 60).toInt()
        }
        if (wantedDurationMs != null && candidateDurationMs != null) {
            if (durationMatches(wantedDurationMs, candidateDurationMs)) score += 30 else score -= 40
        }
        return score
    }

    private fun significantTokens(value: String): Set<String> =
        value.split(' ').map { it.trim() }.filter { it.length >= 2 && it !in STOP_WORDS }.toSet()

    private fun tokenOverlap(wanted: Set<String>, candidate: Set<String>): Double {
        if (wanted.isEmpty() || candidate.isEmpty()) return 0.0
        val shared = wanted.intersect(candidate).size
        return shared.toDouble() / wanted.size.coerceAtLeast(candidate.size).toDouble()
    }

    private fun durationMatches(wantedDurationMs: Long?, candidateDurationMs: Long?): Boolean {
        if (wantedDurationMs == null || candidateDurationMs == null) return true
        return abs(wantedDurationMs - candidateDurationMs) <= 45_000L
    }

    private fun hasVersionMismatch(wanted: String, candidate: String): Boolean =
        VERSION_TOKENS.any { token -> wanted.contains(" $token ") != candidate.contains(" $token ") }

    private fun Query.cacheKey(): String =
        listOf(
            title.normalized(),
            artists.joinToString(",") { it.normalized() },
            album.normalized(),
            durationMs?.div(1000L)?.toString().orEmpty(),
        ).joinToString("::")

    private fun String?.normalized(): String =
        this
            ?.lowercase()
            ?.replace(DIACRITIC_REGEX, "")
            ?.replace(NON_ALPHANUMERIC_REGEX, " ")
            ?.trim()
            .orEmpty()

    private fun String.titleMatchNormalized(): String =
        normalized()
            .replace(FEATURED_ARTIST_TITLE_SUFFIX_REGEX, "")
            .replace(EDITION_NOISE_WORD_REGEX, " ")
            .replace(WHITESPACE_REGEX, " ")
            .trim()

    private fun String.searchTitle(): String =
        trim()
            .replace(FEATURED_ARTIST_BRACKET_REGEX, "")
            .replace(EDITION_SUFFIX_REGEX, "")
            .replace(WHITESPACE_REGEX, " ")
            .trim()

    private fun String.searchArtist(): String =
        trim().substringBefore(',').replace(WHITESPACE_REGEX, " ").trim()
    // endregion

    // region JSON helpers
    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }
    private fun JsonObject.longOrNull(key: String): Long? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()?.takeIf { it > 0L }

    private fun JsonObject.findTrackItems(): JsonArray? {
        val data = obj("data") ?: this
        data.obj("tracks")?.array("items")?.let { return it }
        data.array("items")?.let { return it }
        data.array("tracks")?.let { return it }
        array("items")?.let { return it }
        array("tracks")?.let { return it }
        return null
    }

    private fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

    private fun JsonObject.findStreamUrl(): String? {
        for (key in listOf("url", "downloadUrl", "download_url", "stream_url", "streamUrl", "link")) {
            stringOrNull(key)?.takeIf { it.startsWith("http") }?.let { return it }
        }
        obj("data")?.findStreamUrl()?.let { return it }
        for ((_, value) in this) {
            when (value) {
                is JsonObject -> value.findStreamUrl()?.let { return it }
                is kotlinx.serialization.json.JsonPrimitive ->
                    value.content.let {
                        if (it.startsWith("http") && (it.contains(".flac", true) || it.contains(".mp3", true))) return it
                    }
                else -> Unit
            }
        }
        return null
    }

    /** Best-effort preview/sample detection from the download payload. */
    private fun JsonObject.looksLikePreview(): Boolean {
        val data = obj("data") ?: this
        if (data.flag("sample") == true) return true
        if (data.flag("preview") == true) return true
        // Some proxies signal an unsubscribed account via an explicit flag.
        data.stringOrNull("type")?.let { if (it.equals("preview", true) || it.equals("sample", true)) return true }
        return false
    }

    private fun JsonObject.flag(key: String): Boolean? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.let {
            when (it.content.lowercase()) {
                "true" -> true
                "false" -> false
                else -> null
            }
        }

    private fun JsonObject.trackId(): String? =
        stringOrNull("id") ?: longOrNull("id")?.toString() ?: stringOrNull("track_id")
    // endregion

    private fun isHardTransportError(error: Throwable): Boolean {
        // ProviderHttp collapses transport and protocol errors: a server that answered with an
        // HTTP error or junk payload (ProviderHttpFailure/ProviderProtocolFailure) is soft, while
        // timeouts and remaining throwables — the connect-class the original marks hard — are hard.
        if (error is ProviderHttpFailure || error is ProviderProtocolFailure) return false
        return true
    }

    private companion object {
        private const val MIN_MATCH_SCORE = 60
        private const val SEARCH_LIMIT = 10
        private const val SEARCH_CACHE_MS = 10 * 60 * 1000L
        private const val STREAM_CACHE_MS = 30 * 60 * 1000L
        private const val FAILURE_CACHE_MS = 10 * 60 * 1000L
        private const val INSTANCE_SOFT_COOLDOWN_MS = 60_000L
        private const val INSTANCE_HARD_COOLDOWN_MS = 600_000L
        private const val DISCOVERY_CACHE_MS = 30 * 60 * 1000L
        private const val AUDIO_FLAC_MIME_TYPE = "audio/flac"
        private const val HEALTH_PROBE_TRACK_ID = "5966783"
        private const val HEALTH_PROBE_FORMAT_ID = 5
        private val STOP_WORDS = setOf("the", "a", "an", "of", "and", "feat", "ft", "featuring", "with")
        private val VERSION_TOKENS =
            setOf("remix", "acoustic", "live", "instrumental", "demo", "edit", "mix", "version")
        private val DIACRITIC_REGEX = Regex("\\p{Mn}+")
        private val NON_ALPHANUMERIC_REGEX = Regex("[^\\p{L}\\p{N}]+")
        private val WHITESPACE_REGEX = Regex("\\s+")
        private val FEATURED_ARTIST_TITLE_SUFFIX_REGEX = Regex("""\b(feat|ft|featuring)\b.*$""")
        private val EDITION_NOISE_WORD_REGEX =
            Regex("""\b(explicit|clean|remaster|remastered|version|audio|official)\b""")
        private val FEATURED_ARTIST_BRACKET_REGEX =
            Regex("""\s*[\[(]\s*(feat\.?|ft\.?|featuring)\b.*?[\])]""", RegexOption.IGNORE_CASE)
        private val EDITION_SUFFIX_REGEX =
            Regex("""\s*-\s*(explicit|clean|remaster(?:ed)?|audio|official)\b.*$""", RegexOption.IGNORE_CASE)
    }
}
