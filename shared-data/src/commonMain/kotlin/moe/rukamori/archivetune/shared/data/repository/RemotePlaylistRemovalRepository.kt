/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.data.repository

import kotlinx.coroutines.CancellationException
import moe.rukamori.archivetune.shared.domain.playlist.PlaylistOccurrence
import moe.rukamori.archivetune.shared.domain.repository.*

/** Call within the host's existing per-playlist mutation boundary; never hold a DB transaction over HTTP. */
class RemotePlaylistRemovalRepository(
    private val remote: RemotePlaylistEntries,
    private val removeLocal: suspend (playlistId: String, occurrence: PlaylistOccurrence) -> Unit,
) {
    suspend fun remove(playlistId: String, occurrence: PlaylistOccurrence) {
        require(playlistId.isNotBlank() && occurrence.songId.isNotBlank())
        val setId = occurrence.setVideoId?.takeIf { it.isNotBlank() } ?: run {
            val ids = remote.setVideoIds(playlistId, occurrence.songId).distinct().filter { it.isNotBlank() }
            // Unlike legacy remove-all fallback, an occurrence command cannot delete all duplicates.
            if (ids.size != 1) throw AmbiguousPlaylistOccurrence()
            ids.single()
        }
        remote.remove(playlistId, occurrence.songId, setId)
        try { removeLocal(playlistId, occurrence) }
        catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) { throw RemoteRemovalNeedsReconciliation() }
    }
}
