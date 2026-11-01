/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.ui.menu

import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.db.entities.PlaylistSongMap
import moe.rukamori.archivetune.innertube.YouTube

suspend fun removeSongFromRemotePlaylist(
    playlistBrowseId: String,
    playlistSongMap: PlaylistSongMap,
): Result<Unit> =
    runCatching {
        val setVideoIds =
            playlistSongMap.setVideoId?.let(::listOf)
                ?: YouTube.playlistEntrySetVideoIds(playlistBrowseId, playlistSongMap.songId).getOrThrow()

        setVideoIds
            .distinct()
            .forEach { setVideoId ->
                YouTube.removeFromPlaylist(playlistBrowseId, playlistSongMap.songId, setVideoId).getOrThrow()
            }
    }

/**
 * Moves one playlist row to its top or bottom edge. The local move repositions the row; a linked YouTube playlist
 * additionally receives the successor-pointer move against the row the song lands before — the current first row for
 * the top edge, null for the bottom. Failure carries the remote result so the caller can report it.
 */
suspend fun moveSongToPlaylistEdge(
    database: MusicDatabase,
    playlistSongMap: PlaylistSongMap,
    playlistBrowseId: String?,
    toTop: Boolean,
): Result<Unit> =
    runCatching {
        val maxPosition = database.maxPlaylistSongPosition(playlistSongMap.playlistId) ?: playlistSongMap.position
        val target = if (toTop) 0 else maxPosition
        if (playlistSongMap.position == target) return@runCatching
        val successorSetVideoId =
            if (toTop) {
                database.getPlaylistSongs(playlistSongMap.playlistId).firstOrNull()?.map?.setVideoId
            } else {
                null
            }
        database.withTransaction {
            move(playlistSongMap.playlistId, playlistSongMap.position, target)
        }
        val setVideoId = playlistSongMap.setVideoId
        if (playlistBrowseId != null && setVideoId != null) {
            YouTube.moveSongPlaylist(playlistBrowseId, setVideoId, successorSetVideoId).getOrThrow()
        }
    }
