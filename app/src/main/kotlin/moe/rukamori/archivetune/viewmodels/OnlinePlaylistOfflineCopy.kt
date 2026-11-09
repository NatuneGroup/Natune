package moe.rukamori.archivetune.viewmodels

import kotlinx.coroutines.flow.first
import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.db.entities.Playlist
import moe.rukamori.archivetune.db.entities.PlaylistSong
import moe.rukamori.archivetune.extensions.filterBlockedArtists
import moe.rukamori.archivetune.innertube.models.Album
import moe.rukamori.archivetune.innertube.models.Artist
import moe.rukamori.archivetune.innertube.models.PlaylistItem
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.innertube.models.WatchEndpoint
import moe.rukamori.archivetune.innertube.models.distinctByPlaylistEntry
import moe.rukamori.archivetune.innertube.pages.PlaylistPage
import moe.rukamori.archivetune.playback.queues.FinitePlaylistLoader
import moe.rukamori.archivetune.playback.queues.normalizedPlaylistId

internal enum class OnlinePlaylistSongsSource { REMOTE, OFFLINE_COPY, NONE }

/**
 * Which list the online playlist page shows once a fetch settles.
 *
 * A saved copy's membership is written from whole-list fetches (sync, save to library, import), so
 * a non-empty membership is the last full snapshot the app has. A fetched page replaces it only when
 * it carries songs or is confirmed empty: a failed fetch or an unconfirmed empty page is no evidence
 * that the playlist changed. Without a saved copy the fetch outcome stands, as before.
 */
internal fun onlinePlaylistSongsSource(
    fetchSucceeded: Boolean,
    fetchedSongCount: Int,
    fetchConfirmedEmpty: Boolean,
    savedMembershipSize: Int,
): OnlinePlaylistSongsSource =
    when {
        fetchSucceeded && (fetchedSongCount > 0 || fetchConfirmedEmpty || savedMembershipSize == 0) ->
            OnlinePlaylistSongsSource.REMOTE
        savedMembershipSize > 0 -> OnlinePlaylistSongsSource.OFFLINE_COPY
        else -> OnlinePlaylistSongsSource.NONE
    }

/** An empty first page is only an empty playlist when nothing follows and the header says zero. */
internal fun PlaylistPage.isConfirmedEmpty(): Boolean =
    songs.isEmpty() &&
        songsContinuation == null &&
        continuation == null &&
        runCatching { FinitePlaylistLoader.requireConfirmedInitial(this) }.isSuccess

/** [songs] is filtered for display; [membershipSize] counts the saved membership itself. */
internal class OfflinePlaylistCopy(
    val playlist: PlaylistItem,
    val songs: List<SongItem>,
    val membershipSize: Int,
)

internal suspend fun MusicDatabase.offlinePlaylistCopy(
    playlistId: String,
    hideVideo: Boolean,
    blockedArtistIds: Set<String>,
): OfflinePlaylistCopy? {
    val saved = playlistByAnyBrowseId(playlistId).first() ?: return null
    val membership = playlistSongs(saved.id).first()
    if (membership.isEmpty()) return null
    val songs =
        membership
            .filterNot { hideVideo && it.song.song.isMusicVideo }
            .map { it.toOfflineSongItem() }
            .filterBlockedArtists(blockedArtistIds)
            .distinctByPlaylistEntry()
    return OfflinePlaylistCopy(saved.toOnlinePlaylistItem(playlistId), songs, membership.size)
}

private fun Playlist.toOnlinePlaylistItem(playlistId: String): PlaylistItem {
    val bareId = playlistId.normalizedPlaylistId()
    return PlaylistItem(
        id = playlistId,
        title = playlist.name,
        author = null,
        songCountText = null,
        thumbnail = thumbnails.firstOrNull(),
        playEndpoint = WatchEndpoint(playlistId = bareId, params = playlist.playEndpointParams),
        shuffleEndpoint = WatchEndpoint(playlistId = bareId, params = playlist.shuffleEndpointParams),
        radioEndpoint = WatchEndpoint(playlistId = "RDAMPL$bareId", params = playlist.radioEndpointParams),
        isEditable = playlist.isEditable,
    )
}

private fun PlaylistSong.toOfflineSongItem(): SongItem {
    val entity = song.song
    return SongItem(
        id = entity.id,
        title = entity.title,
        artists = song.artists.map { artist -> Artist(name = artist.name, id = artist.id) },
        album =
            song.album?.let { album -> Album(name = album.title, id = album.id) }
                ?: entity.albumId?.let { albumId -> Album(name = entity.albumName.orEmpty(), id = albumId) },
        duration = entity.duration.takeIf { it >= 0 },
        thumbnail = entity.thumbnailUrl.orEmpty(),
        explicit = entity.explicit,
        setVideoId = map.setVideoId,
    )
}
