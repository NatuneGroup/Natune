package moe.rukamori.archivetune.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.androidauto.AndroidAutoPinnedPlaylist
import moe.rukamori.archivetune.androidauto.AndroidAutoPinnedPlaylistKind
import moe.rukamori.archivetune.androidauto.AndroidAutoPinnedPlaylists
import moe.rukamori.archivetune.constants.AndroidAutoPinnedPlaylistShuffleKey
import moe.rukamori.archivetune.constants.AndroidAutoPinnedPlaylistsJsonKey
import moe.rukamori.archivetune.extensions.toMediaItem
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.get
import timber.log.Timber

internal fun MediaLibrarySessionCallback.notifyOfflineAvailabilityChanged(session: MediaLibrarySession) {
    browseFolderUpdates.availabilityChanged({ session.getSubscribedControllers(it).isNotEmpty() }) { parentId ->
        runCatching { session.notifyChildrenChanged(parentId, Int.MAX_VALUE, null) }
            .onFailure { Timber.tag("MusicService").w(it, "Failed to refresh Auto availability for %s", parentId) }
    }
    runCatching { session.notifyChildrenChanged(MusicService.QUEUE, Int.MAX_VALUE, null) }
}

/**
 * The fixed top-level and Home folder items — identical whether reached as a child of
 * [MusicService.ROOT]/[MusicService.HOME] in onGetChildren or looked up directly by id
 * in onGetItem. Returns null for any other id.
 */
internal fun MediaLibrarySessionCallback.staticFolderItem(id: String): MediaItem? =
    when (id) {
        MusicService.HOME ->
            browsableMediaItem(
                MusicService.HOME,
                context.getString(R.string.home),
                null,
                drawableUri(R.drawable.home_filled),
                MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
            )

        MusicService.HOME_QUICK_PICKS ->
            queueMediaItem(
                MusicService.HOME_QUICK_PICKS,
                context.getString(R.string.quick_picks),
                null,
                drawableUri(R.drawable.playlist_play),
                MediaMetadata.MEDIA_TYPE_PLAYLIST,
            )

        MusicService.HOME_FORGOTTEN_FAVORITES ->
            queueMediaItem(
                MusicService.HOME_FORGOTTEN_FAVORITES,
                context.getString(R.string.forgotten_favorites),
                null,
                drawableUri(R.drawable.favorite),
                MediaMetadata.MEDIA_TYPE_PLAYLIST,
            )

        MusicService.HOME_KEEP_LISTENING ->
            queueMediaItem(
                MusicService.HOME_KEEP_LISTENING,
                context.getString(R.string.keep_listening),
                null,
                drawableUri(R.drawable.history),
                MediaMetadata.MEDIA_TYPE_PLAYLIST,
            )

        MusicService.HOME_SUGGESTED_SONGS ->
            queueMediaItem(
                MusicService.HOME_SUGGESTED_SONGS,
                context.getString(R.string.android_auto_suggested_songs),
                null,
                drawableUri(R.drawable.music_note),
                MediaMetadata.MEDIA_TYPE_PLAYLIST,
            )

        MusicService.HOME_MIXES_AND_RADIOS ->
            browsableMediaItem(
                MusicService.HOME_MIXES_AND_RADIOS,
                context.getString(R.string.android_auto_mixes_and_radios),
                null,
                drawableUri(R.drawable.radio),
                MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS,
            )

        MusicService.QUICK_PICKS ->
            queueMediaItem(
                MusicService.QUICK_PICKS,
                context.getString(R.string.quick_picks),
                null,
                drawableUri(R.drawable.playlist_play),
                MediaMetadata.MEDIA_TYPE_PLAYLIST,
            )

        MusicService.RECENT ->
            queueMediaItem(
                MusicService.RECENT,
                context.getString(R.string.history),
                null,
                drawableUri(R.drawable.history),
                MediaMetadata.MEDIA_TYPE_PLAYLIST,
            )

        MusicService.LIKED ->
            queueMediaItem(
                MusicService.LIKED,
                context.getString(R.string.liked_songs),
                null,
                drawableUri(R.drawable.favorite),
                MediaMetadata.MEDIA_TYPE_PLAYLIST,
            )

        MusicService.DOWNLOADED ->
            queueMediaItem(
                MusicService.DOWNLOADED,
                context.getString(R.string.downloaded_songs),
                null,
                drawableUri(R.drawable.download),
                MediaMetadata.MEDIA_TYPE_PLAYLIST,
            )

        MusicService.SONG ->
            browsableMediaItem(
                MusicService.SONG,
                context.getString(R.string.songs),
                null,
                drawableUri(R.drawable.music_note),
                MediaMetadata.MEDIA_TYPE_PLAYLIST,
            )

        MusicService.ARTIST ->
            browsableMediaItem(
                MusicService.ARTIST,
                context.getString(R.string.artists),
                null,
                drawableUri(R.drawable.artist),
                MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS,
            )

        MusicService.ALBUM ->
            browsableMediaItem(
                MusicService.ALBUM,
                context.getString(R.string.albums),
                null,
                drawableUri(R.drawable.album),
                MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS,
            )

        MusicService.PLAYLIST ->
            browsableMediaItem(
                MusicService.PLAYLIST,
                context.getString(R.string.playlists),
                null,
                drawableUri(R.drawable.queue_music),
                MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS,
            )

        MusicService.QUEUE ->
            browsableMediaItem(
                MusicService.QUEUE,
                context.getString(R.string.queue),
                null,
                drawableUri(R.drawable.queue_music),
                MediaMetadata.MEDIA_TYPE_PLAYLIST,
            )

        MusicService.PINNED_PLAYLISTS ->
            browsableMediaItem(
                MusicService.PINNED_PLAYLISTS,
                context.getString(R.string.android_auto_driving_playlists),
                null,
                drawableUri(R.drawable.push_pin),
                MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS,
            )

        else -> null
    }

