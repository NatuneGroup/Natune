package moe.rukamori.archivetune.utils

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.db.entities.Playlist
import timber.log.Timber
import java.time.LocalDateTime

internal suspend fun MusicDatabase.cleanupDuplicateRemotePlaylists() =
    withContext(Dispatchers.IO) {
        try {
            val allPlaylists = playlistsByNameAsc().first()
            val browseIdGroups =
                allPlaylists
                    .filter { it.playlist.browseId != null }
                    .groupBy { it.playlist.browseId }

            for ((browseId, playlists) in browseIdGroups) {
                if (playlists.size > 1) {
                    Timber.w("Found ${playlists.size} duplicate playlists for browseId: $browseId")
                    val toKeep =
                        playlists.maxByOrNull { it.songCount }
                            ?: playlists.first()

                    playlists.filter { it.id != toKeep.id }.forEach { duplicate ->
                        Timber.d("Removing duplicate playlist: ${duplicate.playlist.name} (${duplicate.id})")
                        clearPlaylist(duplicate.id)
                        delete(duplicate.playlist)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Error cleaning up duplicate playlists")
        }
    }


private fun preferredPlaylistCopy(
    current: Playlist,
    candidate: Playlist,
): Playlist {
    val bySongCount = candidate.songCount.compareTo(current.songCount)
    if (bySongCount != 0) {
        return if (bySongCount > 0) candidate else current
    }

    val byLastUpdated = compareNullableDateTimes(candidate.playlist.lastUpdateTime, current.playlist.lastUpdateTime)
    if (byLastUpdated != 0) {
        return if (byLastUpdated > 0) candidate else current
    }

    val byCreatedAt = compareNullableDateTimes(candidate.playlist.createdAt, current.playlist.createdAt)
    if (byCreatedAt != 0) {
        return if (byCreatedAt > 0) candidate else current
    }

    if (candidate.playlist.isEditable != current.playlist.isEditable) {
        return if (candidate.playlist.isEditable) candidate else current
    }

    return if (candidate.id < current.id) candidate else current
}

private fun compareNullableDateTimes(
    first: LocalDateTime?,
    second: LocalDateTime?,
): Int {
    if (first == second) return 0
    if (first == null) return -1
    if (second == null) return 1
    return first.compareTo(second)
}

internal fun canonicalPlaylistCopies(playlists: List<Playlist>): List<Playlist> =
    playlists
        .groupBy { it.playlist.browseId ?: it.id }
        .values
        .map { copies ->
            copies.reduce(::preferredPlaylistCopy)
        }
