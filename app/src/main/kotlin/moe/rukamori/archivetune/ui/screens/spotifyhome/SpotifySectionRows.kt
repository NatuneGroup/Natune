/*
 * Ported from YumaPlayer's Spotify home, MuwMx/YumaPlayer at 6eb6438 (GPL-3.0),
 * ui/screens/spotifyhome/SpotifySectionRows.kt. See docs/CREDITS.md.
 */

package moe.rukamori.archivetune.ui.screens.spotifyhome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.GridThumbnailCornerRadius
import moe.rukamori.archivetune.spotify.SpotifyHomeSection
import moe.rukamori.archivetune.spotify.SpotifyHomeSectionItem
import moe.rukamori.archivetune.spotify.models.SpotifyAlbum
import moe.rukamori.archivetune.spotify.models.SpotifyArtist
import moe.rukamori.archivetune.spotify.models.SpotifyPlaylist
import moe.rukamori.archivetune.spotify.models.SpotifyTrack
import moe.rukamori.archivetune.ui.component.GridItem
import moe.rukamori.archivetune.ui.component.ItemThumbnail
import moe.rukamori.archivetune.ui.component.SpotifyTrackListItem
import moe.rukamori.archivetune.ui.theme.yumaClickable
import moe.rukamori.archivetune.utils.joinByBullet

/** A shelf's title: Spotify sends a key for its own shelves and plain text otherwise. */
@Composable
internal fun resolveSpotifySectionTitle(section: SpotifyHomeSection): String {
    val title = section.title
    return when {
        title.startsWith("spotify_because_you_like:") -> {
            val artistName = title.removePrefix("spotify_because_you_like:")
            stringResource(R.string.spotify_because_you_like, artistName)
        }
        title == "spotify_top_tracks" -> stringResource(R.string.spotify_top_tracks)
        title == "spotify_top_artists" -> stringResource(R.string.spotify_top_artists)
        title == "spotify_made_for_you" -> stringResource(R.string.spotify_made_for_you)
        title == "spotify_discover" -> stringResource(R.string.spotify_discover)
        title == "spotify_your_playlists" -> stringResource(R.string.spotify_your_playlists)
        title == "spotify_new_releases" -> stringResource(R.string.spotify_new_releases)
        else -> title
    }
}

/**
 * One shelf. A shelf of nothing but tracks keeps YumaPlayer's two-row track grid; any other shelf,
 * including one that mixes albums with playlists, draws a single rail and renders every item as the
 * kind it carries. That is the one place this differs from the reference, which drew a single kind
 * per shelf and dropped the items of every other kind.
 */
@Composable
internal fun SpotifySectionRow(
    section: SpotifyHomeSection,
    onTrackClick: (SpotifyTrack) -> Unit,
    onArtistClick: (SpotifyArtist) -> Unit,
    onAlbumClick: (SpotifyAlbum) -> Unit,
    onPlaylistClick: (SpotifyPlaylist) -> Unit,
    modifier: Modifier = Modifier,
    activeTrackId: String? = null,
    isPlaying: Boolean = false,
    resolvingItemKey: String? = null,
) {
    val items = section.items
    val tracks = items.mapNotNull { item -> (item as? SpotifyHomeSectionItem.Track)?.track }
    if (items.isNotEmpty() && tracks.size == items.size) {
        SpotifyTrackSectionRow(
            tracks = tracks,
            onTrackClick = onTrackClick,
            modifier = modifier,
            activeTrackId = activeTrackId,
            isPlaying = isPlaying,
            resolvingItemKey = resolvingItemKey,
        )
    } else {
        SpotifySectionRail(
            items = items,
            onTrackClick = onTrackClick,
            onArtistClick = onArtistClick,
            onAlbumClick = onAlbumClick,
            onPlaylistClick = onPlaylistClick,
            modifier = modifier,
            activeTrackId = activeTrackId,
            isPlaying = isPlaying,
            resolvingItemKey = resolvingItemKey,
        )
    }
}

