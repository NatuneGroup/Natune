package moe.rukamori.archivetune.playback

import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.constants.AndroidAutoPinnedPlaylistsJsonKey
import moe.rukamori.archivetune.constants.ShowSpotifyPlaylistsKey
import moe.rukamori.archivetune.db.entities.PlaylistSongMap
import moe.rukamori.archivetune.utils.dataStore
import timber.log.Timber

/** All collectors live in the service's scope, including across crossfade player promotion. */
internal fun MediaLibrarySessionCallback.observeBrowseContent(session: MediaLibrarySession, owner: CoroutineScope) {
    fun refresh(change: AutoBrowseContentChange) {
        browseFolderUpdates.invalidate(change, { session.getSubscribedControllers(it).isNotEmpty() }) { parentId ->
            runCatching { session.notifyChildrenChanged(parentId, Int.MAX_VALUE, null) }
                .onFailure { Timber.tag("MusicService").w(it, "Failed to refresh Auto folder %s", parentId) }
        }
    }
    fun refreshSpotify() {
        spotifyPlaylistItemCache.clear()
        refresh(autoBrowseSpotifyChanges)
    }
    owner.launch {
        combine(database.allSongs(), database.allPlaylistSongMapsForDownloads()) { songs, entries ->
            AutoBrowseSongSnapshot(songs.associate { it.id to it.autoBrowseContent() }, entries)
        }.flowOn(Dispatchers.IO).observeChanges { previous, current ->
            val memberships = (previous.entries + current.entries).groupBy { it.songId }
                .mapValues { (_, entries) -> entries.map { it.playlistId }.toSet() }
            refresh(autoBrowseSongChanges(previous.songs, current.songs, memberships))
        }
    }
    owner.launch {
        combine(database.allPlaylistsForDownloads(), database.allPlaylistSongMapsForDownloads()) { playlists, maps ->
            val entries = maps.groupBy { it.playlistId }
            playlists.associate { it.id to AutoBrowsePlaylistContent(it.playlist, entries[it.id].orEmpty()) }
        }.flowOn(Dispatchers.IO).observeChanges { previous, current -> refresh(autoBrowsePlaylistChanges(previous, current)) }
    }
    owner.launch {
        downloadUtil.downloads.map { downloads -> autoBrowseCompletedDownloadIds(downloads) { it.state } }
            .distinctUntilChanged().drop(1).collect { refresh(autoBrowseDownloadChanges) }
    }
    owner.launch { spotifyLibraryRepository.accountChanges.drop(1).collect { refreshSpotify() } }
    owner.launch { spotifyLibraryRepository.playlists.drop(1).collect { refreshSpotify() } }
    owner.launch {
        context.dataStore.data.map { it[ShowSpotifyPlaylistsKey] ?: false }
            .distinctUntilChanged().drop(1).collect { refreshSpotify() }
    }
    owner.launch {
        context.dataStore.data.map { it[AndroidAutoPinnedPlaylistsJsonKey].orEmpty() }
            .distinctUntilChanged().drop(1).collect { refresh(AutoBrowseContentChange(setOf(MusicService.PINNED_PLAYLISTS))) }
    }
}

private data class AutoBrowseSongSnapshot(
    val songs: Map<String, AutoBrowseSongContent>,
    val entries: List<PlaylistSongMap>,
)

private class ObservedValue<T>(val value: T)

private suspend fun <T> Flow<T>.observeChanges(changed: (T, T) -> Unit) {
    var previous: ObservedValue<T>? = null
    distinctUntilChanged().collect { value ->
        previous?.let { changed(it.value, value) }
        previous = ObservedValue(value)
    }
}
