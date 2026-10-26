/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.kmp.data

import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.playback.queues.isFiniteYouTubePlaylist
import moe.rukamori.archivetune.playback.queues.loadCompleteYouTubePlaylist
import moe.rukamori.archivetune.shared.data.provider.readBoundedText
import moe.rukamori.archivetune.shared.domain.repository.RemotePlaylistEntries
import kotlinx.serialization.json.*

/** Reuses the real account/auth-recovery and strict finite collection owner. No new cookie store. */
class AndroidYouTubePlaylistEntries : RemotePlaylistEntries {
    override suspend fun setVideoIds(playlistId: String, songId: String): List<String> {
        require(isFiniteYouTubePlaylist(playlistId))
        // The core convenience lookup is an unbounded loop; reuse the actual playback/action
        // collector with its timeout, 200-request, repeated-token and unknown-envelope gates.
        return loadCompleteYouTubePlaylist(playlistId).songs.filter { it.id == songId }.mapNotNull { it.setVideoId }.distinct()
    }
    override suspend fun remove(playlistId: String, songId: String, setVideoId: String) {
        val response = YouTube.removeFromPlaylist(playlistId, songId, setVideoId).getOrThrow()
        check(response.status.value in 200..299) { "Remote playlist removal failed" }
        val raw = response.readBoundedText(64 * 1024)
        val status = try { Json.parseToJsonElement(raw).jsonObject["status"]?.jsonPrimitive?.content }
            catch (failure: Exception) { null }
        check(status == "STATUS_SUCCEEDED") { "Remote playlist removal did not confirm success" }
    }
}
