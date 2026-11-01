@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package moe.rukamori.archivetune.ui.menu

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.db.entities.PlaylistSong

/**
 * The playlist rows of the song menu: move-to-top, move-to-bottom and remove-from-playlist, drawn only while the
 * menu was opened for a playlist row. The edge moves reposition locally and carry the successor-pointer move to a
 * linked YouTube playlist: the first row's setVideoId for top, null for bottom.
 */
@Composable
internal fun PlaylistSongMenuItems(
    playlistSong: PlaylistSong?,
    playlistBrowseId: String?,
    database: MusicDatabase,
    dividerModifier: Modifier,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    // The playlist's last position decides whether "Move to bottom" would move anything.
    val playlistMaxPosition by produceState<Int?>(initialValue = null, playlistSong?.map?.playlistId) {
        val playlistId = playlistSong?.map?.playlistId ?: return@produceState
        value =
            try {
                withContext(Dispatchers.IO) {
                    database.getPlaylistSongs(playlistId).maxOfOrNull { it.map.position }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
    }
    listOf(
        Triple(
            playlistSong != null && playlistSong.map.position > 0,
            R.string.move_to_top,
            R.drawable.simpmusic_keyboard_double_arrow_up,
        ),
        Triple(
            playlistSong != null &&
                playlistSong.map.position <
                    (playlistMaxPosition ?: playlistSong.map.position),
            R.string.move_to_bottom,
            R.drawable.simpmusic_keyboard_double_arrow_down,
        ),
    ).forEachIndexed { edge, (shown, label, icon) ->
        if (shown && playlistSong != null) {
            ListItem(
                headlineContent = { Text(text = stringResource(label)) },
                leadingContent = {
                    Icon(painter = painterResource(icon), contentDescription = null)
                },
                modifier =
                    Modifier.clickable {
                        val map = playlistSong.map
                        coroutineScope.launch(Dispatchers.IO) {
                            val result =
                                moveSongToPlaylistEdge(
                                    database,
                                    map,
                                    playlistBrowseId,
                                    toTop = edge == 0,
                                )
                            withContext(Dispatchers.Main) {
                                if (result.isFailure) {
                                    Toast
                                        .makeText(
                                            context,
                                            context.getString(R.string.error_unknown),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                }
                                onDismiss()
                            }
                        }
                    },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
    }

    if (playlistSong != null) {
        ListItem(
            headlineContent = {
                Text(
                    text = stringResource(R.string.remove_from_playlist),
                    color = MaterialTheme.colorScheme.error,
                )
            },
            leadingContent = {
                Icon(
                    painter = painterResource(R.drawable.delete),
                    tint = MaterialTheme.colorScheme.error,
                    contentDescription = null,
                )
            },
            modifier =
                Modifier.clickable {
                    val map = playlistSong.map
                    coroutineScope.launch(Dispatchers.IO) {
                        val browseId = playlistBrowseId
                        if (browseId != null) {
                            val remoteResult = removeSongFromRemotePlaylist(browseId, map)
                            if (remoteResult.isFailure) {
                                withContext(Dispatchers.Main) {
                                    Toast
                                        .makeText(
                                            context,
                                            context.getString(R.string.error_unknown),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    onDismiss()
                                }
                                return@launch
                            }
                        }
                        database.withTransaction {
                            val maxPosition = maxPlaylistSongPosition(map.playlistId) ?: map.position
                            if (map.position < maxPosition) {
                                move(map.playlistId, map.position, maxPosition)
                            }
                            delete(map)
                        }
                        withContext(Dispatchers.Main) {
                            onDismiss()
                        }
                    }
                },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )

        HorizontalDivider(
            modifier = dividerModifier,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}
