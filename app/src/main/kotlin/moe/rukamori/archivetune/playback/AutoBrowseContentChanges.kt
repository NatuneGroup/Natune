package moe.rukamori.archivetune.playback

import androidx.media3.exoplayer.offline.Download
import moe.rukamori.archivetune.db.entities.PlaylistEntity
import moe.rukamori.archivetune.db.entities.PlaylistSongMap
import moe.rukamori.archivetune.db.entities.Song
import java.time.LocalDateTime

internal data class AutoBrowseContentChange(
    val folders: Set<String> = emptySet(),
    val prefixes: Set<String> = emptySet(),
) {
    fun affects(parentId: String): Boolean = parentId in folders || prefixes.any(parentId::startsWith)
}

/** The artist list shows these fields. */
internal data class AutoBrowseArtist(val id: String, val name: String, val thumbnailUrl: String?)

internal data class AutoBrowseAlbum(val id: String, val title: String, val thumbnailUrl: String?)

/**
 * What Android Auto's song-backed folders show, filter, group or sort by. Rows show the canonical title, artist
 * names and artwork, never display edits. Durations, play time, history and lyrics offsets stay out: playback
 * writes them all the time, and refreshing an open folder resets the car's scroll position.
 */
internal data class AutoBrowseSongContent(
    val title: String,
    val thumbnailUrl: String?,
    val artists: List<AutoBrowseArtist>,
    /** The liked folders and Keep listening hide songs by a blocked artist. */
    val artistBlocked: Boolean,
    /** The album list reads this column; album folders read [album], which comes from song_album_map. */
    val albumId: String?,
    val album: AutoBrowseAlbum?,
    val liked: Boolean,
    val likedDate: LocalDateTime?,
    val inLibrary: LocalDateTime?,
    /** Orders the downloaded alias, falling back to [inLibrary]. */
    val dateDownload: LocalDateTime?,
)

internal fun Song.autoBrowseContent() = AutoBrowseSongContent(
    title = song.title,
    thumbnailUrl = song.thumbnailUrl,
    artists = artists.map { AutoBrowseArtist(it.id, it.name, it.thumbnailUrl) },
    artistBlocked = artists.any { it.blockedAt != null },
    albumId = song.albumId,
    album = album?.let { AutoBrowseAlbum(it.id, it.title, it.thumbnailUrl) },
    liked = song.liked,
    likedDate = song.likedDate,
    inLibrary = song.inLibrary,
    dateDownload = song.dateDownload,
)

private val likedFolders = setOf(MusicService.LIKED, "${MusicService.PLAYLIST}/${PlaylistEntity.LIKED_PLAYLIST_ID}",
    "${MusicService.PINNED_PLAYLISTS}/local/${PlaylistEntity.LIKED_PLAYLIST_ID}")
private val likedAliasChildren = "${MusicService.PLAYLIST}/${PlaylistEntity.LIKED_PLAYLIST_ID}/"
private val downloadedFolders = setOf(MusicService.DOWNLOADED, "${MusicService.PLAYLIST}/${PlaylistEntity.DOWNLOADED_PLAYLIST_ID}",
    "${MusicService.PINNED_PLAYLISTS}/local/${PlaylistEntity.DOWNLOADED_PLAYLIST_ID}")
private val downloadedAliasChildren = "${MusicService.PLAYLIST}/${PlaylistEntity.DOWNLOADED_PLAYLIST_ID}/"

/** Built from listening history, which is not observed, so only an edit to a row they may list refreshes them. */
private val historyFolders = setOf(MusicService.RECENT, MusicService.QUICK_PICKS, MusicService.HOME_QUICK_PICKS,
    MusicService.HOME_FORGOTTEN_FAVORITES, MusicService.HOME_KEEP_LISTENING)

/**
 * A missing row counts as neither liked nor in the library, so the songs and related tracks that playback inserts
 * refresh no folder. Downloads, history and playlists may list any stored song, so they refresh when a row that
 * already existed looks different.
 */
