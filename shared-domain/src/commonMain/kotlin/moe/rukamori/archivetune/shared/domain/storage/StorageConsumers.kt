package moe.rukamori.archivetune.shared.domain.storage

import kotlinx.coroutines.flow.Flow

/** The areas the storage settings surface measures, matching the cache kinds the screen lists. */
enum class StorageArea { SONG_CACHE, IMAGE_CACHE, CANVAS_CACHE, LYRICS_CACHE, DOWNLOADS, STAGING }

/**
 * The storage settings consumers, the portable form of the retained storage screen's behavior: report used bytes
 * per area, clear an area's cached files (never downloads — those need an explicit remove), evict down to the
 * configured byte caps oldest-last-used first, and move the download/cache root to a picked folder.
 */
interface StorageConsumers {
    /** Live used bytes per area; each host recomputes on demand so the row is never stale. */
    suspend fun usedBytes(): Map<StorageArea, Long>
    /** Observed used bytes per area for surfaces that want a live row. */
    fun observeUsedBytes(): Flow<Map<StorageArea, Long>>
    /** Deletes every cached file of [area]; downloads are never a cache clear target. */
    suspend fun clearCache(area: StorageArea)
    /** Trims each cache area to [limits] bytes, evicting least-recently-modified files first, like LRU eviction. */
    suspend fun applyCacheLimits(limits: Map<StorageArea, Long>)
    /** The label the storage row shows for the current root. */
    suspend fun currentStorageLabel(): String?
    /**
     * Moves the download and cache areas into [newRoot] and returns the label the row now shows, or null when the
     * move did not complete. The original returns a restart token for a SAF move; a host that can migrate in place
     * returns its new label directly.
     */
    suspend fun commitStorageFolder(newRoot: String): String?
}