/** Two rows of track tiles that scroll sideways, each tile a list row with its own artwork. */
@Composable
private fun SpotifyTrackSectionRow(
    tracks: List<SpotifyTrack>,
    onTrackClick: (SpotifyTrack) -> Unit,
    modifier: Modifier = Modifier,
    activeTrackId: String? = null,
    isPlaying: Boolean = false,
    resolvingItemKey: String? = null,
) {
    LazyHorizontalGrid(
        state = rememberLazyGridState(),
        rows = GridCells.Fixed(2),
        contentPadding = PaddingValues(horizontal = 16.dp),
        modifier =
            modifier
                .fillMaxWidth()
                .height(64.dp * 4),
    ) {
        items(
            items = tracks,
            key = { "spotify_track_${it.id}" },
            contentType = { "spotify_track" },
        ) { track ->
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier =
                    Modifier
                        .fillMaxHeight()
                        .width(240.dp),
            ) {
                SpotifyTrackListItem(
                    track = track,
                    isActive = activeTrackId == track.id,
                    isPlaying = isPlaying,
                    trailingContent = {
                        if (resolvingItemKey == "track:${track.id}") SpotifySelectionIndicator()
                    },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .yumaClickable(onClick = { onTrackClick(track) }),
                )
            }
        }
    }
}

/**
 * A rail of whatever the shelf holds, each item in the order Spotify sent it and drawn as its own
 * kind: round portraits for artists, square cards for albums, playlists and tracks.
 */
@Composable
private fun SpotifySectionRail(
    items: List<SpotifyHomeSectionItem>,
    onTrackClick: (SpotifyTrack) -> Unit,
    onArtistClick: (SpotifyArtist) -> Unit,
    onAlbumClick: (SpotifyAlbum) -> Unit,
    onPlaylistClick: (SpotifyPlaylist) -> Unit,
    modifier: Modifier = Modifier,
    activeTrackId: String? = null,
    isPlaying: Boolean = false,
    resolvingItemKey: String? = null,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier,
    ) {
        items(
            items = items,
            key = { it.railKey },
            contentType = { it.railContentType },
        ) { item ->
            when (item) {
                is SpotifyHomeSectionItem.Track ->
                    SpotifyTrackCard(
                        track = item.track,
                        onTrackClick = onTrackClick,
                        activeTrackId = activeTrackId,
                        isPlaying = isPlaying,
                        resolvingItemKey = resolvingItemKey,
                    )
                is SpotifyHomeSectionItem.Artist ->
                    SpotifyArtistCard(
                        artist = item.artist,
                        onArtistClick = onArtistClick,
                        resolvingItemKey = resolvingItemKey,
                    )
                is SpotifyHomeSectionItem.Album ->
                    SpotifyAlbumCard(
                        album = item.album,
                        onAlbumClick = onAlbumClick,
                        resolvingItemKey = resolvingItemKey,
                    )
                is SpotifyHomeSectionItem.Playlist ->
                    SpotifyPlaylistCard(
                        playlist = item.playlist,
                        onPlaylistClick = onPlaylistClick,
                    )
            }
        }
    }
}

/** The rail key an item saves and restores by, prefixed with its kind so a mixed shelf stays unique. */
private val SpotifyHomeSectionItem.railKey: String
    get() =
        when (this) {
            is SpotifyHomeSectionItem.Track -> "spotify_track_${track.id}"
            is SpotifyHomeSectionItem.Artist -> "spotify_artist_${artist.id}"
            is SpotifyHomeSectionItem.Album -> "spotify_album_${album.id}"
            is SpotifyHomeSectionItem.Playlist -> "spotify_playlist_${playlist.id}"
        }

private val SpotifyHomeSectionItem.railContentType: String
    get() =
        when (this) {
            is SpotifyHomeSectionItem.Track -> "spotify_track"
            is SpotifyHomeSectionItem.Artist -> "spotify_artist"
            is SpotifyHomeSectionItem.Album -> "spotify_album"
            is SpotifyHomeSectionItem.Playlist -> "spotify_playlist"
        }

