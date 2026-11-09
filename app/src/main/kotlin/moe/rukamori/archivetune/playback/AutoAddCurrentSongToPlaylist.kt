package moe.rukamori.archivetune.playback

import androidx.media3.session.MediaSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.extensions.metadata
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.telegram.isTelegramMediaId
import moe.rukamori.archivetune.utils.isLocalMediaId

internal suspend fun MediaLibrarySessionCallback.addCurrentSongToPlaylist(
    mediaSession: MediaSession,
    playlistId: String,
): Boolean {
    val currentItem =
        withContext(Dispatchers.Main.immediate) {
            mediaSession.player.currentMediaItem
        } ?: return false
    val songId = currentItem.mediaId.trim().takeIf(String::isNotBlank) ?: return false
    return withContext(Dispatchers.IO) {
        val playlist = database.getPlaylistById(playlistId) ?: return@withContext false
        if (!playlist.playlist.isEditable) return@withContext false
        if (database.checkInPlaylist(playlistId, songId) > 0) return@withContext false
        val browseId = playlist.playlist.browseId
        val setVideoId =
            if (browseId != null) {
                if (songId.isLocalMediaId() || songId.isTelegramMediaId()) return@withContext false
                YouTube.addToPlaylist(browseId, songId).getOrNull() ?: return@withContext false
            } else {
                null
            }
        database.withTransaction {
            if (getSongByIdBlocking(songId) == null) {
                currentItem.metadata?.let { insert(it) } ?: return@withTransaction false
            }
            val latestPlaylist = getPlaylistByIdBlocking(playlistId) ?: return@withTransaction false
            if (checkInPlaylist(playlistId, songId) > 0) return@withTransaction false
            addSongEntriesToPlaylist(latestPlaylist, listOf(songId to setVideoId))
            true
        }
    }
}
