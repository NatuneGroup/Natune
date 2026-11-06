/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.viewmodels

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.datasource.cache.Cache
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.constants.HideExplicitKey
import moe.rukamori.archivetune.constants.HideVideoKey
import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.db.entities.Song
import moe.rukamori.archivetune.di.DownloadCache
import moe.rukamori.archivetune.di.PlayerCache
import moe.rukamori.archivetune.extensions.filterExplicit
import moe.rukamori.archivetune.extensions.filterVideo
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.get
import java.time.LocalDateTime
import javax.inject.Inject

/** SQLite on Android 11 and older binds at most 999 variables per statement (LocalSongScanner.SqlBatchSize). */
private const val SongIdQueryBatchSize = 900
private const val LogTag = "CachePlaylist"

/**
 * The cached-only ids in statement-sized batches: Room expands `id IN (:songIds)` into one bind
 * variable per element, while the player cache keeps every media id it has ever written, so a
 * 1 GiB cache can hand this query far more ids than the platform accepts in one statement.
 */
internal fun pureCacheSongBatches(
    cachedIds: Set<String>,
    downloadedIds: Set<String>,
): List<List<String>> = cachedIds.subtract(downloadedIds).chunked(SongIdQueryBatchSize)

@HiltViewModel
class CachePlaylistViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val database: MusicDatabase,
        @PlayerCache private val playerCache: Cache,
        @DownloadCache private val downloadCache: Cache,
    ) : ViewModel() {
        private val _cachedSongs = MutableStateFlow<List<Song>>(emptyList())
        val cachedSongs: StateFlow<List<Song>> = _cachedSongs

        init {
            viewModelScope.launch(Dispatchers.IO) {
                while (true) {
                    // SongMenu injects this ViewModel only to call removeSongFromCache, and nothing
                    // there observes the list; wait for the cache screen instead of polling for the
                    // lifetime of whichever screen created the ViewModel.
                    _cachedSongs.subscriptionCount.first { it > 0 }
                    while (_cachedSongs.subscriptionCount.value > 0) {
                        // viewModelScope is a supervisor scope: an escaping SQLite failure would
                        // reach the thread's uncaught-exception handler and kill the process.
                        // Contain the tick, and keep the delay outside so cancellation still ends
                        // the loop.
                        runCatching { refresh() }
                            .onFailure { error ->
                                if (error !is CancellationException) {
                                    Log.w(LogTag, "Cached playlist refresh failed", error)
                                }
                            }
                        delay(1000)
                    }
                }
            }
        }

        private suspend fun refresh() {
            val hideExplicit = context.dataStore.get(HideExplicitKey, false)
            val hideVideo = context.dataStore.get(HideVideoKey, false)
            val cachedIds = playerCache.keys.toSet()
            val downloadedIds = downloadCache.keys.toSet()

            val songs =
                pureCacheSongBatches(cachedIds, downloadedIds)
                    .flatMap { batch -> database.getSongsByIds(batch) }

            val completeSongs =
                songs.filter {
                    val contentLength = it.format?.contentLength
                    contentLength != null && playerCache.isCached(it.song.id, 0, contentLength)
                }

            if (completeSongs.isNotEmpty()) {
                database.query {
                    completeSongs.forEach {
                        if (it.song.dateDownload == null) {
                            update(it.song.copy(dateDownload = LocalDateTime.now()))
                        }
                    }
                }
            }

            _cachedSongs.value =
                completeSongs
                    .filter { it.song.dateDownload != null }
                    .sortedByDescending { it.song.dateDownload }
                    .filterExplicit(hideExplicit)
                    .filterVideo(hideVideo)
        }

        fun removeSongFromCache(songId: String) {
            playerCache.removeResource(songId)
        }
    }