/** A track in a mixed shelf: the square card the album and playlist cards use, from its album art. */
@Composable
private fun SpotifyTrackCard(
    track: SpotifyTrack,
    onTrackClick: (SpotifyTrack) -> Unit,
    activeTrackId: String?,
    isPlaying: Boolean,
    resolvingItemKey: String?,
) {
    val thumbnail =
        remember(track.id) {
            track.album?.images?.maxByOrNull { it.width ?: 0 }?.url
                ?: track.album?.images?.firstOrNull()?.url
                ?: ""
        }
    GridItem(
        title = track.name,
        subtitle = track.artists.joinToString { it.name },
        fillMaxWidth = true,
        thumbnailContent = {
            ItemThumbnail(
                thumbnailUrl = thumbnail,
                isActive = activeTrackId == track.id,
                isPlaying = isPlaying,
                shape = RoundedCornerShape(GridThumbnailCornerRadius),
                placeholderIconRes = R.drawable.music_note,
            )
            if (resolvingItemKey == "track:${track.id}") SpotifySelectionIndicator()
        },
        modifier =
            Modifier
                .width(150.dp)
                .yumaClickable(onClick = { onTrackClick(track) }),
    )
}

/** A round artist portrait with the name beneath it. */
@Composable
private fun SpotifyArtistCard(
    artist: SpotifyArtist,
    onArtistClick: (SpotifyArtist) -> Unit,
    resolvingItemKey: String?,
) {
    val thumbnail =
        remember(artist.id) {
            artist.images.maxByOrNull { it.width ?: 0 }?.url
                ?: artist.images.firstOrNull()?.url
        }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            Modifier
                .width(140.dp)
                .yumaClickable(onClick = { onArtistClick(artist) }),
    ) {
        Box(contentAlignment = Alignment.Center) {
            AsyncImage(
                model = thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier
                        .size(140.dp)
                        .clip(CircleShape),
            )
            if (resolvingItemKey == "artist:${artist.id}") SpotifySelectionIndicator()
        }
        Text(
            text = artist.name,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** A square album card: artwork, the release's name and its artists. */
@Composable
private fun SpotifyAlbumCard(
    album: SpotifyAlbum,
    onAlbumClick: (SpotifyAlbum) -> Unit,
    resolvingItemKey: String?,
) {
    val thumbnail =
        remember(album.id) {
            album.images.maxByOrNull { it.width ?: 0 }?.url ?: album.images.firstOrNull()?.url ?: ""
        }
    GridItem(
        title = album.name,
        subtitle = album.artists.joinToString { it.name },
        fillMaxWidth = true,
        thumbnailContent = {
            ItemThumbnail(
                thumbnailUrl = thumbnail,
                isActive = false,
                isPlaying = false,
                shape = RoundedCornerShape(GridThumbnailCornerRadius),
                placeholderIconRes = R.drawable.music_note,
            )
            if (resolvingItemKey == "album:${album.id}") SpotifySelectionIndicator()
        },
        modifier =
            Modifier
                .width(150.dp)
                .yumaClickable(onClick = { onAlbumClick(album) }),
    )
}

/** A square playlist card: artwork, the playlist's name, its owner and its size. */
@Composable
private fun SpotifyPlaylistCard(
    playlist: SpotifyPlaylist,
    onPlaylistClick: (SpotifyPlaylist) -> Unit,
) {
    val thumbnail =
        remember(playlist.id) {
            playlist.images.maxByOrNull { it.width ?: 0 }?.url ?: playlist.images.firstOrNull()?.url ?: ""
        }
    GridItem(
        title = playlist.name,
        subtitle = joinByBullet(playlist.owner?.displayName, playlist.tracks?.total?.toString()),
        fillMaxWidth = true,
        thumbnailContent = {
            ItemThumbnail(
                thumbnailUrl = thumbnail,
                isActive = false,
                isPlaying = false,
                shape = RoundedCornerShape(GridThumbnailCornerRadius),
                placeholderIconRes = R.drawable.music_note,
            )
        },
        modifier =
            Modifier
                .width(150.dp)
                .yumaClickable(onClick = { onPlaylistClick(playlist) }),
    )
}
