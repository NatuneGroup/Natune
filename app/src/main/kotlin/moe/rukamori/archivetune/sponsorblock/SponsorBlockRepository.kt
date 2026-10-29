/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package moe.rukamori.archivetune.sponsorblock

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import moe.rukamori.archivetune.constants.SponsorBlockApiUrlKey
import moe.rukamori.archivetune.constants.SponsorBlockCategoriesKey
import moe.rukamori.archivetune.constants.SponsorBlockEnabledKey
import moe.rukamori.archivetune.constants.SponsorBlockPodcastsEnabledKey
import moe.rukamori.archivetune.uploads.YouTubeUploadedVideoIdsKey
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.isLocalMediaId
import moe.rukamori.archivetune.shared.domain.playback.SponsorBlockPlaybackPolicy
import timber.log.Timber
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import moe.rukamori.archivetune.utils.withAppProxy

/** Fetches skip segments from a SponsorBlock instance. */
@Singleton
class SponsorBlockRepository
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

        private val client by lazy {
            HttpClient(OkHttp) {
                engine {
                    config {
                        withAppProxy()
                    }
                }
                install(ContentNegotiation) { json(json) }
                install(HttpTimeout) {
                    connectTimeoutMillis = 8_000
                    requestTimeoutMillis = 12_000
                    socketTimeoutMillis = 12_000
                }
                expectSuccess = false
            }
        }

        // Access-ordered so the map evicts what has gone longest unread rather than what was added
        // first: a queue that loops back to an earlier track should still find its segments.
        private val cache =
            object : LinkedHashMap<String, List<SponsorBlockSegment>>(16, 0.75f, true) {
                override fun removeEldestEntry(eldest: Map.Entry<String, List<SponsorBlockSegment>>) = size > CACHE_ENTRIES
            }

        suspend fun settings(): SponsorBlockSettings = observeSettings().first()

        fun observeSettings(): Flow<SponsorBlockSettings> = context.dataStore.data.map { sponsorBlockSettingsFrom(it) }

        /**
         * Segments for [videoId], or an empty list when the feature is off, the id is not a YouTube
         * one, the id is one of the account's own uploads, the item is a podcast episode while
         * podcast skipping is off, or the lookup fails. Never fails playback: a segment list is an
         * enhancement. Coroutine cancellation still propagates, so disabling a setting can stop an
         * in-flight lookup.
         */
        suspend fun segments(
            videoId: String,
            podcast: Boolean = false,
        ): List<SponsorBlockSegment> {
            if (!YOUTUBE_ID.matches(videoId)) return emptyList()
            // One snapshot for both gates: an upload exists only in the signed-in account, so no
            // public segment database can know it.
            val preferences = context.dataStore.data.first()
            if (videoId in preferences[YouTubeUploadedVideoIdsKey].orEmpty()) return emptyList()
            val settings = sponsorBlockSettingsFrom(preferences)
            if (settings.categories.isEmpty() || !SponsorBlockPlaybackPolicy.allowsLookup(
                    videoId, settings.enabled, settings.podcastsEnabled, podcast, videoId.isLocalMediaId(),
                )) return emptyList()

            val key = "${settings.apiUrl}|${settings.categories.sortedBy { it.apiName }.joinToString(",") { it.apiName }}|$videoId"
            synchronized(cache) { cache[key] }?.let { return it }

            val segments = try {
                fetch(videoId, settings)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Timber.tag(TAG).w(failure, "SponsorBlock lookup failed for %s", videoId)
                emptyList()
            }

            synchronized(cache) { cache[key] = segments }
            return segments
        }

        private suspend fun fetch(
            videoId: String,
            settings: SponsorBlockSettings,
        ): List<SponsorBlockSegment> {
            val prefix = sha256Prefix(videoId)
            val response: HttpResponse =
                client.get("${settings.apiUrl}/api/skipSegments/$prefix") {
                    settings.categories.forEach { parameter("category", it.apiName) }
                }
            // 404 is how the service says "nothing published for this prefix", which is a normal
            // answer rather than a failure.
            if (response.status == HttpStatusCode.NotFound) return emptyList()
            if (!response.status.isSuccess()) return emptyList()

            val payload = response.body<List<SponsorBlockPrefixResult>>()
            val raw =
                payload
                    .firstOrNull { it.videoID == videoId }
                    ?.segments
                    .orEmpty()

            return raw
                .asSequence()
                .filter { it.actionType == "skip" }
                .filter { it.segment.size == 2 }
                .mapNotNull { api ->
                    val category = SponsorBlockCategory.fromApiName(api.category) ?: return@mapNotNull null
                    if (category !in settings.categories) return@mapNotNull null
                    val startMs = (api.segment[0] * 1000.0).toLong()
                    val endMs = (api.segment[1] * 1000.0).toLong()
                    if (endMs <= startMs) return@mapNotNull null
                    SponsorBlockSegment(category, startMs, endMs)
                }.sortedBy { it.startMs }
                .fold(mutableListOf<SponsorBlockSegment>()) { merged, segment ->
                    // Overlapping segments would otherwise mean a seek out of one landing inside the
                    // next, which reads as a stutter rather than a single skip.
                    val last = merged.lastOrNull()
                    if (last != null && segment.startMs <= last.endMs) {
                        merged[merged.lastIndex] = last.copy(endMs = maxOf(last.endMs, segment.endMs))
                    } else {
                        merged += segment
                    }
                    merged
                }
        }

        private fun sha256Prefix(videoId: String): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(videoId.toByteArray())
                .joinToString("") { "%02x".format(it) }
                .take(HASH_PREFIX_LENGTH)

        private companion object {
            const val TAG = "SponsorBlock"
            const val CACHE_ENTRIES = 64
            const val HASH_PREFIX_LENGTH = 4
            val YOUTUBE_ID = Regex("^[A-Za-z0-9_-]{11}$")
        }
    }

/** Settings snapshot; observed live by the controller and rechecked before every lookup, including cache hits. */
data class SponsorBlockSettings(
    val enabled: Boolean,
    val categories: Set<SponsorBlockCategory>,
    val apiUrl: String,
    val podcastsEnabled: Boolean = false,
)

internal fun sponsorBlockSettingsFrom(preferences: Preferences): SponsorBlockSettings {
    val rawOrigin = preferences[SponsorBlockApiUrlKey]
    val origin = normalizeSponsorBlockApiUrl(rawOrigin)
    // Only an unset/blank value selects the public server; invalid saved origins refuse lookup.
    val originAllowed = rawOrigin.isNullOrBlank() || origin != null
    return SponsorBlockSettings(
        enabled = (preferences[SponsorBlockEnabledKey] ?: false) && originAllowed,
        categories = preferences[SponsorBlockCategoriesKey]
            ?.mapNotNull(SponsorBlockCategory::fromApiName)?.toSet() ?: SponsorBlockCategory.Defaults,
        apiUrl = origin ?: SPONSORBLOCK_DEFAULT_API_URL,
        podcastsEnabled = preferences[SponsorBlockPodcastsEnabledKey] ?: false,
    )
}

@kotlinx.serialization.Serializable
internal data class SponsorBlockPrefixResult(
    val videoID: String = "",
    val segments: List<SponsorBlockApiSegment> = emptyList(),
)
