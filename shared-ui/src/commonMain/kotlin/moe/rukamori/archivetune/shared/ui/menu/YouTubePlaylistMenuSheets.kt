package moe.rukamori.archivetune.shared.ui.menu

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.LibraryMutation
import moe.rukamori.archivetune.shared.domain.repository.LibraryRepository
import moe.rukamori.archivetune.shared.domain.repository.QueueController
import moe.rukamori.archivetune.shared.ui.menu.youtubeplaylist.YouTubePlaylistMenu
import moe.rukamori.archivetune.shared.ui.menu.youtubeplaylist.YouTubePlaylistMenuFacts
import moe.rukamori.archivetune.shared.ui.menu.youtubeplaylist.YouTubePlaylistMenuHost
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.stringResource
import androidx.compose.runtime.setValue

/**
 * The shared add-to-playlist chooser for a remote YouTube playlist. It lists the host's editable local playlists,
 * loads the complete list through the sheet's loader, and commits one AddToPlaylist per choice. An empty or failed
 * load keeps the chooser open with the failure copy and commits nothing, so no success is reported for it.
 */
@Composable
fun YouTubePlaylistAddToChooser(
    editablePlaylists: List<ContentItem>,
    library: LibraryRepository,
    loadSongs: suspend () -> List<ContentOccurrence>,
    onAdded: (songCount: Int, playlistNames: List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val picker = LocalPlaylistPickerContext.current
    if (picker != null) {
        // The multi-playlist chooser needs the occurrences up front, so the lazy load resolves once for it.
        val loaded by produceState<List<ContentOccurrence>?>(null) {
            value = try {
                loadSongs().takeIf { it.isNotEmpty() } ?: emptyList()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
        val songs = loaded
        if (songs.isNullOrEmpty()) {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(Res.string.m6_add_playlist)) },
                text = {
                    Text(
                        stringResource(
                            if (songs == null) Res.string.m4_loading else Res.string.m6yp_playlist_action_failed,
                        ),
                    )
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.m6_cancel)) } },
            )
        } else {
            PlaylistPickerDialog(
                occurrences = songs,
                context = picker,
                library = library,
                onFinished = { names ->
                    if (names.isNotEmpty()) onAdded(songs.size, names)
                    onDismiss()
                },
            )
        }
        return
    }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(Res.string.m6_add_playlist)) },
        text = {
            Column {
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    items(editablePlaylists, key = { it.ref }) { playlist ->
                        TextButton(
                            enabled = !saving,
                            onClick = {
                                if (!saving) {
                                    saving = true
                                    failed = false
                                    scope.launch {
                                        try {
                                            val songs = loadSongs()
                                            require(songs.isNotEmpty()) { "Empty playlist" }
                                            library.mutate(LibraryMutation.AddToPlaylist(playlist.ref.id, songs))
                                            onAdded(songs.size, listOf(playlist.title))
                                            onDismiss()
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (_: Exception) {
                                            failed = true
                                        } finally {
                                            saving = false
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.testTag("playlist:${playlist.ref.id}"),
                        ) { Text(playlist.title) }
                    }
                }
                if (failed) Text(stringResource(Res.string.m6yp_playlist_action_failed))
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(enabled = !saving, onClick = onDismiss) { Text(stringResource(Res.string.m6_cancel)) }
        },
    )
}

/**
 * The remote YouTube playlist sheet bound to M6's shared chooser. The sheet owns its loader and command state through
 * [host]. This binding supplies only the host's editable local playlists and the library that commits a chosen copy;
 * [queue], [sharing] and [artwork] stay the caller's seams.
 */
@Composable
fun YouTubePlaylistOverflowSheet(
    facts: YouTubePlaylistMenuFacts,
    songs: List<ContentItem>,
    host: YouTubePlaylistMenuHost,
    queue: QueueController,
    sharing: MenuShareHandler,
    library: LibraryRepository,
    editablePlaylists: List<ContentItem>,
    canSelect: Boolean,
    onSelect: () -> Unit,
    onDismiss: () -> Unit,
    artwork: @Composable () -> Unit,
) = YouTubePlaylistMenu(
    facts = facts,
    songs = songs,
    host = host,
    queue = queue,
    sharing = sharing,
    canSelect = canSelect,
    onSelect = onSelect,
    onDismiss = onDismiss,
    artwork = artwork,
    addToPlaylistChooser = { loadSongs, onAdded, dismiss ->
        YouTubePlaylistAddToChooser(editablePlaylists, library, loadSongs, onAdded, dismiss)
    },
)
