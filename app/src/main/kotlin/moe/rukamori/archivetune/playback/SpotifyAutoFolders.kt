package moe.rukamori.archivetune.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.spotify.spotifyMetadataExtensionEnabled

/** Native metadata enablement also controls cached Android Auto folders. */
internal fun spotifyAutoLikedFolder(
    context: Context,
    showSpotifyPlaylists: Boolean,
    icon: Uri,
    browsable: (String, String, String?, Uri?, Int) -> MediaItem,
): List<MediaItem> {
    if (!showSpotifyPlaylists || !context.spotifyMetadataExtensionEnabled()) return emptyList()
    return listOf(browsable(MusicService.SPOTIFY_LIKED, context.getString(R.string.spotify_liked_songs),
        null, icon, MediaMetadata.MEDIA_TYPE_PLAYLIST))
}
