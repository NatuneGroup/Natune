package moe.rukamori.archivetune.shared.data.provider

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareRequest
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.Url
import kotlin.io.encoding.Base64
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.contentOrNull
import moe.rukamori.archivetune.shared.data.stream.dash.TidalDashManifest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.PreparedAudio
import moe.rukamori.archivetune.shared.domain.repository.ProviderTrack
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType
import moe.rukamori.archivetune.shared.domain.source.DirectStream
import moe.rukamori.archivetune.shared.domain.source.TrackMatching

/**
 * A faithful port of the retained Android `TidalAudioProvider` shared logic: instance list handling
 * (no bundled hosts), pool discovery, health probes, cooldowns, the up-to-8-endpoint race with AAC
 * deferral and the 429 rate-limit latch, the `/trackManifests/` -> `/track/` dialect fallback,
 * PREVIEW rejection, candidate scoring and the song.link fallback.
 *
 * The one deliberate adaptation is output shape: where the retained code wrote DASH manifests or
 * remuxed FLAC into cache files and handed ExoPlayer `tidal-dash://` or `file://` URIs, this port
 * emits [PreparedAudio.TidalManifest] carrying the manifest text. The KMP native layer
 * ([TidalDashManifest] / TidalDashPreparation / TidalDashSource) performs the retained
 * progressive-remux or concatenation route, so resolution logic stays identical while file work
 * stays on the host.
 */
