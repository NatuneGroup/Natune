package moe.rukamori.archivetune.playback

import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.offline.Download
import kotlinx.coroutines.flow.first
import moe.rukamori.archivetune.constants.DownloadSourceConfig
import moe.rukamori.archivetune.db.entities.Song
import moe.rukamori.archivetune.extensions.toMediaItem
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.utils.isLocalMediaId
import java.util.Locale

internal data class OfflineSongSearchResult(
    val items: List<MediaItem>,
    val count: Int,
)

internal suspend fun MediaLibrarySessionCallback.searchOfflineSongs(
    query: String,
    previewSize: Int,
): OfflineSongSearchResult {
    if (query.isBlank() || previewSize <= 0) {
        return OfflineSongSearchResult(
            items = emptyList(),
            count = 0,
        )
    }

    // Filter before the preview limit: online matches must not hide later offline matches or inflate the count.
    val libraryCount = database.searchSongsCount(query)
    val librarySongs = autoOfflineSongs.filter(database.searchSongs(query,
        previewSize = if (autoOfflineSongs.isOffline()) libraryCount.coerceAtLeast(1) else previewSize).first())
    val libraryIds = librarySongs.mapTo(HashSet(librarySongs.size)) { it.id }
    val cachedOnlySongs = searchCachedOnlySongs(query, excludeIds = libraryIds)

    return OfflineSongSearchResult(
        items =
            interleaveMediaItems(
                first = librarySongs.map { it.toMediaItem(MusicService.SONG) },
                second = cachedOnlySongs.take(previewSize).map { it.toMediaItem() },
            ).take(previewSize),
        count = (if (autoOfflineSongs.isOffline()) librarySongs.size else libraryCount) + cachedOnlySongs.size,
    )
}

private suspend fun MediaLibrarySessionCallback.searchCachedOnlySongs(
    query: String,
    excludeIds: Set<String> = emptySet(),
): List<Song> {
    val cachedIds = cachedSongIds()
    if (cachedIds.isEmpty()) return emptyList()

    val normalizedQuery = query.lowercase(Locale.getDefault())
    return autoOfflineSongs.filter(cachedIds.chunked(SONG_ID_QUERY_BATCH_SIZE).flatMap { database.getSongsByIds(it) })
        .asSequence()
        .filter { it.song.inLibrary == null }
        .filterNot { it.id in excludeIds }
        .filter {
            it.song.title
                .lowercase(Locale.getDefault())
                .contains(normalizedQuery)
        }.sortedBy { it.song.title.lowercase(Locale.getDefault()) }
        .toList()
}

private fun MediaLibrarySessionCallback.cachedSongIds(): List<String> {
    val completedDownloadIds =
        downloadUtil.downloads.value
            .asSequence()
            .filter { (_, download) -> download.state == Download.STATE_COMPLETED }
            .map { (id, _) -> id }
    val downloadCacheIds =
        runCatching { downloadUtil.downloadCache.keys.asSequence() }
            .getOrDefault(emptySequence())
    val playerCacheIds =
        runCatching { downloadUtil.playerCache.keys.asSequence() }
            .getOrDefault(emptySequence())

    return sequenceOf(completedDownloadIds, downloadCacheIds, playerCacheIds)
        .flatten()
        .map(String::trim)
        .filter(String::isNotBlank)
        .map(::cachedSongMediaId)
        .distinct()
        .toList()
}

internal fun interleaveMediaItems(
    first: List<MediaItem>,
    second: List<MediaItem>,
): List<MediaItem> {
    if (first.isEmpty()) return second
    if (second.isEmpty()) return first

    val merged = ArrayList<MediaItem>(first.size + second.size)
    val maxSize = maxOf(first.size, second.size)
    repeat(maxSize) { index ->
        first.getOrNull(index)?.let(merged::add)
        second.getOrNull(index)?.let(merged::add)
    }
    return merged
}

internal suspend fun MediaLibrarySessionCallback.resolveVoiceMediaItems(
    query: String,
    previewSize: Int = 50,
): List<MediaItem> {
    val q = query.trim()
    if (q.isBlank()) return emptyList()
    val offlineSongs = searchOfflineSongs(q, previewSize)
    val existingIds = offlineSongs.items.mapTo(HashSet(offlineSongs.items.size * 2), ::searchSongIdentity)
    val onlineSongs =
        searchOnlineSongs(q, previewSize).filter { onlineItem ->
            existingIds.add(searchSongIdentity(onlineItem))
        }
    onlineSongs.forEach { onlineSearchItemCache.put(it.mediaId, it) }
    return interleaveMediaItems(offlineSongs.items, onlineSongs).take(previewSize)
        .mapNotNull { resolveAutoPlaybackItem(it) }.let(autoOfflineSongs::filterItems)
}

/** Browse prefixes must never become the URI/cache key passed to the normal source resolver. */
internal suspend fun MediaLibrarySessionCallback.resolveAutoPlaybackItem(item: MediaItem): MediaItem? {
    if (item.mediaMetadata.isBrowsable == true) return null
    val id = item.mediaId.autoSongMediaId()
    if (id.isBlank()) return null
    if (id == item.mediaId && item.localConfiguration != null) return item
    if (id == item.mediaId) return (autoOfflineSongs.knownSong(id) ?: database.song(id).first())?.toMediaItem()
        ?: onlineSearchItemCache.get(id) ?: item.buildUpon().setUri(id).setCustomCacheKey(id).build()
    return (autoOfflineSongs.knownSong(id) ?: database.song(id).first())?.toMediaItem() ?: onlineSearchItemCache.get(id)
}

internal fun searchSongIdentity(item: MediaItem): String = item.mediaId.removePrefix("${MusicService.SONG}/")

internal suspend fun MediaLibrarySessionCallback.searchOnlineSongs(
    query: String,
    previewSize: Int,
): List<MediaItem> {
    if (query.isBlank() || previewSize <= 0 || autoOfflineSongs.isOffline()) return emptyList()
    return YouTube
        .search(query, YouTube.SearchFilter.FILTER_SONG)
        .getOrNull()
        ?.items
        .orEmpty()
        .asSequence()
        .filterIsInstance<SongItem>()
        .distinctBy { it.id }
        .take(previewSize)
        .map { it.toMediaItem() }
        .toList()
}

private const val SONG_ID_QUERY_BATCH_SIZE = 900

internal fun cachedSongMediaId(key: String): String {
    if (key.isLocalMediaId()) return key
    val unprefixed = DownloadSourceConfig.CACHE_KEY_PREFIXES.firstOrNull(key::startsWith)?.let(key::removePrefix) ?: key
    return if (unprefixed.substringAfterLast("#i", "").toIntOrNull()?.let { it > 0 } == true) {
        unprefixed.substringBeforeLast("#i")
    } else unprefixed
}
