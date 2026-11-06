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
 * One shelf of the Spotify home, holding every item Spotify sent and in the order it sent them.
 *
 * YumaPlayer's shelf (`spotify/SpotifyHomeSection.kt`) kept the kind the section held most of and
 * dropped the rest, so a shelf such as "Jump back in" that mixes albums with playlists lost whole
 * releases. Here each item carries the kind it draws as and the shelf's row dispatches per item, so
 * a mixed shelf keeps its albums next to its playlists.
 */
@Immutable
data class SpotifyHomeSection(
    val title: String,
    val items: List<SpotifyHomeSectionItem>,
)

/** One item of a home shelf, tagged with the kind its row item draws as. */
@Immutable
sealed interface SpotifyHomeSectionItem {
    data class Track(val track: SpotifyTrack) : SpotifyHomeSectionItem

    data class Artist(val artist: SpotifyArtist) : SpotifyHomeSectionItem

    data class Album(val album: SpotifyAlbum) : SpotifyHomeSectionItem

    data class Playlist(val playlist: SpotifyPlaylist) : SpotifyHomeSectionItem
}
