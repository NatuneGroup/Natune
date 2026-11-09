package moe.rukamori.archivetune.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.ContentMetadata
import moe.rukamori.archivetune.audiosource.PlaybackSourceChain
import moe.rukamori.archivetune.audiosource.PlaybackSourceSnapshot
import moe.rukamori.archivetune.constants.AudioSourceType

internal data class CachedPlaybackSelection(
    val key: String,
    val window: CachedReadWindow,
    val spotifyCacheTag: String?,
    val includePlayerCache: Boolean,
)

/** The resolver and offline browse eligibility use the same source gates and byte coverage. */
internal class CachedPlaybackLookup(
    private val recordedLength: (String, Boolean) -> Long?,
    private val continuousLength: (String, CachedReadWindow, Boolean) -> Long,
    private val completeDownload: (String) -> Boolean,
) {
    fun find(
        mediaId: String,
        position: Long,
        requestedLength: Long,
        includePlayerCache: Boolean,
        youtubeItag: Int?,
        formatContentLength: Long?,
        prefs: PlaybackSourceSnapshot,
        authenticityRejected: Set<AudioSourceType>,
        transportRejected: Set<AudioSourceType>,
        sourceOf: (String) -> AudioSourceType?,
        spotifyTagOf: (String) -> String?,
        spotifyCacheAllowed: (String?) -> Boolean,
        legacyContentLength: Long? = null,
    ): CachedPlaybackSelection? {
        val itagKey = PlaybackSourceChain.itagCacheKey(mediaId, youtubeItag)
        return PlaybackSourceChain.cacheKeyOrder(prefs, mediaId, youtubeItag).firstNotNullOfOrNull { key ->
            val source = sourceOf(key)
            val spotifyTag = if (source == AudioSourceType.SPOTIFY) spotifyTagOf(key) else null
            if (source == AudioSourceType.SPOTIFY && !spotifyCacheAllowed(spotifyTag)) return@firstNotNullOfOrNull null
            if (source != null && source in authenticityRejected) return@firstNotNullOfOrNull null
            val rejectedTransport = source != null && source in transportRejected
            // A rejected URL may still have a complete download; partial old bytes must not
            // be stitched into a refreshed stream, nor read from the ephemeral player cache.
            if (rejectedTransport && !completeDownload(key)) return@firstNotNullOfOrNull null
            val usePlayerCache = includePlayerCache && !rejectedTransport
            val length = recordedLength(key, usePlayerCache)
            val window = resolveCachedReadWindow(
                position,
                requestedLength,
                if (requestedLength >= 0L) null
                else if (key == itagKey) PlaybackSourceChain.itagCoverageLength(length, formatContentLength)
                else if (key == mediaId) length ?: legacyContentLength
                else length,
            ) ?: return@firstNotNullOfOrNull null
            if (continuousLength(key, window, usePlayerCache) < window.length) return@firstNotNullOfOrNull null
            CachedPlaybackSelection(key, window, spotifyTag, usePlayerCache)
        }
    }

    companion object {
        @UnstableApi
        fun fromCaches(downloadCache: Cache, playerCache: Cache): CachedPlaybackLookup {
            fun caches(includePlayerCache: Boolean) =
                if (includePlayerCache) listOf(downloadCache, playerCache) else listOf(downloadCache)

            fun length(cache: Cache, key: String): Long? =
                runCatching { cache.getContentMetadata(key).get(ContentMetadata.KEY_CONTENT_LENGTH, -1L) }
                    .getOrNull()?.takeIf { it > 0L }

            fun spans(cache: Cache, key: String): List<CachedSpan> =
                runCatching { cache.getCachedSpans(key).toList() }.getOrNull().orEmpty()
                    .map { CachedSpan(it.position, it.length, it.isCached) }

            return CachedPlaybackLookup(
                recordedLength = { key, includePlayerCache -> caches(includePlayerCache).mapNotNull { length(it, key) }.maxOrNull() },
                continuousLength = { key, window, includePlayerCache ->
                    continuousCachedLength(caches(includePlayerCache).flatMap { spans(it, key) }, window.position, window.length)
                },
                completeDownload = { key ->
                    runCatching { length(downloadCache, key)?.let { downloadCache.isCached(key, 0L, it) } == true }.getOrDefault(false)
                },
            )
        }
    }
}
