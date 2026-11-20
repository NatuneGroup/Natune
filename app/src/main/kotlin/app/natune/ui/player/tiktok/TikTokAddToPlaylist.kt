/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



/*
 * TikTok player style — playlist picker for the rail's bookmark action.
 *
 * Thin wrapper over the app's shared AddToPlaylistDialog (app infrastructure,
 * not another player style's component): registers the tapped page's song in
 * the library if it isn't there yet, then lets the user pick playlists — the
 * same flow, toasts included, as the player menu's "Add to playlist".
 */

package app.natune.ui.player.tiktok

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import app.natune.LocalDatabase
import app.natune.R
import app.natune.models.MediaMetadata
import app.natune.ui.menu.AddToPlaylistDialog

@Composable
internal fun TikTokAddToPlaylist(
    song: MediaMetadata,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val database = LocalDatabase.current

    AddToPlaylistDialog(
        isVisible = true,
        onGetSong = {
            database.withTransaction { insert(song) }
            listOf(song.id)
        },
        onDismiss = onDismiss,
        onAddComplete = { _, playlistNames ->
            val message =
                when {
                    playlistNames.size == 1 ->
                        context.getString(R.string.added_to_playlist, playlistNames.first())

                    else -> context.getString(R.string.added_to_n_playlists, playlistNames.size)
                }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        },
    )
}
