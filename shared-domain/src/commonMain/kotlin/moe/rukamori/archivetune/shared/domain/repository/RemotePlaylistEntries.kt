/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.repository

/** Lookup must use the existing strict whole-finite-list collector, not just its first page. */
interface RemotePlaylistEntries {
    suspend fun setVideoIds(playlistId: String, songId: String): List<String>
    suspend fun remove(playlistId: String, songId: String, setVideoId: String)
}
class AmbiguousPlaylistOccurrence : IllegalStateException("Remote playlist occurrence cannot be identified uniquely")
class RemoteRemovalNeedsReconciliation : IllegalStateException("Remote removal committed; local reconciliation required")
