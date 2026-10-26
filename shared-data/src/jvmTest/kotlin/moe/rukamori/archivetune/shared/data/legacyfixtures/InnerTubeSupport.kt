/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.innertube.models

// Test-only signatures for unused conversion methods; none of these objects enters a Java fixture.
data class Artist(val id: String?, val name: String)
data class Album(val id: String, val name: String)
data class Podcast(val id: String?, val name: String)
data class SongItem(val id: String, val title: String, val artists: List<Artist>, val duration: Int?,
    val thumbnail: String, val album: Album?, val explicit: Boolean, val setVideoId: String?, val endpoint: WatchEndpoint?)
data class EpisodeItem(val id: String, val title: String, val podcast: Podcast?, val duration: Int?,
    val thumbnail: String, val endpoint: WatchEndpoint)
data class WatchEndpoint(val playlistSetVideoId: String?, val watchEndpointMusicSupportedConfigs: WatchEndpointMusicSupportedConfigs?) {
    data class WatchEndpointMusicSupportedConfigs(val watchEndpointMusicConfig: WatchEndpointMusicConfig?) {
        data class WatchEndpointMusicConfig(val musicVideoType: String?) {
            companion object {
                const val MUSIC_VIDEO_TYPE_OMV = "MUSIC_VIDEO_TYPE_OMV"
                const val MUSIC_VIDEO_TYPE_UGC = "MUSIC_VIDEO_TYPE_UGC"
            }
        }
    }
}
