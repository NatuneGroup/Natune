package moe.rukamori.archivetune.shared.data.stats

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import moe.rukamori.archivetune.shared.data.persistence.PortableStoredSongDao
import moe.rukamori.archivetune.shared.data.persistence.StoredSongOrigin
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType

/** Stored-song counts per audio source, the feed the statistics and recap surfaces read for download/cache rows. */
data class StoredSourceCount(val source: AudioSourceType, val songs: Int, val bytes: Long)

data class StoredSongCounts(
    val downloadsBySource: List<StoredSourceCount>,
    val cacheBySource: List<StoredSourceCount>,
) {
    val downloadedSongs: Int get() = downloadsBySource.sumOf { it.songs }
    val downloadedBytes: Long get() = downloadsBySource.sumOf { it.bytes }
    val cachedSongs: Int get() = cacheBySource.sumOf { it.songs }
    val cachedBytes: Long get() = cacheBySource.sumOf { it.bytes }
}

/**
 * Aggregates the stored-song index into the counts stats needs, over the same rows the download and cache lists
 * read — never a second count that could disagree with them.
 */
class StoredSongStats(private val dao: PortableStoredSongDao) {
    fun observe(): Flow<StoredSongCounts> =
        combine(dao.observe(StoredSongOrigin.DOWNLOAD.name), dao.observe(StoredSongOrigin.CACHE.name)) { d, c ->
            StoredSongCounts(countBySource(d), countBySource(c))
        }

    fun observeDownloads(): Flow<List<StoredSourceCount>> =
        dao.observe(StoredSongOrigin.DOWNLOAD.name).map(::countBySource)

    private fun countBySource(rows: List<moe.rukamori.archivetune.shared.data.persistence.PortableStoredSongRow>) =
        rows.mapNotNull { row -> runCatching { AudioSourceType.valueOf(row.source) }.getOrNull()?.let { it to row } }
            .groupBy({ it.first }, { it.second })
            .map { (source, group) ->
                StoredSourceCount(source, group.size, group.sumOf { it.bytes ?: 0L })
            }
            .sortedBy { it.source.ordinal }
}
