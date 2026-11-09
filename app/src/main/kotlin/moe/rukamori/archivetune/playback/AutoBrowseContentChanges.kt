package moe.rukamori.archivetune.playback

import androidx.media3.exoplayer.offline.Download
import moe.rukamori.archivetune.db.entities.PlaylistEntity
import moe.rukamori.archivetune.db.entities.PlaylistSongMap
import moe.rukamori.archivetune.db.entities.Song
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.models.toMediaMetadata
import java.time.LocalDateTime

internal data class AutoBrowseContentChange(
    val folders: Set<String> = emptySet(),
    val prefixes: Set<String> = emptySet(),
) {
    fun affects(parentId: String): Boolean = parentId in folders || prefixes.any(parentId::startsWith)
}

/** No play time, history, play counts, codec reports, or other playback statistics. */
internal data class AutoBrowseSongContent(
    val metadata: MediaMetadata,
    val liked: Boolean,
    val likedDate: LocalDateTime?,
    val inLibrary: LocalDateTime?,
    val blockedAt: LocalDateTime?,
)

internal fun Song.autoBrowseContent() = AutoBrowseSongContent(
    toMediaMetadata(), song.liked, song.likedDate, song.inLibrary, song.blockedAt,
)

internal fun autoBrowseSongChanges(
    previous: Map<String, AutoBrowseSongContent>,
    current: Map<String, AutoBrowseSongContent>,
    playlistIdsBySong: Map<String, Set<String>> = emptyMap(),
): AutoBrowseContentChange {
    val changedIds = (previous.keys + current.keys).filter { previous[it] != current[it] }
    val folders = mutableSetOf<String>()
    val prefixes = mutableSetOf<String>()
    for (id in changedIds) {
        val before = previous[id]
        val after = current[id]
        if (before?.liked != after?.liked || before?.likedDate != after?.likedDate) {
            folders.addAll(listOf(MusicService.LIKED, MusicService.PLAYLIST,
                "${MusicService.PLAYLIST}/${PlaylistEntity.LIKED_PLAYLIST_ID}", MusicService.HOME_FORGOTTEN_FAVORITES,
                MusicService.PINNED_PLAYLISTS, "${MusicService.PINNED_PLAYLISTS}/local/${PlaylistEntity.LIKED_PLAYLIST_ID}"))
        }
        if (before?.metadata != after?.metadata || before?.inLibrary != after?.inLibrary || before?.blockedAt != after?.blockedAt) {
            folders.addAll(listOf(MusicService.SONG, MusicService.LIKED, MusicService.DOWNLOADED,
                MusicService.RECENT, MusicService.QUICK_PICKS, MusicService.HOME_QUICK_PICKS,
                MusicService.HOME_FORGOTTEN_FAVORITES, MusicService.HOME_KEEP_LISTENING,
                MusicService.ARTIST, MusicService.ALBUM))
            listOfNotNull(before?.metadata, after?.metadata).forEach { metadata ->
                metadata.artists.mapNotNull { it.id }.forEach { folders.add("${MusicService.ARTIST}/$it") }
                metadata.album?.id?.let { folders.add("${MusicService.ALBUM}/$it") }
            }
        }
        if (before?.metadata != after?.metadata) {
            playlistIdsBySong[id].orEmpty().forEach { playlistId ->
                folders.addAll(listOf(MusicService.PLAYLIST, MusicService.PINNED_PLAYLISTS,
                    "${MusicService.PLAYLIST}/$playlistId", "${MusicService.PINNED_PLAYLISTS}/local/$playlistId"))
                prefixes.add("${MusicService.PLAYLIST}/$playlistId/")
            }
        }
    }
    return AutoBrowseContentChange(folders, prefixes)
}

internal data class AutoBrowsePlaylistContent(
    val playlist: PlaylistEntity,
    val entries: List<PlaylistSongMap>,
)

internal fun autoBrowsePlaylistChanges(
    previous: Map<String, AutoBrowsePlaylistContent>,
    current: Map<String, AutoBrowsePlaylistContent>,
): AutoBrowseContentChange {
    val ids = (previous.keys + current.keys).filter { previous[it] != current[it] }
    if (ids.isEmpty()) return AutoBrowseContentChange()
    val folders = mutableSetOf(MusicService.PLAYLIST, MusicService.PINNED_PLAYLISTS, MusicService.HOME_MIXES_AND_RADIOS)
    val prefixes = mutableSetOf<String>()
    for (id in ids) {
        folders.add("${MusicService.PLAYLIST}/$id")
        prefixes.add("${MusicService.PLAYLIST}/$id/")
        folders.add("${MusicService.PINNED_PLAYLISTS}/local/$id")
        listOfNotNull(previous[id]?.playlist?.browseId, current[id]?.playlist?.browseId).forEach { browseId ->
            folders.add("${MusicService.ONLINE_PLAYLIST}/$browseId")
            folders.add("${MusicService.PINNED_PLAYLISTS}/online/$browseId")
        }
    }
    return AutoBrowseContentChange(folders, prefixes)
}

internal val autoBrowseDownloadChanges = AutoBrowseContentChange(setOf(
    MusicService.DOWNLOADED, MusicService.PLAYLIST, "${MusicService.PLAYLIST}/${PlaylistEntity.DOWNLOADED_PLAYLIST_ID}",
    MusicService.PINNED_PLAYLISTS, "${MusicService.PINNED_PLAYLISTS}/local/${PlaylistEntity.DOWNLOADED_PLAYLIST_ID}",
))

internal fun <T> autoBrowseCompletedDownloadIds(downloads: Map<String, T>, state: (T) -> Int): Set<String> =
    downloads.asSequence().filter { state(it.value) == Download.STATE_COMPLETED }.map { it.key }.toSet()

internal val autoBrowseSpotifyChanges = AutoBrowseContentChange(
    setOf(MusicService.PLAYLIST, MusicService.SPOTIFY_LIKED, MusicService.SPOTIFY_PLAYLIST),
    setOf("${MusicService.SPOTIFY_PLAYLIST}/"),
)
