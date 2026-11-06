/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * ArchiveTune (2026) | Original work by © Rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * YumaPlayer (2026) | Modified work by MuwMx
 */


package moe.rukamori.archivetune.spotify

import androidx.compose.runtime.Immutable
import moe.rukamori.archivetune.spotify.models.SpotifyAlbum
import moe.rukamori.archivetune.spotify.models.SpotifyArtist
import moe.rukamori.archivetune.spotify.models.SpotifyPlaylist
import moe.rukamori.archivetune.spotify.models.SpotifyTrack

/**
 * One shelf of the Spotify home. The shape is YumaPlayer's (`spotify/SpotifyHomeSection.kt`): a shelf
 * carries the one kind its [type] names, and the other three lists stay empty. The home screen picks
 * the row by [type] and never inspects the lists.
 */
@Immutable
data class SpotifyHomeSection(
    val title: String,
    val type: SectionType,
    val tracks: List<SpotifyTrack> = emptyList(),
    val artists: List<SpotifyArtist> = emptyList(),
    val albums: List<SpotifyAlbum> = emptyList(),
    val playlists: List<SpotifyPlaylist> = emptyList(),
)

/** The row a shelf draws: two rows of track tiles, or a single rail of cards. */
enum class SectionType {
    TRACKS,
    ARTISTS,
    ALBUMS,
    PLAYLISTS,
}
