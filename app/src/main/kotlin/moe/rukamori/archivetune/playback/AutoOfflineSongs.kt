package moe.rukamori.archivetune.playback

import androidx.media3.common.MediaItem
import moe.rukamori.archivetune.androidauto.AndroidAutoPinnedPlaylists
import moe.rukamori.archivetune.db.entities.Song
import moe.rukamori.archivetune.utils.isLocalMediaId

/** A local file/content URI can itself contain slashes after the Auto folder prefix. */
internal fun String.autoSongPathId(prefixSegments: Int): String? =
    split('/', limit = prefixSegments + 1).getOrNull(prefixSegments)?.takeIf { it.isNotBlank() }

/** Strip only the browse prefix, preserving file/content URIs and queue occurrence IDs. */
internal fun String.autoSongMediaId(): String = when {
    isLocalMediaId() -> this
    startsWith("${MusicService.QUEUE}/") -> AutoQueueMediaIds.parse(this)?.songMediaId ?: this
    startsWith("${MusicService.PINNED_PLAYLISTS}/") -> AndroidAutoPinnedPlaylists.parseMediaId(this)?.songMediaId ?: this
    startsWith("${MusicService.PLAYLIST}/") -> {
        val action = split('/', limit = 4).getOrNull(2)
        autoSongPathId(when (action) { "_sort" -> 5; "_shuffle" -> 3; else -> 2 }) ?: this
    }
    startsWith("${MusicService.ARTIST}/") || startsWith("${MusicService.ALBUM}/") ||
        startsWith("${MusicService.SPOTIFY_PLAYLIST}/") || startsWith("${MusicService.ONLINE_PLAYLIST}/") ->
        autoSongPathId(if (split('/', limit = 4).getOrNull(2) == "_shuffle") 3 else 2) ?: this
    substringBefore('/') in MediaLibrarySessionCallback.AUTO_QUEUE_SONG_ROOTS || startsWith("${MusicService.SPOTIFY_LIKED}/") ->
        autoSongPathId(1) ?: this
    else -> this
}

/** Filters database-backed Auto collections without changing their order or song identity. */
internal class AutoOfflineSongs(
    val isOffline: () -> Boolean = { false },
    private val isDownloaded: (String) -> Boolean = { false },
    private val isFullyCached: (Song) -> Boolean = { false },
    private val isFullyCachedId: (String) -> Boolean = { false },
) {
    @Volatile private var knownSongs: Map<String, Song> = emptyMap()

    /** The existing database observer supplies format relations; transport never blocks on Room. */
    fun updateSongs(songs: List<Song>) { synchronized(this) { knownSongs = songs.associateBy { it.id } } }

    fun rememberSongs(songs: List<Song>) {
        synchronized(this) {
            val missing = songs.filter { it.id !in knownSongs }
            if (missing.isNotEmpty()) knownSongs = knownSongs + missing.associateBy { it.id }
        }
    }

    fun knownSong(mediaId: String): Song? = knownSongs[mediaId]

    fun canPlay(song: Song): Boolean =
        song.song.isLocal || song.id.isLocalMediaId() || isDownloaded(song.id) || isFullyCached(song)

    fun canPlayId(mediaId: String): Boolean =
        mediaId.isLocalMediaId() || (knownSongs[mediaId]?.let(::canPlay) ?: (isDownloaded(mediaId) || isFullyCachedId(mediaId)))

    fun filter(songs: List<Song>): List<Song> {
        rememberSongs(songs)
        return if (isOffline()) songs.filter(::canPlay) else songs
    }

    fun filterItems(items: List<MediaItem>): List<MediaItem> =
        if (isOffline()) items.filter { canPlayId(it.mediaId.autoSongMediaId()) } else items

    fun filterBrowseItems(items: List<MediaItem>): List<MediaItem> =
        if (isOffline()) items.filter {
            it.mediaMetadata.isBrowsable == true || it.mediaMetadata.isPlayable != true || canPlayId(it.mediaId.autoSongMediaId())
        } else items

    data class Selection<T>(val items: List<T>, val startIndex: Int, val startPositionMs: Long)

    fun select(songs: List<Song>, selectedSongId: String?, startPositionMs: Long): Selection<Song>? {
        rememberSongs(songs)
        val originalStart = autoBrowseStartIndex(songs, selectedSongId, Song::id) ?: return null
        return select(songs, originalStart, startPositionMs, selectedSongId == null, ::canPlay)
    }

    /** An indexed tap keeps that exact occurrence, including duplicate songs. */
    fun selectItems(items: List<MediaItem>, startIndex: Int, positionMs: Long, allowFirstPlayable: Boolean = false): Selection<MediaItem>? =
        select(items, startIndex, positionMs, allowFirstPlayable) { canPlayId(it.mediaId.autoSongMediaId()) }

    private fun <T> select(
        items: List<T>, startIndex: Int, positionMs: Long, allowFirstPlayable: Boolean, canPlay: (T) -> Boolean,
    ): Selection<T>? {
        if (items.isEmpty() || startIndex !in items.indices) return null
        if (!isOffline()) return Selection(items, startIndex, positionMs)
        val playable = items.withIndex().filter { canPlay(it.value) }
        if (playable.isEmpty()) return null
        val selected = playable.indexOfFirst { it.index == startIndex }
        if (selected < 0 && !allowFirstPlayable) return null
        val index = selected.coerceAtLeast(0)
        return Selection(playable.map { it.value }, index, if (selected >= 0) positionMs else 0L)
    }
}
