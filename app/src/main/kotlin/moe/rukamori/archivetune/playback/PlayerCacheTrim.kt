package moe.rukamori.archivetune.playback

import android.content.Context
import androidx.media3.datasource.cache.Cache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.extensions.directorySizeBytes
import moe.rukamori.archivetune.storage.StorageFolderKind
import moe.rukamori.archivetune.storage.StorageLocationRepository

/**
 * Evicts the oldest-touched cached spans until the player cache fits [limitBytes]. Candidates are
 * ordered by oldest span touch so the freshest keys survive; cache metadata is read best-effort,
 * so a cache mid-write simply contributes nothing to the sweep.
 */
internal suspend fun trimPlayerCacheToBytes(context: Context, playerCache: Cache, limitBytes: Long) {
    if (limitBytes <= 0L) return

    withContext(Dispatchers.IO) {
        val cacheDir = StorageLocationRepository.cacheDirectory(context, StorageFolderKind.SONG_CACHE)
        val currentSpace = runCatching { playerCache.cacheSpace }.getOrNull() ?: 0L
        var totalBytes = if (currentSpace > 0L) currentSpace else cacheDir.directorySizeBytes()
        if (totalBytes <= limitBytes) return@withContext

        data class Candidate(
            val key: String,
            val lastTouchTimestamp: Long,
            val sizeBytes: Long,
        )

        val candidates =
            runCatching {
                playerCache.keys
                    .mapNotNull { key ->
                        runCatching {
                            val spans = playerCache.getCachedSpans(key)
                            if (spans.isEmpty()) return@runCatching null
                            val oldestTouch = spans.minOf { it.lastTouchTimestamp }
                            val sizeBytes = spans.sumOf { it.length }
                            Candidate(key = key, lastTouchTimestamp = oldestTouch, sizeBytes = sizeBytes)
                        }.getOrNull()
                    }.sortedBy { it.lastTouchTimestamp }
            }.getOrNull().orEmpty()

        for (candidate in candidates) {
            if (totalBytes <= limitBytes) break
            val removedSize = candidate.sizeBytes.coerceAtLeast(0L)
            runCatching { playerCache.removeResource(candidate.key) }
            totalBytes -= removedSize
        }
    }
}