internal fun autoBrowseSongChanges(
    previous: Map<String, AutoBrowseSongContent>,
    current: Map<String, AutoBrowseSongContent>,
    playlistIdsBySong: Map<String, Set<String>> = emptyMap(),
): AutoBrowseContentChange {
    val folders = mutableSetOf<String>()
    val prefixes = mutableSetOf<String>()
    for (id in previous.keys + current.keys) {
        val before = previous[id]
        val after = current[id]
        if (before == after) continue
        val rows = listOfNotNull(before, after)
        val rowChanged = before?.title != after?.title || before?.thumbnailUrl != after?.thumbnailUrl ||
            before?.artists?.map { it.name } != after?.artists?.map { it.name }
        val likeChanged = (before?.liked == true) != (after?.liked == true)
        val blockingChanged = (before?.artistBlocked == true) != (after?.artistBlocked == true)
        if (likeChanged || before?.likedDate != after?.likedDate || rows.any { it.liked } && (rowChanged || blockingChanged)) {
            folders += likedFolders
            prefixes += likedAliasChildren
        }
        if (likeChanged) folders += listOf(MusicService.PLAYLIST, MusicService.PINNED_PLAYLISTS) // Both show the liked count.
        if (rows.any { it.inLibrary != null }) {
            val libraryChanged = before?.inLibrary != after?.inLibrary
            val artistIdsChanged = before?.artists?.map { it.id } != after?.artists?.map { it.id }
            val albumChanged = before?.albumId != after?.albumId || before?.album != after?.album
            if (libraryChanged || rowChanged) folders += MusicService.SONG
            if (libraryChanged || before?.artists != after?.artists) folders += MusicService.ARTIST
            if (libraryChanged || rowChanged || artistIdsChanged) {
                rows.flatMap { it.artists }.mapTo(folders) { "${MusicService.ARTIST}/${it.id}" }
            }
            if ((libraryChanged || albumChanged) && rows.any { it.albumId != null }) folders += MusicService.ALBUM
        }
        if (rowChanged || before?.album?.id != after?.album?.id) {
            rows.mapNotNull { it.album?.id }.mapTo(folders) { "${MusicService.ALBUM}/$it" }
        }
        if (before == null) continue
        if (rowChanged) {
            folders += downloadedFolders + historyFolders
            prefixes += downloadedAliasChildren
            playlistIdsBySong[id].orEmpty().forEach { playlistId ->
                folders.addAll(listOf(MusicService.PLAYLIST, MusicService.PINNED_PLAYLISTS,
                    "${MusicService.PLAYLIST}/$playlistId", "${MusicService.PINNED_PLAYLISTS}/local/$playlistId"))
                prefixes.add("${MusicService.PLAYLIST}/$playlistId/")
            }
        }
        if (blockingChanged) folders += MusicService.HOME_KEEP_LISTENING
        if (after != null && (before.dateDownload ?: before.inLibrary) != (after.dateDownload ?: after.inLibrary)) {
            prefixes += downloadedAliasChildren
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

internal val autoBrowseDownloadChanges = AutoBrowseContentChange(
    downloadedFolders + setOf(MusicService.PLAYLIST, MusicService.PINNED_PLAYLISTS), // Both show the downloaded count.
    setOf(downloadedAliasChildren),
)

internal fun <T> autoBrowseCompletedDownloadIds(downloads: Map<String, T>, state: (T) -> Int): Set<String> =
    downloads.asSequence().filter { state(it.value) == Download.STATE_COMPLETED }.map { it.key }.toSet()

internal val autoBrowseSpotifyChanges = AutoBrowseContentChange(
    setOf(MusicService.PLAYLIST, MusicService.SPOTIFY_LIKED, MusicService.SPOTIFY_PLAYLIST),
    setOf("${MusicService.SPOTIFY_PLAYLIST}/"),
)
