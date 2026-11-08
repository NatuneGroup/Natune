package moe.rukamori.archivetune.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.spotify.spotifyMetadataExtensionEnabled

/** Apply the current gate after loading, including when later pages reuse a browse snapshot. */
internal fun <T> spotifyAutoVisibleChildren(children: List<T>, enabled: Boolean, mediaId: (T) -> String): List<T> =
    if (enabled) children else children.filterNot {
        val root = mediaId(it).substringBefore('/')
        root == MusicService.SPOTIFY_LIKED || root == MusicService.SPOTIFY_PLAYLIST
    }

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