internal suspend fun MediaLibrarySessionCallback.currentQueueChildren(
    session: MediaLibrarySession,
): List<MediaItem> =
    withContext(Dispatchers.Main) {
        val player = session.player
        (0 until player.mediaItemCount).map { index ->
            val item = player.getMediaItemAt(index)
            item
                .buildUpon()
                .setMediaId(AutoQueueMediaIds.mediaIdFor(index, item.mediaId))
                .setMediaMetadata(
                    item.mediaMetadata
                        .buildUpon()
                        .setIsPlayable(true)
                        .setIsBrowsable(false)
                        .setExtras(playableExtras())
                        .build(),
                ).build()
        }
    }

internal suspend fun MediaLibrarySessionCallback.queueEntryItem(
    session: MediaLibrarySession,
    mediaId: String,
): MediaItem? {
    val entry = AutoQueueMediaIds.parse(mediaId) ?: return null
    return withContext(Dispatchers.Main) {
        val player = session.player
        val queueMediaIds =
            List(player.mediaItemCount) { index -> player.getMediaItemAt(index).mediaId }
        AutoQueueMediaIds.resolvePosition(entry, queueMediaIds)?.let { position ->
            player
                .getMediaItemAt(position)
                .buildUpon()
                .setMediaId(mediaId)
                .build()
        }
    }
}

internal suspend fun MediaLibrarySessionCallback.queueEntryPlayback(
    session: MediaSession,
    mediaId: String,
    positionMs: Long,
): MediaSession.MediaItemsWithStartPosition? {
    val entry = AutoQueueMediaIds.parse(mediaId) ?: return null
    return withContext(Dispatchers.Main) {
        val player = session.player
        val items = List(player.mediaItemCount) { player.getMediaItemAt(it) }
        val position = AutoQueueMediaIds.resolvePosition(entry, items.map { it.mediaId }) ?: return@withContext null
        val selection = autoOfflineSongs.selectItems(items, position, positionMs) ?: return@withContext null
        MediaSession.MediaItemsWithStartPosition(selection.items, selection.startIndex, selection.startPositionMs)
    }
}

internal suspend fun MediaLibrarySessionCallback.pinnedPlaylistEntries(): List<AndroidAutoPinnedPlaylist> =
    AndroidAutoPinnedPlaylists.decode(
        context.dataStore.get(AndroidAutoPinnedPlaylistsJsonKey, "[]"),
    )

