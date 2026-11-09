package moe.rukamori.archivetune.playback

import androidx.media3.common.MediaItem
import moe.rukamori.archivetune.androidauto.AndroidAutoPinnedPlaylist
import moe.rukamori.archivetune.androidauto.AndroidAutoPinnedPlaylistKind
import moe.rukamori.archivetune.androidauto.AndroidAutoPinnedPlaylists

/**
 * Online folders cannot be rebuilt offline, but the car still shows their retained browse
 * snapshot. Playing from it keeps the visible order and the tapped song; the caller's offline
 * selection then drops rows that are neither downloaded nor fully cached.
 */
internal suspend fun MediaLibrarySessionCallback.offlineSnapshotItems(parentId: String): List<MediaItem>? {
    if (!autoOfflineSongs.isOffline()) return null
    return browseFolderUpdates.snapshot(parentId)
        ?.filter { it.mediaMetadata.isBrowsable != true && it.mediaMetadata.isPlayable == true }
        ?.map { item ->
            val id = item.mediaId.autoSongMediaId()
            resolveAutoPlaybackItem(item) ?: item.buildUpon().setMediaId(id).setUri(id).setCustomCacheKey(id).build()
        }
        ?.takeIf { it.isNotEmpty() }
}

internal suspend fun MediaLibrarySessionCallback.offlineOnlinePlaylistItems(playlistId: String, shuffle: Boolean): List<MediaItem>? {
    val parentId = "${MusicService.ONLINE_PLAYLIST}/$playlistId"
    return if (shuffle) {
        offlineSnapshotItems("$parentId/_shuffle") ?: offlineSnapshotItems(parentId)?.shuffled()
    } else {
        offlineSnapshotItems(parentId)
    }
}

internal suspend fun MediaLibrarySessionCallback.offlinePinnedPlaylistItems(playlistId: String): List<MediaItem>? =
    offlineSnapshotItems(AndroidAutoPinnedPlaylists.mediaIdFor(AndroidAutoPinnedPlaylist(AndroidAutoPinnedPlaylistKind.ONLINE, playlistId)))
        ?: offlineSnapshotItems("${MusicService.ONLINE_PLAYLIST}/$playlistId")
