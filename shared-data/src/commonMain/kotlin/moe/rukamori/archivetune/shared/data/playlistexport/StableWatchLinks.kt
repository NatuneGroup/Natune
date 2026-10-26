/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.data.playlistexport

import moe.rukamori.archivetune.shared.domain.playlistexport.PlaylistExportTrack
import moe.rukamori.archivetune.shared.domain.playlistexport.PlaylistTrackLocations
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider

/**
 * The stable YouTube watch link that the baseline share and menu code already uses. It is the only M3U location this
 * module builds itself, and it is built from the stored video id, never from a signed or session-bound stream URL.
 * Other providers have no such link here, so their tracks are refused unless the host supplies a location.
 */
object StableWatchLinks : PlaylistTrackLocations {
    private const val PREFIX = "https://music.youtube.com/watch?v="
    private const val VIDEO_ID_LENGTH = 11

    override suspend fun locate(track: PlaylistExportTrack): String? {
        val ref = track.ref ?: return null
        return if (ref.provider == ContentProvider.YOUTUBE.id && isVideoId(ref.id)) PREFIX + ref.id else null
    }

    /**
     * The video id that [uri] names when it is exactly a stable watch link, or null for anything else. The import uses
     * this to find the song, and it never fetches the link.
     */
    fun videoIdOf(uri: String): String? {
        if (!uri.startsWith(PREFIX)) return null
        return uri.substring(PREFIX.length).takeIf { isVideoId(it) }
    }

    private fun isVideoId(id: String): Boolean =
        id.length == VIDEO_ID_LENGTH && id.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' }
}
