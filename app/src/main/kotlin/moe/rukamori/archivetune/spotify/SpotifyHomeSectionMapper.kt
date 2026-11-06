/*
 * Ported from YumaPlayer's Spotify home, MuwMx/YumaPlayer at 6eb6438 (GPL-3.0), file
 * spotify/SpotifyHomeSectionMapper.kt. See docs/CREDITS.md.
 */

package moe.rukamori.archivetune.spotify

import moe.rukamori.archivetune.spotify.models.SpotifyAlbum
import moe.rukamori.archivetune.spotify.models.SpotifyArtist
import moe.rukamori.archivetune.spotify.models.SpotifyHomeFeedItem
import moe.rukamori.archivetune.spotify.models.SpotifyHomeFeedSection
import moe.rukamori.archivetune.spotify.models.SpotifyImage
import moe.rukamori.archivetune.spotify.models.SpotifyPlaylist
import moe.rukamori.archivetune.spotify.models.SpotifyPlaylistOwner
import moe.rukamori.archivetune.spotify.models.SpotifyPlaylistTracksRef

/**
 * A feed section as a home shelf: every item the section carries, in the order Spotify sent them,
 * each tagged with the kind it draws as. A section with no title draws nothing.
 *
 * YumaPlayer's mapper kept only the kind the section held most of and dropped the rest, which is
 * where a six-playlist-four-album shelf lost the albums; this keeps them all.
 */
internal fun convertHomeSection(feedSection: SpotifyHomeFeedSection): SpotifyHomeSection? {
    val title = feedSection.title ?: return null

    val items =
        feedSection.items.map { item ->
            when (item) {
                is SpotifyHomeFeedItem.Playlist -> SpotifyHomeSectionItem.Playlist(item.toPlaylist())
                is SpotifyHomeFeedItem.Album -> SpotifyHomeSectionItem.Album(item.toAlbum())
                is SpotifyHomeFeedItem.Artist -> SpotifyHomeSectionItem.Artist(item.toArtist())
            }
        }

    return items.takeIf { it.isNotEmpty() }?.let { SpotifyHomeSection(title = title, items = it) }
}

private fun SpotifyHomeFeedItem.Playlist.toPlaylist() =
    SpotifyPlaylist(
        id = id,
        name = name,
        description = description,
        images = listOfNotNull(imageUrl?.let { url -> SpotifyImage(url) }),
        owner = ownerName?.let { owner -> SpotifyPlaylistOwner(id = "", displayName = owner) },
        tracks = SpotifyPlaylistTracksRef(total = totalCount),
        uri = uri,
    )

private fun SpotifyHomeFeedItem.Album.toAlbum() =
    SpotifyAlbum(
        id = id,
        name = name,
        albumType = albumType,
        artists = artists,
        images = listOfNotNull(imageUrl?.let { url -> SpotifyImage(url) }),
        uri = uri,
    )

private fun SpotifyHomeFeedItem.Artist.toArtist() =
    SpotifyArtist(
        id = id,
        name = name,
        images = listOfNotNull(imageUrl?.let { url -> SpotifyImage(url) }),
        uri = uri,
    )