internal suspend fun MediaLibrarySessionCallback.pinnedPlaylistItems(): List<MediaItem> =
    pinnedPlaylistEntries().mapNotNull { entry -> pinnedPlaylistItem(entry) }

internal suspend fun MediaLibrarySessionCallback.pinnedPlaylistItem(entry: AndroidAutoPinnedPlaylist): MediaItem? {
    val source =
        when (entry.kind) {
            AndroidAutoPinnedPlaylistKind.LOCAL ->
                playlistItem("${MusicService.PLAYLIST}/${entry.id}")

            AndroidAutoPinnedPlaylistKind.ONLINE ->
                onlinePlaylistItem("${MusicService.ONLINE_PLAYLIST}/${entry.id}")
        } ?: return null
    return source
        .buildUpon()
        .setMediaId(AndroidAutoPinnedPlaylists.mediaIdFor(entry))
        .build()
}

internal suspend fun MediaLibrarySessionCallback.pinnedMediaItem(mediaId: String): MediaItem? {
    val ref = AndroidAutoPinnedPlaylists.parseMediaId(mediaId) ?: return null
    val songMediaId =
        ref.songMediaId
            ?: return pinnedPlaylistItem(AndroidAutoPinnedPlaylist(ref.kind, ref.playlistId))
    val parentId = mediaId.removeSuffix("/$songMediaId")
    return when (ref.kind) {
        AndroidAutoPinnedPlaylistKind.LOCAL ->
            database.song(songMediaId).first()?.let { song -> song.toMediaItem(parentId) }

        AndroidAutoPinnedPlaylistKind.ONLINE ->
            onlinePlaylistSongItem(
                playlistId = ref.playlistId,
                songId = songMediaId,
                parentId = parentId,
            )
    }
}

internal suspend fun MediaLibrarySessionCallback.pinnedPlaylistChildren(parentId: String): List<MediaItem> {
    // Song-level ids are leaves: browsing below them yields nothing.
    val ref = AndroidAutoPinnedPlaylists.parseMediaId(parentId) ?: return emptyList()
    if (ref.songMediaId != null) return emptyList()
    return when (ref.kind) {
        AndroidAutoPinnedPlaylistKind.LOCAL ->
            with(this) { playlistSongs(ref.playlistId).toAutoMediaItems(parentId) }

        AndroidAutoPinnedPlaylistKind.ONLINE ->
            onlinePlaylistSongs(ref.playlistId).map { song -> song.toMediaItem(parentId) }
    }
}

internal suspend fun MediaLibrarySessionCallback.pinnedPlaylistPlayback(
    mediaId: String,
    startPositionMs: Long,
): MediaSession.MediaItemsWithStartPosition? {
    val ref = AndroidAutoPinnedPlaylists.parseMediaId(mediaId) ?: return null
    val shufflePinned = context.dataStore.get(AndroidAutoPinnedPlaylistShuffleKey, false)
    val songs =
        when (ref.kind) {
            AndroidAutoPinnedPlaylistKind.LOCAL -> {
                val source = playlistSongs(ref.playlistId).let { if (ref.songMediaId == null && shufflePinned) it.shuffled() else it }
                val selection = autoOfflineSongs.select(source, ref.songMediaId, startPositionMs) ?: return null
                return MediaSession.MediaItemsWithStartPosition(
                    selection.items.map { it.toMediaItem() }, selection.startIndex, selection.startPositionMs,
                )
            }

            AndroidAutoPinnedPlaylistKind.ONLINE ->
                onlinePlaylistSongs(ref.playlistId).map { it.toMediaItem() }
        }
    if (songs.isEmpty()) return null
    // Pinned playlist taps honour the driving-playlist playback mode: shuffle starts a
    // shuffled list from the top while play-in-order keeps the playlist's order. A tap on
    // a specific song always starts at that song.
    val source = if (ref.songMediaId == null && shufflePinned) songs.shuffled() else songs
    val startIndex = autoBrowseStartIndex(source, ref.songMediaId) { it.mediaId } ?: return null
    val selection = autoOfflineSongs.selectItems(source, startIndex, startPositionMs,
        allowFirstPlayable = ref.songMediaId == null) ?: return null
    return MediaSession.MediaItemsWithStartPosition(selection.items, selection.startIndex, selection.startPositionMs)
}