class TidalInstanceResolver(
    private val client: HttpClient,
    private val healthClient: HttpClient = client,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val poolEnabled: () -> Boolean = { false },
    private val sourceProviderUrl: () -> String = { "" },
    private val sourceProviderKey: () -> String = { "" },
    /**
     * Carries the best-effort cancels of abandoned queued playback. It must outlive the attempt that
     * abandoned the ticket, because the race does not wait for a cancel before returning a winner.
     */
    private val cancelScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    data class Query(
        val mediaId: String,
        val title: String,
        val artists: List<String>,
        val album: String?,
        val isrc: String?,
        val durationMs: Long?,
    )

    /**
     * The resolved stream plus the expiry and downgrade metadata the original caches beside the
     * DirectStream/Resolved pair. `prepared` is what the repository hands to the resolver chain.
     */
    data class Resolved(
        val prepared: PreparedAudio,
        val trackId: String,
        val label: String,
        val expiresAtMs: Long,
        val losslessDowngradedBitrateKbps: Int? = null,
        val isLiveManifest: Boolean = false,
    )

    data class CandidateMetadata(
        val trackId: String,
        val title: String,
        val artist: String,
        val album: String?,
        val isrc: String?,
        val durationMs: Long?,
        val thumbnailUrl: String? = null,
    )

    open class TidalAudioResolutionException(message: String, cause: Throwable? = null) : Exception(message, cause)

    class TidalRateLimitedException(
        val retryAfterMs: Long,
    ) : TidalAudioResolutionException("TIDAL FLAC resolver is rate limited; cooling down for ${retryAfterMs / 1000L}s")

    /** Instance answered 404 for `/trackManifests/`; caller retries the same host on `/track/`. */
    private class TidalDialectUnsupportedException(message: String) : TidalAudioResolutionException(message)

    private data class CachedTrack(val track: MatchedTrack, val expiresAtMs: Long)
    private data class CachedFailure(val message: String, val expiresAtMs: Long)
    private data class CachedSearch(val results: JsonArray, val expiresAtMs: Long)

    internal data class MatchedTrack(
        val trackId: String,
        val title: String,
        val artistNames: List<String>,
        val album: String?,
        val isrc: String?,
        val durationMs: Long?,
        val audioQuality: String?,
        val mediaTags: Set<String>,
        val audioModes: Set<String>,
        val sampleRate: Int?,
        val albumId: String? = null,
        val albumCoverId: String? = null,
        val rawJson: String = "",
    ) {
        fun supportsLossless(): Boolean =
            qualitySignals().any { it.contains("LOSSLESS", ignoreCase = true) || it.contains("FLAC", ignoreCase = true) }

        fun supportsHiResLossless(): Boolean =
            qualitySignals().any {
                it.contains("HI_RES", ignoreCase = true) ||
                    it.contains("HIRES", ignoreCase = true) ||
                    it.contains("MAX", ignoreCase = true)
            }

        fun supportsAtmos(): Boolean =
            qualitySignals().any {
                it.contains("DOLBY_ATMOS", ignoreCase = true) ||
                    it.contains("DOLBY ATMOS", ignoreCase = true) ||
                    it.contains("EAC3_JOC", ignoreCase = true) ||
                    it.contains("ATMOS", ignoreCase = true)
            }

        fun losslessRank(): Int =
            when {
                supportsHiResLossless() -> 3
                supportsLossless() -> 2
                supportsAtmos() -> 1
                else -> 0
            }

        fun qualityScoreBoost(): Int =
            when {
                supportsHiResLossless() -> 70
                supportsLossless() -> 55
                supportsAtmos() -> 20
                else -> 0
            }

        private fun qualitySignals(): List<String> =
            buildList {
                audioQuality?.takeIf { it.isNotBlank() }?.let(::add)
                addAll(mediaTags)
                addAll(audioModes)
            }
    }

    private data class ParsedManifest(
        val url: String,
        val mimeType: String,
        val codecs: String,
        val sampleRate: Int?,
        val bitrate: Int?,
        val expiryUrl: String?,
        val isDash: Boolean,
        val segmentUrls: List<String> = emptyList(),
        val manifestText: String? = null,
    )

    private data class StreamMetadata(val mimeType: String?, val contentLength: Long?)
    private data class PlaybackStreamInfo(val mimeType: String, val codecs: String, val contentLength: Long?)
    internal class TidalDownloadEndpoint(val name: String, baseUrl: String) {
        val baseUrl: String = baseUrl.trimEnd('/')
    }
    private data class ScoredTrack(val track: MatchedTrack, val score: Int)

    /** Mirrors the retained enum: AAC_320 / FLAC / HI_RES_LOSSLESS. */
    enum class TidalAudioQuality { AAC_320, FLAC, HI_RES_LOSSLESS }

    enum class InstanceHealth { HEALTHY, PREVIEW_ONLY, UNREACHABLE }

    // region instance list / discovery / health
    private val DEFAULT_DOWNLOAD_API_ENDPOINTS = emptyList<TidalDownloadEndpoint>()

    private val INSTANCE_DISCOVERY_SOURCES: List<String>
        get() =
            sourceProviderUrl()
                .trim()
                .trimEnd('/')
                .takeIf { it.isNotEmpty() }
                ?.let { listOf("$it/api/discovery/tidal") }
                ?: emptyList()

    @kotlin.concurrent.Volatile
    private var customEndpoints: List<TidalDownloadEndpoint> = emptyList()

    @kotlin.concurrent.Volatile
    private var poolDiscoveredInstanceUrls: Set<String> = emptySet()

    private val activeEndpoints: List<TidalDownloadEndpoint>
        get() = customEndpoints

    val defaultInstanceUrls: List<String>
        get() = DEFAULT_DOWNLOAD_API_ENDPOINTS.map { it.baseUrl }

    val activeInstanceUrls: List<String>
        get() = activeEndpoints.map { it.baseUrl }

    fun setInstances(baseUrls: List<String>) {
        val seen = LinkedHashSet<String>()
        customEndpoints =
            baseUrls.mapNotNull { raw ->
                val normalized = normalizeInstanceUrl(raw) ?: return@mapNotNull null
                if (!seen.add(normalized)) return@mapNotNull null
                TidalDownloadEndpoint(instanceLabel(normalized), normalized)
            }
    }

    fun clearPoolDiscovery() {
        val discovered = poolDiscoveredInstanceUrls
        if (discovered.isNotEmpty()) {
            customEndpoints = customEndpoints.filterNot { it.baseUrl in discovered }
            poolDiscoveredInstanceUrls = emptySet()
        }
    }

    /** Normalizes an instance URL to `scheme://host[:port]` form, or null when it is not valid. */
    fun normalizeInstanceUrl(raw: String): String? {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) return null
        val withScheme =
            if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
        val url = runCatching { Url(withScheme) }.getOrNull() ?: return null
        if (url.host.isBlank() || !url.host.contains('.')) return null
        return withScheme
    }

    private fun instanceLabel(baseUrl: String): String = runCatching { Url(baseUrl).host }.getOrNull() ?: baseUrl

    /** Lightweight reachability probe: returns latency when the instance answers 2xx-4xx, else null. */
    suspend fun checkInstance(baseUrl: String): Long? {
        val normalized = normalizeInstanceUrl(baseUrl) ?: return null
        return runCatching {
            val start = nowMs()
            healthGet("$normalized/", HEALTH_TIMEOUT_MS).let { reply ->
                if (reply.status in 200..499) nowMs() - start else null
            }
        }.getOrNull()
    }

    @kotlin.concurrent.Volatile
    var lastResolvedTrackId: String? = null
        private set

    fun seedProbeTrack(trackId: String) {
        if (lastResolvedTrackId.isNullOrBlank() && trackId.isNotBlank()) {
            lastResolvedTrackId = trackId
        }
    }

    suspend fun findHealthProbeTrackId(): String? {
        if (activeEndpoints.isEmpty()) return null
        return runCatching {
            val items = searchTracks("adele hello") ?: return@runCatching null
            items
                .firstNotNullOfOrNull { it.jsonObjectOrNull?.toMatchedTrack()?.trackId }
                ?.also { lastResolvedTrackId = it }
        }.getOrNull()
    }

    /**
     * Deep health probe: resolves a probe track manifest and reports whether the instance serves a
     * FULL track or only a PREVIEW (unsubscribed backing account).
     */
    suspend fun verifyInstance(
        baseUrl: String,
        probeTrackId: String?,
        quality: TidalAudioQuality = TidalAudioQuality.FLAC,
    ): InstanceHealth {
        val normalized = normalizeInstanceUrl(baseUrl) ?: return InstanceHealth.UNREACHABLE
        val trackId = probeTrackId?.trim().orEmpty().ifBlank { findHealthProbeTrackId().orEmpty() }
        if (trackId.isEmpty()) return InstanceHealth.UNREACHABLE
        val qualityString =
            when (quality) {
                TidalAudioQuality.HI_RES_LOSSLESS -> "HI_RES_LOSSLESS"
                TidalAudioQuality.FLAC -> "LOSSLESS"
                TidalAudioQuality.AAC_320 -> "HIGH"
            }
        val manifestFormats =
            qualityString.tidalManifestFormats() ?: return probeTrackDialect(normalized, trackId, qualityString)
        val viaManifests = probeTrackManifestsDialect(normalized, trackId, manifestFormats)
        if (viaManifests != InstanceHealth.UNREACHABLE) return viaManifests
        return probeTrackDialect(normalized, trackId, qualityString)
    }

    /** Probes `/trackManifests/` (HiFi-RestAPI 3.x / Monochrome). */
    private suspend fun probeTrackManifestsDialect(
        normalized: String,
        trackId: String,
        manifestFormats: List<String>,
    ): InstanceHealth {
        val params =
            buildList {
                add("id" to trackId)
                manifestFormats.forEach { add("formats" to it) }
                add("adaptive" to "false")
                add("manifestType" to "MPEG_DASH")
                add("uriScheme" to "HTTPS")
                add("usage" to "PLAYBACK")
            }
        return runCatching {
            val reply = healthGet("$normalized/trackManifests/", HEALTH_TIMEOUT_MS, params)
            if (reply.status !in 200..299 || reply.body.isBlank()) return@runCatching InstanceHealth.UNREACHABLE
            val attributes =
                Json.parseToJsonElement(reply.body).jsonObjectOrNull
                    ?.obj("data")?.obj("data")?.obj("attributes")
                    ?: return@runCatching InstanceHealth.UNREACHABLE
            val presentation = attributes.stringOrNull("trackPresentation") ?: attributes.stringOrNull("assetPresentation")
            if (presentation.equals("PREVIEW", ignoreCase = true)) InstanceHealth.PREVIEW_ONLY else InstanceHealth.HEALTHY
        }.getOrDefault(InstanceHealth.UNREACHABLE)
    }

    /** Probes `/track/?id=&quality=` (HiFi-RestAPI 2.x), which returns the base64 manifest inline. */
    private suspend fun probeTrackDialect(
        normalized: String,
        trackId: String,
        qualityString: String,
    ): InstanceHealth {
        val params = listOf("id" to trackId, "quality" to qualityString)
        return runCatching {
            val reply = healthGet("$normalized/track/", HEALTH_TIMEOUT_MS, params)
            if (reply.status !in 200..299 || reply.body.isBlank()) return@runCatching InstanceHealth.UNREACHABLE
            val data =
                Json.parseToJsonElement(reply.body).jsonObjectOrNull?.obj("data")
                    ?: return@runCatching InstanceHealth.UNREACHABLE
            if (data.stringOrNull("assetPresentation").equals("PREVIEW", ignoreCase = true)) {
                return@runCatching InstanceHealth.PREVIEW_ONLY
            }
            // No inline manifest means the instance answered but cannot actually stream.
            if (data.stringOrNull("manifest").isNullOrBlank()) InstanceHealth.UNREACHABLE else InstanceHealth.HEALTHY
        }.getOrDefault(InstanceHealth.UNREACHABLE)
    }

    /** Feeds an external health result into the resolver's cooldown map. */
    suspend fun applyHealthResult(
        baseUrl: String,
        healthy: Boolean,
    ) {
        val normalized = normalizeInstanceUrl(baseUrl) ?: return
        if (healthy) markInstanceHealthy(normalized) else markInstanceFailed(normalized, hardFailure = true)
    }

    suspend fun discoverInstances(): List<String> {
        if (!poolEnabled()) {
            clearPoolDiscovery()
            return emptyList()
        }
        val discovered = discoverInstancesWithinAttempt()
        if (!poolEnabled()) {
            clearPoolDiscovery()
            return emptyList()
        }
        if (discovered.isNotEmpty()) poolDiscoveredInstanceUrls = poolDiscoveredInstanceUrls + discovered
        return discovered
    }

    private suspend fun discoverInstancesWithinAttempt(): List<String> {
        val discovered = LinkedHashSet<String>()
        for (source in INSTANCE_DISCOVERY_SOURCES) {
            if (!poolEnabled()) break
            runCatching {
                val headers =
                    sourceProviderKey()
                        .takeIf { it.isNotBlank() }
                        ?.let { mapOf("Authorization" to "Bearer $it") }
                        ?: emptyMap()
                val reply = healthGet(source, HEALTH_TIMEOUT_MS, headers = headers)
                if (!poolEnabled()) return@runCatching
                if (reply.status !in 200..299) return@runCatching
                parseDiscoveredInstances(reply.body).forEach { url ->
                    normalizeInstanceUrl(url)?.let(discovered::add)
                }
            }
        }
        return discovered.toList()
    }

    /**
     * Parses a discovery payload: `{ "api": [...], "streaming": [...] }` objects (streaming first,
     * `.squid.wtf` dropped, `qobuz` ignored), a JSON array of strings or `{url,host}` objects, or a
     * plain whitespace-separated list.
     */
    private fun parseDiscoveredInstances(body: String): List<String> {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return emptyList()
        runCatching {
            val obj = Json.parseToJsonElement(trimmed).jsonObject
            val result = LinkedHashSet<String>()
            for (key in listOf("streaming", "instances", "api")) {
                obj.array(key)?.let { collectStringsFromArray(it, result) }
            }
            val filtered = result.filterNot { it.contains(".squid.wtf", ignoreCase = true) }
            if (filtered.isNotEmpty()) return filtered
        }
        runCatching {
            val result = LinkedHashSet<String>()
            collectStringsFromArray(Json.parseToJsonElement(trimmed).jsonArray, result)
            if (result.isNotEmpty()) return result.toList()
        }
        return trimmed
            .split('\n', '\r', ' ', ',')
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
    }

    private fun collectStringsFromArray(
        arr: JsonArray,
        into: MutableCollection<String>,
    ) {
        for (item in arr) {
            when (item) {
                is JsonObject -> {
                    (item.stringOrNull("url") ?: item.stringOrNull("host"))?.let(into::add)
                }
                else -> item.jsonPrimitiveOrNull?.content?.takeIf { it.isNotBlank() }?.let(into::add)
            }
        }
    }
    // endregion

    // region runtime state (cooldowns, caches, rate-limit latch)
    private val stateMutex = Mutex()
    private val trackCache = mutableMapOf<String, CachedTrack>()
    private val searchCache = mutableMapOf<String, CachedSearch>()
    private val streamCache = mutableMapOf<String, Resolved>()
    private val streamFailureCache = mutableMapOf<String, CachedFailure>()

    @kotlin.concurrent.Volatile
    private var resolverRateLimitedUntilMs = 0L

    private val instanceCooldownUntilMs = mutableMapOf<String, Long>()

    private suspend fun markInstanceHealthy(baseUrl: String) {
        stateMutex.withLock { instanceCooldownUntilMs.remove(baseUrl) }
    }

    private suspend fun markInstanceFailed(
        baseUrl: String,
        hardFailure: Boolean,
    ) {
        val cooldownMs = if (hardFailure) INSTANCE_HARD_COOLDOWN_MS else INSTANCE_SOFT_COOLDOWN_MS
        stateMutex.withLock { instanceCooldownUntilMs[baseUrl] = nowMs() + cooldownMs }
    }

    private fun isInstanceCoolingDown(
        baseUrl: String,
        now: Long,
    ): Boolean = (instanceCooldownUntilMs[baseUrl] ?: 0L) > now

    /** Healthy endpoints first; cooling-down ones trail only when everything is down. */
    private fun orderedEndpoints(): List<TidalDownloadEndpoint> {
        val now = nowMs()
        return activeEndpoints.sortedBy { if (isInstanceCoolingDown(it.baseUrl, now)) 1 else 0 }
    }
    // endregion

    // region resolution entry point
    /**
     * Resolves [query] to a stream, racing the configured instances. `trustedTrackId` is the
     * authoritative catalog id from the request's `directIds` entry; when the winning track equals
     * it, the emitted stream is marked `trustedDirectId` like the retained DirectStream.
     */
    suspend fun resolve(
        query: Query,
        preferAtmos: Boolean = false,
        audioQuality: TidalAudioQuality = TidalAudioQuality.AAC_320,
        accountScope: String? = null,
        trustedTrackId: String? = null,
        deadlineMs: Long = nowMs() + PROVIDER_ATTEMPT_MS,
    ): Resolved = resolveWithinAttempt(query, preferAtmos, audioQuality, accountScope, trustedTrackId, deadlineMs)

    private suspend fun resolveWithinAttempt(
        query: Query,
        preferAtmos: Boolean,
        audioQuality: TidalAudioQuality,
        accountScope: String?,
        trustedTrackId: String?,
        deadlineMs: Long,
    ): Resolved {
        if (activeEndpoints.isEmpty()) {
            throw TidalAudioResolutionException("TIDAL playback has no configured instance")
        }
        val now = nowMs()
        val cooldownRemainingMs = resolverRateLimitedUntilMs - now
        if (cooldownRemainingMs > 0L) {
            throw TidalRateLimitedException(cooldownRemainingMs)
        }
        val directTrackId = query.mediaId.toTidalTrackIdOrNull()
        val trackCacheKey = query.trackCacheKey()
        val tracks = selectTracks(query, directTrackId, trackCacheKey, now, deadlineMs)

        if (tracks.isEmpty()) {
            throw TidalAudioResolutionException("TIDAL match not found for ${query.title}")
        }

        val errors = mutableListOf<String>()
        var deferredAacFallback: Resolved? = null
        val streamCandidateLimit = if (directTrackId != null) MAX_DIRECT_STREAM_CANDIDATES else MAX_STREAM_CANDIDATES
        for (track in tracks.take(streamCandidateLimit)) {
            for (quality in streamQualityCandidates(track, preferAtmos, audioQuality)) {
                if (nowMs() >= deadlineMs) break
                val streamCacheKey = "${query.mediaId}::${track.trackId}::$quality::live"
                val trackFailureCacheKey = "track:${track.trackId}::$quality::live"
                val cachedStream = stateMutex.withLock { streamCache[streamCacheKey]?.takeIf { it.expiresAtMs > now + 20_000L } }
                if (cachedStream != null) {
                    if (cachedStream.losslessDowngradedBitrateKbps == null) {
                        return cachedStream
                    }
                    deferredAacFallback = deferredAacFallback ?: cachedStream
                    errors += "${track.trackId}/$quality: cached AAC fallback"
                    continue
                }

                val cachedFailure = cachedFailureFor(now, streamCacheKey, trackFailureCacheKey)
                if (cachedFailure != null) {
                    errors += "${track.trackId}/$quality: cached resolver failure: ${cachedFailure.message}"
                    continue
                }

                val streamAttempt =
                    runCatching {
                        requestDirectFlac(
                            track = track,
                            quality = quality,
                            durationMs = query.durationMs ?: track.durationMs,
                            now = now,
                            audioQuality = audioQuality,
                            accountScope = accountScope,
                            trustedTrackId = trustedTrackId,
                            deadlineMs = deadlineMs,
                        )
                    }.onFailure { error ->
                        if (error is CancellationException) throw error
                        errors += "${track.trackId}/$quality: ${error.message ?: error::class.simpleName}"
                        if (nowMs() < deadlineMs) {
                            cacheStreamFailure(now, error, streamCacheKey, trackFailureCacheKey)
                        }
                    }

                streamAttempt.getOrNull()?.let { resolved ->
                    stateMutex.withLock {
                        streamCache[streamCacheKey] = resolved
                        if (directTrackId == null) {
                            trackCache[trackCacheKey] = CachedTrack(track, now + TRACK_CACHE_MS)
                        }
                    }
                    if (resolved.losslessDowngradedBitrateKbps == null) {
                        return resolved
                    }
                    deferredAacFallback = deferredAacFallback ?: resolved
                    errors += "${track.trackId}/$quality: returned AAC fallback"
                }
            }
        }

        deferredAacFallback?.let { return it }

        throw TidalAudioResolutionException(
            "TIDAL FLAC stream not found for ${query.title}: ${errors.joinToString("; ").take(720)}",
        )
    }

    /**
     * The candidate selection half of [resolveWithinAttempt]. A numeric media id is the direct track;
     * otherwise the scored search results plus any cached track. The signed-in path does not use this:
     * it matches through its own bearer search, as the retained account resolver does.
     */
    internal suspend fun selectTracks(
        query: Query,
        directTrackId: String? = query.mediaId.toTidalTrackIdOrNull(),
        trackCacheKey: String = query.trackCacheKey(),
        now: Long = nowMs(),
        deadlineMs: Long = now + PROVIDER_ATTEMPT_MS,
    ): List<MatchedTrack> =
        if (directTrackId != null) {
            val directTrack = resolveTrackById(directTrackId) ?: query.toDirectMatchedTrack(directTrackId)
            buildList {
                add(directTrack)
                findCandidateTracks(query, deadlineMs)
                    .asSequence()
                    .filterNot { it.trackId == directTrack.trackId }
                    .take(MAX_DIRECT_STREAM_CANDIDATES - 1)
                    .forEach { add(it) }
            }.distinctBy { it.trackId }
        } else {
            val cached = stateMutex.withLock { trackCache[trackCacheKey]?.takeIf { it.expiresAtMs > now }?.track }
            val candidates = findCandidateTracks(query, deadlineMs)
            buildList {
                addAll(candidates)
                cached?.let(::add)
            }.distinctBy { it.trackId }
        }

    suspend fun invalidate(mediaId: String) {
        val prefix = "$mediaId::"
        stateMutex.withLock {
            streamCache.keys.filter { it.startsWith(prefix) }.toList().forEach { streamCache.remove(it) }
            streamFailureCache.keys.filter { it.startsWith(prefix) }.toList().forEach { streamFailureCache.remove(it) }
            trackCache.remove(mediaId.trackCacheKeyFallback())
        }
    }

    private suspend fun cachedFailureFor(
        now: Long,
        vararg keys: String,
    ): CachedFailure? =
        stateMutex.withLock {
            for (key in keys.distinct()) {
                val failure = streamFailureCache[key] ?: continue
                if (failure.expiresAtMs > now) return@withLock failure
                streamFailureCache.remove(key)
            }
            null
        }

    private suspend fun cacheStreamFailure(
        now: Long,
        error: Throwable,
        vararg keys: String,
    ) {
        val message = error.message ?: error::class.simpleName.orEmpty()
        val expiresAtMs = now + error.streamFailureCacheDurationMs()
        stateMutex.withLock {
            keys
                .distinct()
                .forEach { key -> streamFailureCache[key] = CachedFailure(message = message, expiresAtMs = expiresAtMs) }
        }
    }

    private fun Throwable.streamFailureCacheDurationMs(): Long =
        when {
            this is TidalRateLimitedException || causeChainContains("rate limited") -> RATE_LIMIT_COOLDOWN_MS
            causeChainContains("did not return FLAC") || causeChainContains("downgraded") -> TRACK_STREAM_FAILURE_CACHE_MS
            else -> STREAM_FAILURE_CACHE_MS
        }

    private fun Throwable.causeChainContains(text: String): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current.message?.contains(text, ignoreCase = true) == true) return true
            current = current.cause
        }
        return false
    }

    suspend fun searchCandidates(
        query: Query,
        limit: Int = 8,
    ): List<CandidateMetadata> =
        findCandidateTracks(query, nowMs() + PROVIDER_ATTEMPT_MS)
            .take(limit.coerceAtLeast(1))
            .map { track ->
                val thumbUrl =
                    track.albumCoverId?.let { coverId ->
                        "https://resources.tidal.com/images/${coverId.replace("-", "/")}/320x320.jpg"
                    }
                CandidateMetadata(
                    trackId = track.trackId,
                    title = track.title,
                    artist = track.artistNames.joinToString(", "),
                    album = track.album,
                    isrc = track.isrc,
                    durationMs = track.durationMs,
                    thumbnailUrl = thumbUrl,
                )
            }
    // endregion

    // region candidate search and scoring
    fun normalizeIsrc(value: String?): String? {
        val compact = value?.uppercase()?.replace(ISRC_STRIP_REGEX, "") ?: return null
        return ISRC_PATTERN_REGEX.find(compact)?.value
    }

    private suspend fun findCandidateTracks(
        query: Query,
        deadlineMs: Long,
    ): List<MatchedTrack> {
        if (nowMs() >= deadlineMs) return emptyList()
        val candidates = mutableListOf<ScoredTrack>()
        val wantedTitle = query.title.titleMatchNormalized()
        val wantedArtists = query.artists.map { it.normalized() }.filter { it.isNotBlank() }
        val wantedAlbum = query.album.normalized()
        val wantedIsrc = normalizeIsrc(query.isrc)
        val wantedDurationMs = query.durationMs?.takeIf { it > 0L }
        wantedIsrc?.let { isrc ->
            searchTracks(isrc, exactIsrc = true)
                ?.let { selectCandidateTracks(it, query, exactIsrcOnly = true) }
                ?.losslessFirst()
                ?.firstOrNull()
                ?.takeIf { it.score >= MIN_MATCH_SCORE }
                ?.let { return listOf(it.track) }
        }

        for (term in searchTerms(query)) {
            if (nowMs() >= deadlineMs) break
            val results = searchTracks(term) ?: continue
            candidates += selectCandidateTracks(results, query)
            candidates
                .losslessFirst()
                .firstOrNull()
                ?.takeIf { it.score >= STRONG_MATCH_SCORE && it.track.losslessRank() > 0 }
                ?.let { return listOf(it.track) }
        }
        if (nowMs() < deadlineMs) {
            resolveSongLinkTidalTrackId(query)?.let { tidalId ->
                val track = resolveTrackById(tidalId) ?: query.toDirectMatchedTrack(tidalId)
                val score = scoreTrack(track, wantedTitle, wantedArtists, wantedAlbum, wantedIsrc, wantedDurationMs)
                if (score >= MIN_MATCH_SCORE) {
                    if (track.losslessRank() > 0) {
                        return listOf(track)
                    }
                    candidates += ScoredTrack(track, score)
                }
            }
        }
        return candidates
            .groupBy { it.track.trackId }
            .mapNotNull { (_, matches) -> matches.maxByOrNull { it.score } }
            .losslessFirst()
            .map { it.track }
    }

    private suspend fun searchTracks(
        term: String,
        exactIsrc: Boolean = false,
    ): JsonArray? {
        if (term.isBlank()) return null
        searchTracksFromDirectApi(term, exactIsrc)?.takeIf { it.isNotEmpty() }?.let { return it }
        return searchTracksFromTidalApi(term)
    }

    private suspend fun searchTracksFromDirectApi(
        term: String,
        exactIsrc: Boolean,
    ): JsonArray? {
        val cacheKey = "direct:${if (exactIsrc) "isrc" else "query"}:${term.lowercase()}"
        val now = nowMs()
        searchCache[cacheKey]?.takeIf { it.expiresAtMs > now }?.let { return it.results }
        val parameter = if (exactIsrc) "i" else "s"
        for (endpoint in orderedEndpoints().take(MAX_CONCURRENT_TIDAL_ENDPOINTS)) {
            val params = listOf(parameter to term, "limit" to "$SEARCH_LIMIT", "offset" to "0")
            runCatching {
                val reply = streamGet("${endpoint.baseUrl}/search", STREAM_REQUEST_TIMEOUT_MS, params)
                if (reply.status !in 200..299) return@runCatching null
                val body = reply.body.takeIf { it.isNotBlank() } ?: return@runCatching null
                val root = Json.parseToJsonElement(body).jsonObjectOrNull
                root?.obj("data")?.array("items") ?: root?.array("items")
            }.getOrNull()?.let { results ->
                stateMutex.withLock { searchCache[cacheKey] = CachedSearch(results, now + SEARCH_CACHE_MS) }
                return results
            }
        }
        return null
    }

    private suspend fun searchTracksFromTidalApi(term: String): JsonArray? {
        val params =
            listOf(
                "countryCode" to COUNTRY_CODE,
                "locale" to LOCALE,
                "deviceType" to DEVICE_TYPE,
                "query" to term,
                "limit" to "$SEARCH_LIMIT",
                "offset" to "0",
            )
        return runCatching {
            val reply =
                streamGet(
                    "$API_BASE_URL/search/tracks",
                    STREAM_REQUEST_TIMEOUT_MS,
                    params,
                    mapOf(HttpHeaders.UserAgent to BROWSER_USER_AGENT, "x-tidal-token" to PUBLIC_TOKEN),
                )
            if (reply.status !in 200..299) return@runCatching null
            Json.parseToJsonElement(reply.body).jsonObjectOrNull?.array("items")
        }.getOrNull()
    }

    private suspend fun resolveTrackById(trackId: String): MatchedTrack? {
        val params =
            listOf("countryCode" to COUNTRY_CODE, "locale" to LOCALE, "deviceType" to DEVICE_TYPE)
        return runCatching {
            val reply =
                streamGet(
                    "$API_BASE_URL/tracks/$trackId",
                    STREAM_REQUEST_TIMEOUT_MS,
                    params,
                    mapOf(HttpHeaders.UserAgent to BROWSER_USER_AGENT, "x-tidal-token" to PUBLIC_TOKEN),
                )
            if (reply.status !in 200..299) return@runCatching null
            Json.parseToJsonElement(reply.body).jsonObjectOrNull?.toMatchedTrack()
        }.getOrNull()
    }

    private fun selectCandidateTracks(
        results: JsonArray,
        query: Query,
        exactIsrcOnly: Boolean = false,
    ): List<ScoredTrack> {
        val wantedTitle = query.title.titleMatchNormalized()
        val wantedArtists = query.artists.map { it.normalized() }.filter { it.isNotBlank() }
        val wantedAlbum = query.album.normalized()
        val wantedIsrc = normalizeIsrc(query.isrc)
        val wantedDurationMs = query.durationMs?.takeIf { it > 0L }
        val candidates = mutableListOf<ScoredTrack>()
        for (element in results) {
            val obj = element.jsonObjectOrNull ?: continue
            val track = obj.toMatchedTrack() ?: continue
            if (exactIsrcOnly && normalizeIsrc(track.isrc) != wantedIsrc) continue
            val score = scoreTrack(track, wantedTitle, wantedArtists, wantedAlbum, wantedIsrc, wantedDurationMs)
            if (score >= MIN_MATCH_SCORE) {
                candidates += ScoredTrack(track, score)
            }
        }
        return candidates.losslessFirst()
    }

    private fun List<ScoredTrack>.losslessFirst(): List<ScoredTrack> =
        sortedWith(compareByDescending<ScoredTrack> { it.track.losslessRank() }.thenByDescending { it.score })

    private fun scoreTrack(
        track: MatchedTrack,
        wantedTitle: String,
        wantedArtists: List<String>,
        wantedAlbum: String,
        wantedIsrc: String?,
        wantedDurationMs: Long?,
    ): Int {
        val candidateTitle = track.title.titleMatchNormalized()
        val candidateArtists = track.artistNames.map { it.normalized() }.filter { it.isNotBlank() }
        val candidateAlbum = track.album.normalized()
        val candidateIsrc = normalizeIsrc(track.isrc)

        if (wantedTitle.isBlank() || candidateTitle.isBlank()) return REJECT_SCORE
        if (wantedIsrc != null && candidateIsrc == wantedIsrc) {
            return if (TrackMatching.durationMatches(wantedDurationMs, track.durationMs)) {
                220 + track.qualityScoreBoost()
            } else {
                REJECT_SCORE
            }
        }
        if (hasVersionMismatch(wantedTitle, candidateTitle)) return REJECT_SCORE

        val wantedTitleTokens = significantTokens(wantedTitle)
        val candidateTitleTokens = significantTokens(candidateTitle)
        var score = 0
        when {
            candidateTitle == wantedTitle -> score += 110
            candidateTitle.contains(wantedTitle) || wantedTitle.contains(candidateTitle) -> score += 62
            else -> {
                val titleOverlap = tokenOverlap(wantedTitleTokens, candidateTitleTokens)
                if (titleOverlap < 0.50) return REJECT_SCORE
                score += (titleOverlap * 48).roundToInt()
            }
        }
        val extraCandidateTitleTokens = candidateTitleTokens - wantedTitleTokens
        score -= (extraCandidateTitleTokens.size * 6).coerceAtMost(24)

        if (wantedArtists.isNotEmpty() && candidateArtists.isNotEmpty()) {
            val artistHit =
                wantedArtists.any { wanted ->
                    candidateArtists.any { candidate ->
                        candidate == wanted || candidate.contains(wanted) || wanted.contains(candidate)
                    }
                }
            if (artistHit) score += 38 else return REJECT_SCORE
        }

        if (wantedAlbum.isNotBlank() && candidateAlbum.isNotBlank()) {
            score +=
                when {
                    candidateAlbum == wantedAlbum -> 18
                    candidateAlbum.contains(wantedAlbum) || wantedAlbum.contains(candidateAlbum) -> 10
                    else -> 0
                }
        }

        if (wantedIsrc != null) {
            score +=
                when {
                    candidateIsrc == wantedIsrc -> 160
                    candidateIsrc != null -> -30
                    else -> 0
                }
        }

        val candidateDurationMs = track.durationMs
        if (wantedDurationMs != null && candidateDurationMs != null) {
            val diffSeconds = abs(wantedDurationMs - candidateDurationMs) / 1000L
            if (diffSeconds > 45) return REJECT_SCORE
            score +=
                when {
                    diffSeconds <= 3 -> 36
                    diffSeconds <= 8 -> 18
                    diffSeconds <= 20 -> 4
                    else -> -50
                }
        }

        score += track.qualityScoreBoost()
        return score
    }

    private fun searchTerms(query: Query): List<String> =
        buildList {
            val title = query.title.searchQueryTitle()
            val primaryArtist = query.artists.firstOrNull { it.isNotBlank() }?.searchQueryArtist().orEmpty()
            val titleAndArtist = listOf(title, primaryArtist).filter { it.isNotBlank() }.joinToString(" ")
            add(titleAndArtist)
        }.map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

    private fun Query.toDirectMatchedTrack(trackId: String): MatchedTrack =
        MatchedTrack(
            trackId = trackId,
            title = title,
            artistNames = artists,
            album = album,
            isrc = isrc,
            durationMs = durationMs,
            audioQuality = null,
            mediaTags = emptySet(),
            audioModes = emptySet(),
            sampleRate = null,
        )
    // endregion

    // region song.link fallback
    private suspend fun resolveSongLinkTidalTrackId(query: Query): String? {
        for (sourceUrl in songLinkSourceUrls(query.mediaId)) {
            runCatching {
                val reply =
                    streamGet(
                        SONG_LINK_API_URL,
                        STREAM_REQUEST_TIMEOUT_MS,
                        listOf("url" to sourceUrl),
                        mapOf(HttpHeaders.UserAgent to BROWSER_USER_AGENT),
                    )
                if (reply.status !in 200..299) return@runCatching null
                val body = reply.body.takeIf { it.isNotBlank() } ?: return@runCatching null
                val root = Json.parseToJsonElement(body).jsonObjectOrNull
                root
                    ?.obj("linksByPlatform")
                    ?.obj("tidal")
                    ?.stringOrNull("url")
                    ?.toTidalTrackIdOrNull()
                    ?: root?.obj("songUrls")?.stringOrNull("Tidal")?.toTidalTrackIdOrNull()
            }.getOrNull()?.let { trackId -> return trackId }
        }
        return null
    }

    private fun songLinkSourceUrls(mediaId: String): List<String> {
        val trimmed = mediaId.trim()
        if (trimmed.isBlank()) return emptyList()
        return buildList {
            if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
                add(trimmed)
            }
            SPOTIFY_URI_TRACK_ID_REGEX.find(trimmed)?.groupValues?.getOrNull(1)?.let {
                add("https://open.spotify.com/track/$it")
            }
            if (trimmed.matches(SPOTIFY_ID_ONLY_REGEX)) {
                add("https://open.spotify.com/track/$trimmed")
            }
            if (trimmed.matches(YOUTUBE_ID_ONLY_REGEX)) {
                add("https://music.youtube.com/watch?v=$trimmed")
            }
        }.distinct()
    }
    // endregion

    // region stream request: race, dialects, manifest handling
    private suspend fun requestDirectFlac(
        track: MatchedTrack,
        quality: String,
        durationMs: Long?,
        now: Long,
        audioQuality: TidalAudioQuality,
        accountScope: String?,
        trustedTrackId: String?,
        deadlineMs: Long,
    ): Resolved {
        val trustedDirectId = trustedTrackId == track.trackId
        val isAtmosRequest = quality.equals("DOLBY_ATMOS", ignoreCase = true)
        val manifestFormats = quality.tidalManifestFormats()
        val errors = mutableListOf<String>()
        var rateLimitCount = 0
        var longestRetryAfterMs = 0L
        val endpoints = orderedEndpoints().take(MAX_CONCURRENT_TIDAL_ENDPOINTS)

        return supervisorScope {
            val results = Channel<Pair<TidalDownloadEndpoint, Result<Resolved>>>(endpoints.size.coerceAtLeast(1))
            val jobs =
                endpoints.map { endpoint ->
                    launch {
                        val result: Result<Resolved> =
                            try {
                                Result.success(
                                    requestDirectFlacFromEndpoint(
                                        endpoint = endpoint,
                                        isAtmosRequest = isAtmosRequest,
                                        manifestFormats = manifestFormats,
                                        track = track,
                                        quality = quality,
                                        durationMs = durationMs,
                                        now = now,
                                        audioQuality = audioQuality,
                                        accountScope = accountScope,
                                        trustedDirectId = trustedDirectId,
                                        deadlineMs = deadlineMs,
                                    ),
                                )
                            } catch (cancel: CancellationException) {
                                throw cancel
                            } catch (error: Throwable) {
                                Result.failure(error)
                            }
                        results.send(endpoint to result)
                    }
                }

            var deferredAacFallback: Resolved? = null
            try {
                repeat(endpoints.size) {
                    val (endpoint, result) = results.receive()
                    val resolved = result.getOrNull()
                    if (resolved != null) {
                        markInstanceHealthy(endpoint.baseUrl)
                        lastResolvedTrackId = track.trackId
                        if (resolved.losslessDowngradedBitrateKbps == null) {
                            jobs.forEach { it.cancel() }
                            return@supervisorScope resolved
                        }
                        if (deferredAacFallback == null) deferredAacFallback = resolved
                        return@repeat
                    }

                    val error = result.exceptionOrNull() ?: TidalAudioResolutionException("unknown mirror failure")
                    if (nowMs() >= deadlineMs) {
                        errors += "${endpoint.name}: source attempt deadline exceeded"
                    } else {
                        if (error is TidalRateLimitedException) {
                            rateLimitCount += 1
                            longestRetryAfterMs = maxOf(longestRetryAfterMs, error.retryAfterMs)
                        } else {
                            markInstanceFailed(endpoint.baseUrl, hardFailure = error.isHardTransportError())
                        }
                        errors += "${endpoint.name}: ${error.message ?: error::class.simpleName}"
                    }
                }
            } finally {
                jobs.forEach { it.cancel() }
                results.close()
            }

            deferredAacFallback?.let { return@supervisorScope it }

            if (rateLimitCount == endpoints.size && longestRetryAfterMs > 0L) {
                resolverRateLimitedUntilMs = nowMs() + longestRetryAfterMs
                throw TidalRateLimitedException(longestRetryAfterMs)
            }
            throw TidalAudioResolutionException(
                "TIDAL resolver failed on all mirrors for ${track.title}: ${errors.joinToString(" | ").take(720)}",
            )
        }
    }

    /** Requests a direct stream from one instance, transparently retrying on the older API dialect. */
    private suspend fun requestDirectFlacFromEndpoint(
        endpoint: TidalDownloadEndpoint,
        isAtmosRequest: Boolean,
        manifestFormats: List<String>?,
        track: MatchedTrack,
        quality: String,
        durationMs: Long?,
        now: Long,
        audioQuality: TidalAudioQuality,
        accountScope: String?,
        trustedDirectId: Boolean,
        deadlineMs: Long,
    ): Resolved =
        try {
            requestDirectFlacFromEndpointDialect(
                endpoint, isAtmosRequest, manifestFormats, track, quality, durationMs, now, audioQuality,
                accountScope, trustedDirectId, deadlineMs,
            )
        } catch (unsupported: TidalDialectUnsupportedException) {
            requestDirectFlacFromEndpointDialect(
                endpoint, isAtmosRequest, null, track, quality, durationMs, now, audioQuality,
                accountScope, trustedDirectId, deadlineMs,
            )
        }

    private suspend fun requestDirectFlacFromEndpointDialect(
        endpoint: TidalDownloadEndpoint,
        isAtmosRequest: Boolean,
        manifestFormats: List<String>?,
        track: MatchedTrack,
        quality: String,
        durationMs: Long?,
        now: Long,
        audioQuality: TidalAudioQuality,
        accountScope: String?,
        trustedDirectId: Boolean,
        deadlineMs: Long,
    ): Resolved {
        // The Monochrome/hifi API is FastAPI with redirect_slashes, so endpoints require a TRAILING
        // SLASH (`/trackManifests/`, `/track/`). null formats selects the `/track/?id=&quality=`
        // request shape.
        val url: String
        val params: List<Pair<String, String>>
        if (manifestFormats != null) {
            url = "${endpoint.baseUrl}/trackManifests/"
            params =
                buildList {
                    add("id" to track.trackId)
                    manifestFormats.forEach { add("formats" to it) }
                    add("adaptive" to "false")
                    add("manifestType" to "MPEG_DASH")
                    add("uriScheme" to "HTTPS")
                    add("usage" to "PLAYBACK")
                }
        } else {
            url = "${endpoint.baseUrl}/track/"
            params = listOf("id" to track.trackId, "quality" to quality)
        }

        val reply = streamGet(url, STREAM_REQUEST_TIMEOUT_MS, params)
        if (reply.status == 429) {
            throw TidalRateLimitedException(rateLimitRetryAfterMs(reply))
        }
        if (reply.status !in 200..299) {
            val detail = "TIDAL ${endpoint.name} HTTP ${reply.status}: ${reply.body.take(180)}"
            if (reply.status == 404 && manifestFormats != null) {
                throw TidalDialectUnsupportedException(detail)
            }
            throw TidalAudioResolutionException(detail)
        }
        // 202 is a 2xx that carries a queue ticket instead of the playback payload.
        val payload = if (reply.status == 202) awaitQueuedPlayback(endpoint, reply, deadlineMs) else reply.body
        val root =
            Json.parseToJsonElement(payload).jsonObjectOrNull
                ?: throw TidalAudioResolutionException("TIDAL FLAC resolver returned no data")
        val data = root.obj("data") ?: throw TidalAudioResolutionException("TIDAL FLAC resolver returned no data")
        val effectiveDurationMs = durationMs ?: track.durationMs
        val resolvedQuality: String
        val manifest: ParsedManifest
        val dataSampleRate: Int?
        val dataBitDepth: Int?
        if (manifestFormats != null) {
            val attributes =
                data.obj("data")?.obj("attributes")
                    ?: throw TidalAudioResolutionException("TIDAL manifest payload missing attributes")
            val presentation = attributes.stringOrNull("trackPresentation") ?: attributes.stringOrNull("assetPresentation")
            if (presentation.equals("PREVIEW", ignoreCase = true)) {
                val reason = attributes.stringOrNull("previewReason").orEmpty()
                throw TidalAudioResolutionException(
                    "TIDAL instance returned a PREVIEW (unsubscribed account)${if (reason.isNotBlank()) ": $reason" else ""}",
                )
            }
            val returnedFormats =
                attributes.array("formats")?.mapNotNull { it.jsonPrimitiveOrNull?.content?.takeIf(String::isNotBlank) }
                    .orEmpty()
            if (returnedFormats.none { returned -> manifestFormats.any { it.equals(returned, ignoreCase = true) } }) {
                throw TidalAudioResolutionException(
                    "TIDAL API did not report ${manifestFormats.joinToString()} for this track",
                )
            }
            val manifestUrl =
                attributes.stringOrNull("uri") ?: throw TidalAudioResolutionException("TIDAL manifest URI was empty")
            val manifestText = fetchText(manifestUrl)
            manifest = parseManifestText(manifestText, "audio/mp4")
            resolvedQuality = returnedFormats.toResolvedTidalQuality(quality)
            dataSampleRate = manifest.sampleRate ?: 48_000
            dataBitDepth = null
        } else {
            if (data.stringOrNull("assetPresentation").equals("PREVIEW", ignoreCase = true)) {
                throw TidalAudioResolutionException("TIDAL FLAC resolver returned a preview asset")
            }
            val manifestPayload =
                data.stringOrNull("manifest") ?: throw TidalAudioResolutionException("TIDAL FLAC resolver returned no manifest")
            resolvedQuality = data.stringOrNull("audioQuality") ?: quality
            manifest = parseManifest(manifestPayload, data.stringOrNull("manifestMimeType"), effectiveDurationMs)
            dataSampleRate = normalizeSampleRate(data.doubleOrNull("sampleRate"))
            dataBitDepth = data.longOrNull("bitDepth")?.toInt()
        }
        val manifestLooksFlac =
            manifest.mimeType.contains("flac", ignoreCase = true) ||
                manifest.codecs.contains("flac", ignoreCase = true)
        val isAtmosManifest =
            isAtmosRequest ||
                resolvedQuality.contains("ATMOS", ignoreCase = true) ||
                manifest.codecs.contains("eac3", ignoreCase = true) ||
                manifest.codecs.contains("ec-3", ignoreCase = true)
        val returnedHiRes =
            resolvedQuality.contains("HI_RES", ignoreCase = true) ||
                resolvedQuality.contains("HIRES", ignoreCase = true) ||
                resolvedQuality.contains("MAX", ignoreCase = true) ||
                (dataBitDepth ?: 0) >= 24 ||
                (dataSampleRate ?: manifest.sampleRate ?: 0) > 48_000
        if (audioQuality == TidalAudioQuality.HI_RES_LOSSLESS && quality.isLosslessQuality() && !returnedHiRes) {
            throw TidalAudioResolutionException(
                "TIDAL HiRes was unavailable for ${track.title}; resolver returned ${resolvedQuality.ifBlank { quality }}",
            )
        }

        // In the retained code a FLAC DASH became a `tidal-dash://` URI over a temp manifest (or a
        // remuxed .flac file for downloads); a non-FLAC DASH downloaded to an .m4a temp file. In the
        // KMP port every DASH rides PreparedAudio.TidalManifest — the native dash route remuxes it.
        val playbackStreamInfo =
            if (!manifest.isDash) {
                inspectRemotePlaybackUrl(manifest.url, manifest)
            } else {
                null
            }
        val resolvedCodecs =
            playbackStreamInfo?.codecs
                ?: manifest.codecs.takeIf { it.isNotBlank() }
                ?: when {
                    manifestLooksFlac -> "flac"
                    isAtmosManifest -> "ec-3"
                    else -> "mp4a.40.2"
                }
        val playbackMimeType = playbackStreamInfo?.mimeType ?: manifest.mimeType.coerceDirectPlaybackMimeType(manifest.url)
        val isResolvedFlacLike =
            playbackMimeType.contains("flac", ignoreCase = true) || resolvedCodecs.contains("flac", ignoreCase = true)
        val isLosslessDowngrade = quality.isLosslessQuality() && !isResolvedFlacLike && !isAtmosManifest
        if (quality.equals("HI_RES_LOSSLESS", ignoreCase = true) && isLosslessDowngrade) {
            throw TidalAudioResolutionException(
                "TIDAL $quality did not return FLAC (${playbackMimeType}, ${resolvedCodecs})",
            )
        }
        val streamMetadata =
            when {
                manifest.isDash -> StreamMetadata(playbackMimeType, null)
                playbackStreamInfo != null -> StreamMetadata(playbackStreamInfo.mimeType, playbackStreamInfo.contentLength)
                else -> fetchStreamMetadata(manifest.url)
            }
        val sampleRate = dataSampleRate ?: manifest.sampleRate ?: normalizeSampleRate(track.sampleRate?.toDouble())
        val bitrate = manifest.bitrate ?: estimateBitrate(streamMetadata.contentLength, effectiveDurationMs)
        validateLikelyFullTrack(track, effectiveDurationMs, streamMetadata.contentLength)
        val resolvedLabel =
            when {
                isAtmosManifest -> "TIDAL Atmos"
                isLosslessDowngrade -> "TIDAL AAC"
                resolvedQuality.contains("HI_RES", ignoreCase = true) -> "TIDAL HiRes FLAC"
                quality.isLossyQuality() || resolvedQuality.equals("HIGH", ignoreCase = true) -> "TIDAL AAC"
                else -> "TIDAL FLAC"
            }
        val losslessDowngradedBitrateKbps = if (isLosslessDowngrade) ((bitrate ?: 320_000) / 1000).coerceAtLeast(1) else null
        val expiresAtMs = extractExpiryMs(manifest.expiryUrl ?: manifest.url, now)
        val providerTrack = track.toProviderTrack()

        val prepared: PreparedAudio =
            if (manifest.isDash) {
                PreparedAudio.TidalManifest(
                    manifestText = manifest.manifestText ?: throw TidalAudioResolutionException("TIDAL manifest had no text"),
                    track = providerTrack,
                    quality = resolvedQuality,
                    trustedDirectId = trustedDirectId,
                    accountScope = accountScope,
                )
            } else {
                PreparedAudio.Progressive(
                    DirectStream(
                        uri = manifest.url,
                        mimeType = playbackMimeType,
                        codecs = resolvedCodecs,
                        contentLength = streamMetadata.contentLength,
                        label = resolvedLabel,
                        source = AudioSourceType.TIDAL,
                        matchedTitle = track.title,
                        matchedArtist = track.artistNames.joinToString(", ").takeIf { it.isNotBlank() },
                        matchedAlbum = track.album,
                        matchedDurationMs = track.durationMs,
                        trustedDirectId = trustedDirectId,
                        sampleRate = sampleRate,
                        bitDepth = dataBitDepth,
                        accountScope = accountScope,
                    ),
                )
            }
        return Resolved(
            prepared = prepared,
            trackId = track.trackId,
            label = resolvedLabel,
            expiresAtMs = expiresAtMs,
            losslessDowngradedBitrateKbps = losslessDowngradedBitrateKbps,
            isLiveManifest = manifest.isDash,
        )
    }

    /**
     * Resolves a directly-playable prepared audio from an official-API `playbackinfopostpaywall`
     * manifest, reusing the same BTS/DASH handling as the public-instance path. The DASH case emits
     * [PreparedAudio.TidalManifest], the KMP counterpart of the retained progressive-dash URI.
     */
    internal fun resolveAccountManifest(
        manifestB64: String,
        declaredMimeType: String?,
        trackId: String,
        quality: String,
        durationMs: Long?,
        accountScope: String?,
        trustedDirectId: Boolean,
        track: MatchedTrack? = null,
    ): PreparedAudio? =
        runCatching {
            val manifest = parseManifest(manifestB64, declaredMimeType, durationMs)
            if (!manifest.isDash) {
                return@runCatching PreparedAudio.Progressive(
                    DirectStream(
                        uri = manifest.url,
                        mimeType = manifest.mimeType,
                        codecs = manifest.codecs,
                        contentLength = null,
                        label = "account $quality",
                        source = AudioSourceType.TIDAL,
                        matchedTitle = track?.title,
                        matchedArtist = track?.artistNames?.joinToString(", ")?.takeIf { it.isNotBlank() },
                        matchedAlbum = track?.album,
                        matchedDurationMs = track?.durationMs,
                        trustedDirectId = trustedDirectId,
                        accountScope = accountScope,
                    ),
                )
            }
            PreparedAudio.TidalManifest(
                manifestText = manifest.manifestText ?: return@runCatching null,
                track = track?.toProviderTrack() ?: matchedTrackPlaceholder(trackId),
                quality = quality,
                trustedDirectId = trustedDirectId,
                accountScope = accountScope,
            )
        }.getOrNull()
    // endregion

    // region manifest parsing
    private fun parseManifest(
        manifestB64: String,
        declaredMimeType: String?,
        durationMs: Long?,
    ): ParsedManifest {
        val text = Base64.decode(manifestB64.trim()).decodeToString().trim()
        return parseManifestText(text, declaredMimeType, durationMs)
    }

    private fun parseManifestText(
        text: String,
        declaredMimeType: String?,
        durationMs: Long? = null,
    ): ParsedManifest {
        val cleanText = text.trim()
        if (cleanText.isBlank()) {
            throw TidalAudioResolutionException("TIDAL FLAC manifest was empty")
        }
        if (cleanText.startsWith("{")) {
            val root =
                Json.parseToJsonElement(cleanText).jsonObjectOrNull
                    ?: throw TidalAudioResolutionException("TIDAL FLAC manifest had no URLs")
            val urls = root.array("urls") ?: throw TidalAudioResolutionException("TIDAL FLAC manifest had no URLs")
            val directUrl = urls.firstStringOrNull() ?: throw TidalAudioResolutionException("TIDAL FLAC manifest URL was empty")
            val rawMimeType = root.stringOrNull("mimeType") ?: AUDIO_FLAC_MIME_TYPE
            val codecs =
                root.stringOrNull("codecs") ?: if (rawMimeType.contains("flac", ignoreCase = true)) "flac" else "mp4a.40.2"
            val mimeType = rawMimeType.coerceDirectPlaybackMimeType(directUrl)
            val sampleRate = normalizeSampleRate(root.doubleOrNull("sampleRate"))
            val bitrate =
                root.longOrNull("bitrate")?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
                    ?: root.longOrNull("bandwidth")?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
            return ParsedManifest(
                url = directUrl,
                mimeType = mimeType,
                codecs = codecs,
                sampleRate = sampleRate,
                bitrate = bitrate,
                expiryUrl = directUrl,
                isDash = false,
            )
        }
        if (cleanText.contains("<MPD", ignoreCase = true)) {
            return parseDashManifest(cleanText.sanitizeXmlEntities(), declaredMimeType)
        }
        throw TidalAudioResolutionException("TIDAL FLAC manifest format was not recognized")
    }

    private fun parseDashManifest(
        text: String,
        declaredMimeType: String?,
    ): ParsedManifest {
        if (
            !text.contains("<SegmentTemplate", ignoreCase = true) &&
            !text.contains("<SegmentList", ignoreCase = true) &&
            !text.contains("<SegmentBase", ignoreCase = true) &&
            !text.contains("<BaseURL", ignoreCase = true)
        ) {
            throw TidalAudioResolutionException("TIDAL DASH manifest had no playable segments")
        }
        val codecs = firstXmlAttr(text, "codecs").ifBlank { "flac" }
        val representationMime = firstXmlAttr(text, "mimeType")
        val sampleRate = firstXmlAttr(text, "audioSamplingRate").toIntOrNull()
        val bandwidth = xmlAttrValues(text, "bandwidth").maxOrNull()
        val segmentUrls =
            TidalDashManifest.parse(text)?.segmentUrls
                ?: throw TidalAudioResolutionException("TIDAL DASH manifest had no playable segments")
        val firstSegmentUrl = HTTP_URL_REGEX.find(text)?.value?.xmlUnescape()
        return ParsedManifest(
            url = "",
            mimeType = representationMime.ifBlank { declaredMimeType ?: "audio/mp4" },
            codecs = codecs,
            sampleRate = sampleRate,
            bitrate = bandwidth,
            expiryUrl = firstSegmentUrl,
            isDash = true,
            segmentUrls = segmentUrls,
            manifestText = text,
        )
    }

    private fun String.coerceDirectPlaybackMimeType(url: String): String {
        if (!contains("dash", ignoreCase = true)) return this
        return when {
            url.endsWith(".flac", ignoreCase = true) -> AUDIO_FLAC_MIME_TYPE
            else -> AUDIO_MP4_MIME_TYPE
        }
    }
    // endregion

    // region remote probes / metadata
    private suspend fun fetchText(url: String): String {
        val reply =
            streamGet(
                url,
                STREAM_REQUEST_TIMEOUT_MS,
                headers =
                    mapOf(
                        HttpHeaders.Accept to "application/dash+xml,application/xml,text/xml,*/*;q=0.8",
                        HttpHeaders.UserAgent to DOWNLOAD_USER_AGENT,
                    ),
            )
        if (reply.status !in 200..299) {
            throw TidalAudioResolutionException("TIDAL manifest fetch returned HTTP ${reply.status}")
        }
        return reply.body.takeIf { it.isNotBlank() }
            ?: throw TidalAudioResolutionException("TIDAL manifest fetch returned an empty body")
    }

    private suspend fun fetchStreamMetadata(url: String): StreamMetadata {
        runCatching {
            val head =
                headGet(
                    url,
                    STREAM_REQUEST_TIMEOUT_MS,
                    mapOf(
                        HttpHeaders.Accept to "audio/flac,audio/mp4,audio/*,*/*;q=0.8",
                        HttpHeaders.UserAgent to BROWSER_USER_AGENT,
                    ),
                )
            if (head.status in 200..299) {
                return StreamMetadata(
                    mimeType = head.contentType?.substringBefore(';'),
                    contentLength = head.contentLength?.takeIf { it > 0L },
                )
            }
        }
        return runCatching {
            val probe =
                streamGet(
                    url,
                    STREAM_REQUEST_TIMEOUT_MS,
                    headers =
                        mapOf(
                            HttpHeaders.Accept to "audio/flac,audio/mp4,audio/*,*/*;q=0.8",
                            HttpHeaders.UserAgent to BROWSER_USER_AGENT,
                            HttpHeaders.Range to "bytes=0-0",
                        ),
                )
            StreamMetadata(
                mimeType = probe.contentType?.substringBefore(';'),
                contentLength =
                    probe.contentRange?.substringAfter('/')?.toLongOrNull()
                        ?: probe.contentLength?.takeIf { it > 0L && probe.status == 206 },
            )
        }.getOrDefault(StreamMetadata("audio/flac", null))
    }

    private suspend fun inspectRemotePlaybackUrl(
        url: String,
        manifest: ParsedManifest,
    ): PlaybackStreamInfo? {
        val reply =
            runCatching {
                streamGet(
                    url,
                    STREAM_REQUEST_TIMEOUT_MS,
                    headers =
                        mapOf(
                            HttpHeaders.Accept to "audio/flac,audio/mp4,audio/*,*/*;q=0.8",
                            HttpHeaders.UserAgent to BROWSER_USER_AGENT,
                            HttpHeaders.Range to "bytes=0-15",
                        ),
                )
            }.getOrNull()
            ?: return null
        if (reply.status !in 200..299 || reply.body.isEmpty()) return null
        val header = reply.body.encodeToByteArray().copyOf(16)
        val contentLength =
            reply.contentRange?.substringAfter('/')?.toLongOrNull()
                ?: reply.contentLength?.takeIf { it > 0L && reply.status != 206 }
        return inspectPlaybackHeader(header, manifest, contentLength)
    }

    private fun inspectPlaybackHeader(
        bytes: ByteArray,
        manifest: ParsedManifest,
        contentLength: Long?,
    ): PlaybackStreamInfo {
        val isFlac = bytes.size >= 4 && bytes[0] == 'f'.code.toByte() && bytes[1] == 'L'.code.toByte() &&
            bytes[2] == 'a'.code.toByte() && bytes[3] == 'C'.code.toByte()
        val isMp4 = bytes.size >= 8 && bytes.copyOfRange(4, 8).decodeToString() == "ftyp"
        if (isFlac) return PlaybackStreamInfo(AUDIO_FLAC_MIME_TYPE, "flac", contentLength)
        if (isMp4) return PlaybackStreamInfo(manifest.mimeType, manifest.codecs.ifBlank { "mp4a.40.2" }, contentLength)
        return PlaybackStreamInfo(manifest.mimeType, manifest.codecs, contentLength)
    }

    private fun validateLikelyFullTrack(
        track: MatchedTrack,
        durationMs: Long?,
        contentLength: Long?,
    ) {
        val duration = durationMs?.takeIf { it >= 60_000L } ?: return
        val length = contentLength?.takeIf { it > 0L } ?: return
        val minimumBytesForFullTrack = (duration / 1000.0 * 96_000.0 / 8.0 * 0.70).toLong()
        if (length < minimumBytesForFullTrack) {
            throw TidalAudioResolutionException(
                "TIDAL returned a likely preview for ${track.title}: $length bytes for ${duration / 1000L}s",
            )
        }
    }

    private fun estimateBitrate(
        contentLength: Long?,
        durationMs: Long?,
    ): Int? {
        val length = contentLength?.takeIf { it > 0L } ?: return null
        val duration = durationMs?.takeIf { it > 0L } ?: return null
        val bitrate = (length * 8L * 1000L) / duration
        return bitrate.takeIf { it in 32_000L..20_000_000L }?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
    }

    private fun normalizeSampleRate(value: Double?): Int? {
        val sampleRate = value?.takeIf { it > 0.0 } ?: return null
        return when {
            sampleRate < 1000.0 -> (sampleRate * 1000.0).roundToInt()
            else -> sampleRate.roundToInt()
        }.takeIf { it > 0 }
    }

    private fun extractExpiryMs(
        url: String,
        now: Long,
    ): Long {
        val httpUrl = runCatching { Url(url) }.getOrNull() ?: return now + STREAM_CACHE_MS
        val params = httpUrl.parameters
        val expiresSeconds =
            params.entries().firstOrNull { it.key.equals("X-Amz-Expires", ignoreCase = true) }
                ?.value?.firstOrNull()?.toLongOrNull()
        val date =
            params.entries().firstOrNull { it.key.equals("X-Amz-Date", ignoreCase = true) }
                ?.value?.firstOrNull()
        if (expiresSeconds != null && date != null) {
            runCatching {
                val issuedAt = parseAmazonDateMs(date)
                return (issuedAt + expiresSeconds * 1000L - 30_000L).coerceAtLeast(now + 60_000L)
            }
        }
        return now + STREAM_CACHE_MS
    }
    // endregion

    // region quality helpers
    private fun streamQualityCandidates(
        track: MatchedTrack,
        preferAtmos: Boolean,
        audioQuality: TidalAudioQuality,
    ): List<String> =
        buildList {
            if (preferAtmos && audioQuality != TidalAudioQuality.AAC_320) {
                add("DOLBY_ATMOS")
            }
            when (audioQuality) {
                TidalAudioQuality.AAC_320 -> add("HIGH")
                TidalAudioQuality.FLAC -> {
                    if (track.supportsHiResLossless() || track.supportsLossless()) {
                        add("LOSSLESS")
                    }
                    add("HIGH")
                }
                TidalAudioQuality.HI_RES_LOSSLESS -> {
                    if (track.supportsHiResLossless()) {
                        add("HI_RES_LOSSLESS")
                    }
                    if (track.supportsHiResLossless() || track.supportsLossless()) {
                        add("LOSSLESS")
                    }
                    add("HIGH")
                }
            }
        }.distinct()

    private fun String.tidalManifestFormats(): List<String>? =
        when {
            equals("DOLBY_ATMOS", ignoreCase = true) -> listOf("EAC3_JOC")
            equals("HI_RES_LOSSLESS", ignoreCase = true) || equals("HI_RES", ignoreCase = true) -> listOf("FLAC_HIRES")
            equals("LOSSLESS", ignoreCase = true) -> listOf("FLAC")
            else -> null
        }

    private fun List<String>.toResolvedTidalQuality(requestedQuality: String): String =
        when {
            any { it.equals("EAC3_JOC", ignoreCase = true) } -> "DOLBY_ATMOS"
            any { it.equals("FLAC_HIRES", ignoreCase = true) } -> "HI_RES_LOSSLESS"
            any { it.equals("FLAC", ignoreCase = true) } -> "LOSSLESS"
            else -> requestedQuality
        }

    private fun String.isLosslessQuality(): Boolean =
        equals("LOSSLESS", ignoreCase = true) ||
            equals("HI_RES_LOSSLESS", ignoreCase = true) ||
            equals("HI_RES", ignoreCase = true)

    private fun String.isLossyQuality(): Boolean =
        equals("HIGH", ignoreCase = true) || equals("LOW", ignoreCase = true)
    // endregion

    // region track parsing
    private fun JsonObject.toMatchedTrack(): MatchedTrack? {
        val trackId = stringOrNull("id") ?: return null
        val title = stringOrNull("title") ?: stringOrNull("name") ?: return null
        val artists = collectArtistNames()
        val albumObj = obj("album")
        val album = albumObj?.stringOrNull("title") ?: albumObj?.stringOrNull("name")
        return MatchedTrack(
            trackId = trackId,
            title = title,
            artistNames = artists,
            album = album,
            isrc = stringOrNull("isrc"),
            durationMs = longOrNull("duration")?.takeIf { it > 0L }?.times(1000L),
            audioQuality = stringOrNull("audioQuality"),
            mediaTags = collectMediaTags(),
            audioModes = collectAudioModes(),
            sampleRate = normalizeSampleRate(doubleOrNull("sampleRate")),
            albumId = albumObj?.stringOrNull("id"),
            albumCoverId = albumObj?.stringOrNull("cover"),
            rawJson = toString(),
        )
    }

    private fun JsonObject.collectArtistNames(): List<String> {
        val names = mutableListOf<String>()
        array("artists")?.let { arr ->
            for (item in arr) {
                item.jsonObjectOrNull?.stringOrNull("name")?.takeIf { it.isNotBlank() }?.let(names::add)
            }
        }
        obj("artist")?.stringOrNull("name")?.takeIf { it.isNotBlank() }?.let(names::add)
        return names.distinct()
    }

    private fun JsonObject.collectMediaTags(): Set<String> {
        val tags = mutableSetOf<String>()
        obj("mediaMetadata")?.array("tags")?.let { arr ->
            for (item in arr) {
                item.jsonPrimitiveOrNull?.content?.takeIf { it.isNotBlank() }?.let(tags::add)
            }
        }
        array("mediaMetadataTags")?.let { arr ->
            for (item in arr) {
                item.jsonPrimitiveOrNull?.content?.takeIf { it.isNotBlank() }?.let(tags::add)
            }
        }
        return tags
    }

    private fun JsonObject.collectAudioModes(): Set<String> {
        val modes = mutableSetOf<String>()
        stringOrNull("audioMode")?.let(modes::add)
        array("audioModes")?.let { arr ->
            for (item in arr) {
                item.jsonPrimitiveOrNull?.content?.takeIf { it.isNotBlank() }?.let(modes::add)
            }
        }
        obj("mediaMetadata")?.array("audioModes")?.let { arr ->
            for (item in arr) {
                item.jsonPrimitiveOrNull?.content?.takeIf { it.isNotBlank() }?.let(modes::add)
            }
        }
        return modes
    }

    private fun MatchedTrack.toProviderTrack(): ProviderTrack {
        val cover = albumCoverId?.replace('-', '/')?.let { "https://resources.tidal.com/images/$it/640x640.jpg" }
        return ProviderTrack(
            ContentItem(
                ContentRef(ContentProvider.TIDAL.id, ContentKind.SONG, trackId),
                title,
                artistNames.joinToString(", "),
                cover,
                durationMs,
                false,
            ),
            artistNames,
            album,
            isrc,
            rawJson,
        )
    }

    private fun matchedTrackPlaceholder(trackId: String): ProviderTrack =
        MatchedTrack(
            trackId = trackId,
            title = "",
            artistNames = emptyList(),
            album = null,
            isrc = null,
            durationMs = null,
            audioQuality = null,
            mediaTags = emptySet(),
            audioModes = emptySet(),
            sampleRate = null,
        ).toProviderTrack()
    // endregion

    // region normalization helpers
    private fun String.toTidalTrackIdOrNull(): String? {
        val trimmed = trim()
        if (trimmed.matches(NUMERIC_ONLY_REGEX)) return trimmed
        TIDAL_URI_TRACK_ID_REGEX.matchEntire(trimmed)?.groupValues?.getOrNull(1)?.let { return it }
        TIDAL_URL_TRACK_ID_REGEX.find(trimmed)?.groupValues?.getOrNull(1)?.let { return it }
        return null
    }

    private fun String.trackCacheKeyFallback(): String = "direct:${trim().lowercase()}"

    private fun Query.trackCacheKey(): String =
        mediaId.toTidalTrackIdOrNull()
            ?.let { "direct:$it" }
            ?: listOf(
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

    private fun String.searchQueryTitle(): String =
        trim()
            .replace(FEATURED_ARTIST_BRACKET_REGEX, "")
            .replace(EDITION_SUFFIX_REGEX, "")
            .replace(WHITESPACE_REGEX, " ")
            .trim()

    private fun String.searchQueryArtist(): String =
        trim().substringBefore(',').replace(WHITESPACE_REGEX, " ").trim()

    private fun significantTokens(value: String): Set<String> =
        value.split(' ').map { it.trim() }.filter { it.length >= 2 && it !in STOP_WORDS }.toSet()

    private fun tokenOverlap(
        wanted: Set<String>,
        candidate: Set<String>,
    ): Double {
        if (wanted.isEmpty() || candidate.isEmpty()) return 0.0
        val shared = wanted.intersect(candidate).size
        return shared.toDouble() / wanted.size.coerceAtLeast(candidate.size).toDouble()
    }

    private fun hasVersionMismatch(
        wanted: String,
        candidate: String,
    ): Boolean {
        val wantedLive = wanted.contains(" live ")
        val candidateLive = candidate.contains(" live ")
        if (wantedLive != candidateLive) return true
        val wantedInstrumental = wanted.contains(" instrumental ")
        val candidateInstrumental = candidate.contains(" instrumental ")
        if (wantedInstrumental != candidateInstrumental) return true
        return VERSION_TOKENS.any { token -> wanted.hasToken(token) != candidate.hasToken(token) }
    }

    private fun String.hasToken(token: String): Boolean = split(' ').any { it == token }
    // endregion

    // region queued playback (hifi-api 202 protocol)
    /** Header seconds, else the body's retry_after, else the cooldown; shared by direct and polled 429s. */
    private fun rateLimitRetryAfterMs(reply: Reply): Long {
        val headerRetryAfterMs = reply.retryAfter?.toLongOrNull()?.times(1000L)?.coerceAtLeast(60_000L)
        val bodyRetryAfterMs =
            runCatching {
                Json.parseToJsonElement(reply.body).jsonObjectOrNull
                    ?.long("retry_after")
                    ?.takeIf { it > 0L }
                    ?.times(1000L)
            }.getOrNull()
        return headerRetryAfterMs ?: bodyRetryAfterMs ?: RATE_LIMIT_COOLDOWN_MS
    }

    /**
     * Waits out a 202 ticket and returns the completed playback body. The status URL reports on the
     * queued request and finishes with the payload a direct 200 would carry. Every exit that leaves the
     * ticket open cancels it best-effort, including cancellation by the race, so the account's single
     * in-flight slot is released rather than held until the server expires the record.
     */
    private suspend fun awaitQueuedPlayback(
        endpoint: TidalDownloadEndpoint,
        accepted: Reply,
        deadlineMs: Long,
    ): String {
        val acceptedJson = runCatching { Json.parseToJsonElement(accepted.body).jsonObjectOrNull }.getOrNull()
        val statusUrl =
            TidalQueuedPlayback.requestUrl(endpoint.baseUrl, acceptedJson?.stringOrNull("statusUrl"))
                ?: TidalQueuedPlayback.requestUrl(endpoint.baseUrl, accepted.location)
        val cancelTarget =
            TidalQueuedPlayback.requestUrl(endpoint.baseUrl, acceptedJson?.stringOrNull("cancelUrl")) ?: statusUrl
        var settled = false
        try {
            val pollUrl =
                statusUrl ?: throw TidalAudioResolutionException(
                    "TIDAL ${endpoint.name} queued playback has no usable status URL",
                )
            var waitMs = TidalQueuedPlayback.pollDelayMs(accepted.retryAfter, nowMs())
            while (true) {
                // A poll that would land at or after the deadline cannot help, so give up and cancel now.
                if (nowMs() + waitMs >= deadlineMs) throw queuedDeadlineException(endpoint)
                delay(waitMs)
                val remainingMs = deadlineMs - nowMs()
                if (remainingMs <= 0L) throw queuedDeadlineException(endpoint)
                val poll = streamGet(pollUrl, minOf(STREAM_REQUEST_TIMEOUT_MS, remainingMs))
                if (poll.status == 429) {
                    val retryAfterMs = rateLimitRetryAfterMs(poll)
                    markInstanceRateLimited(endpoint.baseUrl, retryAfterMs)
                    throw TidalRateLimitedException(retryAfterMs)
                }
                if (poll.status == 202) {
                    waitMs = TidalQueuedPlayback.pollDelayMs(poll.retryAfter, nowMs())
                    continue
                }
                if (poll.status == 404 || poll.status == 410) {
                    // The server has already dropped the record, so a cancel would only add noise.
                    settled = true
                    throw TidalAudioResolutionException(
                        "TIDAL ${endpoint.name} queued playback expired (HTTP ${poll.status})",
                    )
                }
                if (poll.status !in 200..299) {
                    throw TidalAudioResolutionException(
                        "TIDAL ${endpoint.name} queued playback HTTP ${poll.status}: ${poll.body.take(180)}",
                    )
                }
                val pollJson = runCatching { Json.parseToJsonElement(poll.body).jsonObjectOrNull }.getOrNull()
                if (pollJson?.obj("data") != null) {
                    settled = true
                    return poll.body
                }
                val state = pollJson?.stringOrNull("status")
                if (TidalQueuedPlayback.isFailedState(state)) {
                    settled = true
                    throw TidalAudioResolutionException("TIDAL ${endpoint.name} queued playback ended: $state")
                }
                waitMs = TidalQueuedPlayback.pollDelayMs(poll.retryAfter, nowMs())
            }
        } finally {
            if (!settled && cancelTarget != null) cancelQueuedPlayback(cancelTarget)
        }
    }

    private fun queuedDeadlineException(endpoint: TidalDownloadEndpoint): TidalAudioResolutionException =
        TidalAudioResolutionException("TIDAL ${endpoint.name} queued playback did not finish before the attempt deadline")

    /** Fire-and-forget on [cancelScope]: the race must not wait for it, and a failed cancel changes nothing. */
    private fun cancelQueuedPlayback(cancelUrl: String) {
        cancelScope.launch {
            runCatching {
                exchange(client, cancelUrl, QUEUED_CANCEL_TIMEOUT_MS, emptyList(), emptyMap(), HttpMethod.Delete)
            }.onFailure { failure ->
                // Best effort: a ticket that cannot be cancelled is left for the server to expire.
                if (failure is CancellationException) throw failure
            }
        }
    }

    /** A rate limit is not a hard failure; the instance is only skipped for the length of its Retry-After. */
    private suspend fun markInstanceRateLimited(
        baseUrl: String,
        retryAfterMs: Long,
    ) {
        stateMutex.withLock {
            instanceCooldownUntilMs[baseUrl] = maxOf(instanceCooldownUntilMs[baseUrl] ?: 0L, nowMs() + retryAfterMs)
        }
    }
    // endregion

    // region HTTP plumbing
    private class Reply(
        val status: Int,
        val body: String,
        val retryAfter: String?,
        val contentType: String?,
        val contentLength: Long?,
        val contentRange: String?,
        val location: String? = null,
    )

    private class TidalTimeoutException : Exception("TIDAL request timed out")
    private class TidalConnectException : Exception("TIDAL host unreachable")

    private fun Throwable.isHardTransportError(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            val name = current::class.simpleName.orEmpty()
            if (
                name.contains("UnknownHost") ||
                name.contains("UnresolvedAddress") ||
                name.contains("ConnectException") ||
                name.contains("Connect") && name.contains("Exception") ||
                current is TidalConnectException
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private suspend fun healthGet(
        url: String,
        timeoutMs: Long,
        params: List<Pair<String, String>> = emptyList(),
        headers: Map<String, String> = emptyMap(),
    ): Reply = exchange(healthClient, url, timeoutMs, params, headers, HttpMethod.Get)

    private suspend fun streamGet(
        url: String,
        timeoutMs: Long,
        params: List<Pair<String, String>> = emptyList(),
        headers: Map<String, String> = emptyMap(),
    ): Reply = exchange(client, url, timeoutMs, params, headers, HttpMethod.Get)

    private suspend fun headGet(
        url: String,
        timeoutMs: Long,
        headers: Map<String, String>,
    ): Reply = exchange(client, url, timeoutMs, emptyList(), headers, HttpMethod.Head)

    private suspend fun exchange(
        http: HttpClient,
        url: String,
        timeoutMs: Long,
        params: List<Pair<String, String>>,
        headers: Map<String, String>,
        method: HttpMethod,
    ): Reply =
        withTimeoutOrNull(timeoutMs) {
            try {
                http.prepareRequest(url) {
                    this.method = method
                    header(HttpHeaders.Accept, "application/json")
                    if (headers.keys.none { it.equals(HttpHeaders.UserAgent, true) }) {
                        header(HttpHeaders.UserAgent, DOWNLOAD_USER_AGENT)
                    }
                    params.forEach { (key, value) -> parameter(key, value) }
                    headers.forEach { (key, value) -> header(key, value) }
                }.execute { response ->
                    val body =
                        if (method == HttpMethod.Head) "" else response.bodyAsText()
                    Reply(
                        status = response.status.value,
                        body = body,
                        retryAfter = response.headers[HttpHeaders.RetryAfter],
                        contentType = response.headers[HttpHeaders.ContentType],
                        contentLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull(),
                        contentRange = response.headers[HttpHeaders.ContentRange],
                        location = response.headers[HttpHeaders.Location],
                    )
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                if (error.isHardTransportError()) throw TidalConnectException()
                throw TidalAudioResolutionException("TIDAL request failed: ${error::class.simpleName}", error)
            }
        } ?: throw TidalTimeoutException()
    // endregion

    // region JSON helpers
    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }

    private fun JsonObject.longOrNull(key: String): Long? =
        if (containsKey(key)) (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull?.takeIf { it > 0L } else null

    private fun JsonObject.doubleOrNull(key: String): Double? =
        if (containsKey(key)) {
            (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.doubleOrNull?.takeIf { it > 0.0 && !it.isNaN() }
        } else {
            null
        }

    private val kotlinx.serialization.json.JsonElement.jsonObjectOrNull: JsonObject?
        get() = this as? JsonObject

    private val kotlinx.serialization.json.JsonElement.jsonPrimitiveOrNull: kotlinx.serialization.json.JsonPrimitive?
        get() = this as? kotlinx.serialization.json.JsonPrimitive

    private fun JsonArray.firstStringOrNull(): String? {
        for (element in this) {
            val value = element.jsonPrimitiveOrNull?.content?.takeIf { it.isNotBlank() }
            if (value != null) return value
        }
        return null
    }
    // endregion

    // region XML helpers
    private fun firstXmlAttr(
        text: String,
        attr: String,
    ): String =
        Regex("""\b${Regex.escape(attr)}\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()

    private fun xmlAttrValues(
        text: String,
        attr: String,
    ): List<Int> =
        Regex("""\b${Regex.escape(attr)}\s*=\s*"(\d+)"""", RegexOption.IGNORE_CASE)
            .findAll(text)
            .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
            .toList()

    private fun String.xmlUnescape(): String =
        replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")

    private fun String.sanitizeXmlEntities(): String = replace(XML_UNESCAPED_AMPERSAND_REGEX, "&amp;")
    // endregion

    // region transport-error classification hook
    private fun parseAmazonDateMs(value: String): Long {
        // X-Amz-Date "yyyyMMdd'T'HHmmss'Z'" — fixed-width parse to keep common code date-lib free.
        require(value.length >= 16)
        val year = value.substring(0, 4).toInt()
        val month = value.substring(4, 6).toInt()
        val day = value.substring(6, 8).toInt()
        val hour = value.substring(9, 11).toInt()
        val minute = value.substring(11, 13).toInt()
        val second = value.substring(13, 15).toInt()
        val epochDay = daysFromCivil(year, month, day)
        return (epochDay * 86_400L + hour * 3600L + minute * 60L + second) * 1000L
    }

    private fun daysFromCivil(
        y0: Int,
        m: Int,
        d: Int,
    ): Long {
        var y = y0
        if (m <= 2) y -= 1
        val era = if (y >= 0) y else y - 399
        val eraYears = era / 400
        val yoe = y - eraYears * 400
        val mp = (m + 9) % 12
        val doy = (153 * mp + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return (eraYears * 146097L + doe - 719468L)
    }
    // endregion

    private companion object {
        internal const val PROGRESSIVE_DASH_SCHEME = "tidal-dash"
        private const val API_BASE_URL = "https://tidal.com/v1"
        private const val SONG_LINK_API_URL = "https://api.song.link/v1-alpha.1/links"
        private const val PUBLIC_TOKEN = "49YxDN9a2aFV6RTG"
        private const val COUNTRY_CODE = "US"
        private const val LOCALE = "en_US"
        private const val DEVICE_TYPE = "BROWSER"
        private const val DOWNLOAD_USER_AGENT = "ArchiveTune-Android"
        private const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/135.0.0.0 Mobile Safari/537.36"
        private const val AUDIO_MP4_MIME_TYPE = "audio/mp4"
        private const val AUDIO_FLAC_MIME_TYPE = "audio/flac"
        private const val STREAM_CACHE_MS = 45 * 60 * 1000L
        private const val TRACK_CACHE_MS = 60 * 60 * 1000L
        private const val STREAM_FAILURE_CACHE_MS = 15 * 60 * 1000L
        private const val TRACK_STREAM_FAILURE_CACHE_MS = 60 * 60 * 1000L
        private const val RATE_LIMIT_COOLDOWN_MS = 8 * 60 * 1000L
        private const val SEARCH_LIMIT = 8
        private const val SEARCH_CACHE_MS = 10 * 60 * 1000L
        private const val MAX_STREAM_CANDIDATES = 2
        private const val MAX_DIRECT_STREAM_CANDIDATES = 3
        private const val MAX_CONCURRENT_TIDAL_ENDPOINTS = 8
        private const val MIN_MATCH_SCORE = 90
        private const val PROVIDER_ATTEMPT_MS = 8_000L
        private const val STREAM_REQUEST_TIMEOUT_MS = 25_000L
        private const val QUEUED_CANCEL_TIMEOUT_MS = 5_000L
        private const val HEALTH_TIMEOUT_MS = 6_000L
        private const val INSTANCE_SOFT_COOLDOWN_MS = 60_000L
        private const val INSTANCE_HARD_COOLDOWN_MS = 600_000L
        private const val STRONG_MATCH_SCORE = 150
        private const val REJECT_SCORE = -1_000_000

        private val ISRC_STRIP_REGEX = Regex("[^A-Z0-9]")
        private val ISRC_PATTERN_REGEX = Regex("[A-Z]{2}[A-Z0-9]{3}[0-9]{7}")
        private val DIACRITIC_REGEX = Regex("\\p{Mn}+")
        private val NON_ALPHANUMERIC_REGEX = Regex("[^\\p{L}\\p{N}]+")
        private val WHITESPACE_REGEX = Regex("\\s+")
        private val FEATURED_ARTIST_TITLE_SUFFIX_REGEX = Regex("""\b(feat|ft|featuring)\b.*$""")
        private val EDITION_NOISE_WORD_REGEX = Regex("""\b(explicit|clean|remaster|remastered|version|audio|official)\b""")
        private val FEATURED_ARTIST_BRACKET_REGEX =
            Regex("""\s*[\[(]\s*(feat\.?|ft\.?|featuring)\b.*?[\])]""", RegexOption.IGNORE_CASE)
        private val EDITION_SUFFIX_REGEX =
            Regex("""\s*-\s*(explicit|clean|remaster(?:ed)?|audio|official)\b.*$""", RegexOption.IGNORE_CASE)
        private val NUMERIC_ONLY_REGEX = Regex("\\d+")
        private val TIDAL_URI_TRACK_ID_REGEX = Regex("""^tidal:track:(\d+)$""", RegexOption.IGNORE_CASE)
        private val TIDAL_URL_TRACK_ID_REGEX = Regex("""tidal\.com/(?:browse/)?track/(\d+)""", RegexOption.IGNORE_CASE)
        private val SPOTIFY_URI_TRACK_ID_REGEX = Regex("""spotify[:/](?:track[:/])?([A-Za-z0-9]{22})""", RegexOption.IGNORE_CASE)
        private val SPOTIFY_ID_ONLY_REGEX = Regex("[A-Za-z0-9]{22}")
        private val YOUTUBE_ID_ONLY_REGEX = Regex("[A-Za-z0-9_-]{11}")
        private val HTTP_URL_REGEX = Regex("""https?://[^"'<>\s]+""")
        private val XML_UNESCAPED_AMPERSAND_REGEX = Regex("""&(?!amp;|lt;|gt;|quot;|apos;|#\d+;|#x[0-9a-fA-F]+;)""")

        private val STOP_WORDS =
            setOf(
                "the", "and", "feat", "ft", "with", "remaster", "remastered", "version", "explicit",
                "clean", "audio", "official",
            )

        private val VERSION_TOKENS =
            setOf("remix", "mix", "edit", "acoustic", "sped", "slowed", "nightcore", "karaoke")
    }
}
