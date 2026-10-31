package moe.rukamori.archivetune.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.LocalDatabase
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.AutoLoadMoreKey
import moe.rukamori.archivetune.db.entities.PlaylistEntity
import moe.rukamori.archivetune.db.entities.PlaylistSongMap
import moe.rukamori.archivetune.extensions.metadata
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.ui.component.TextFieldDialog
import moe.rukamori.archivetune.utils.makeTimeString
import moe.rukamori.archivetune.utils.rememberPreference
import java.time.LocalDateTime

/**
 * Queue actions shared by the host's custom queue surfaces; their transports remain style-owned.
 * One row: the source and counts take the width the actions leave. The autoplay toggle sits in the overflow menu,
 * so a large font scale wraps nothing and the bar stays one row tall.
 */
@Composable
internal fun QueueExtrasBar(
    onJumpToCurrent: () -> Unit,
    modifier: Modifier = Modifier,
    contentColor: Color = LocalContentColor.current,
    showInfinity: Boolean = true,
    active: Boolean = true,
) {
    val connection = LocalPlayerConnection.current ?: return
    val database = LocalDatabase.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val windows by connection.queueWindows.collectAsStateWithLifecycle()
    val currentIndex by connection.currentWindowIndex.collectAsStateWithLifecycle()
    val title by connection.queueTitle.collectAsStateWithLifecycle()
    val loading by connection.service.infiniteQueueLoading.collectAsStateWithLifecycle()
    var infinite by rememberPreference(AutoLoadMoreKey, true)
    var positionMs by remember { mutableStateOf(0L) }
    LaunchedEffect(active, currentIndex) {
        while (active && isActive) {
            positionMs = connection.player.currentPosition.coerceAtLeast(0L)
            delay(1_000L)
        }
    }
    val metadata = windows.mapNotNull { it.mediaItem.metadata }
    val durationKnown = metadata.size == windows.size && metadata.all { it.duration > 0 }
    val totalMs = metadata.sumOf { it.duration.toLong() * 1_000L }
    val remaining = metadata.drop(currentIndex.coerceAtLeast(0)).sumOf { it.duration.toLong() * 1_000L }
        .minus(positionMs).coerceAtLeast(0L)
    var menu by remember { mutableStateOf(false) }
    var saveSnapshot by remember { mutableStateOf<List<MediaMetadata>?>(null) }
    val snackbar = remember { SnackbarHostState() }
    var undoJob by remember { mutableStateOf<Job?>(null) }

    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                title?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        stringResource(R.string.queue_source_title, it),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = contentColor,
                    )
                }
                Text(
                    pluralStringResource(R.plurals.n_song, windows.size, windows.size) + " · " +
                        (if (durationKnown) makeTimeString(totalMs) else stringResource(R.string.unknown)) + " · " +
                        stringResource(
                            R.string.queue_time_remaining,
                            if (durationKnown) makeTimeString(remaining) else stringResource(R.string.unknown),
                        ),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = contentColor.copy(alpha = 0.7f),
                )
            }
            if (showInfinity && loading) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = contentColor)
            }
            IconButton(onClick = { saveSnapshot = metadata.toList() }, enabled = metadata.isNotEmpty()) {
                Icon(painterResource(R.drawable.playlist_add), stringResource(R.string.queue_save_playlist), tint = contentColor)
            }
            IconButton(onClick = onJumpToCurrent, enabled = currentIndex >= 0) {
                Icon(painterResource(R.drawable.location_on), stringResource(R.string.queue_jump_current), tint = contentColor)
            }
            Column {
                IconButton(onClick = { menu = true }) {
                    Icon(painterResource(R.drawable.more_vert), stringResource(R.string.queue_actions), tint = contentColor)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (showInfinity) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.queue_infinite)) },
                            leadingIcon = { Icon(painterResource(R.drawable.all_inclusive), null) },
                            trailingIcon = { Checkbox(checked = infinite, onCheckedChange = null) },
                            onClick = {
                                menu = false
                                val enabled = !infinite
                                infinite = enabled
                                if (!enabled) connection.service.onInfiniteQueueDisabled()
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.queue_clear_upcoming)) },
                        onClick = {
                            menu = false
                            infinite = false
                            connection.service.clearQueueWithUndo()?.let { removal ->
                                undoJob?.cancel()
                                undoJob = scope.launch {
                                    val result = snackbar.showSnackbar(
                                        context.getString(R.string.removed_n_songs_from_queue, removal.count),
                                        context.getString(R.string.undo), duration = SnackbarDuration.Short,
                                    )
                                    if (result == SnackbarResult.ActionPerformed) connection.service.undoQueueRemoval(removal.ticket)
                                }
                            }
                        },
                    )
                }
            }
        }
        SnackbarHost(snackbar)
    }
    saveSnapshot?.let { songs ->
        TextFieldDialog(
            title = { Text(stringResource(R.string.queue_save_playlist)) },
            placeholder = { Text(stringResource(R.string.playlist_name)) },
            initialTextFieldValue = TextFieldValue(title ?: context.getString(R.string.queue)),
            isInputValid = { it.trim().isNotEmpty() && songs.isNotEmpty() },
            onDismiss = { saveSnapshot = null },
            onDone = { rawName ->
                saveSnapshot = null
                scope.launch {
                    val name = rawName.trim()
                    try {
                        withContext(Dispatchers.IO) {
                            val playlist = PlaylistEntity(name = name, bookmarkedAt = LocalDateTime.now(), isEditable = true)
                            database.withTransaction {
                                insert(playlist)
                                songs.forEachIndexed { index, song ->
                                    insert(song)
                                    insert(PlaylistSongMap(playlistId = playlist.id, songId = song.id, position = index, setVideoId = song.setVideoId))
                                }
                            }
                        }
                        snackbar.showSnackbar(context.getString(R.string.added_n_songs_to_playlist, songs.size, name))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        snackbar.showSnackbar(context.getString(R.string.queue_save_failed))
                    }
                }
            },
        )
    }
}
