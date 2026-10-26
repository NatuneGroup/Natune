package moe.rukamori.archivetune.shared.ui.menu.youtubeplaylist

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.queue.FinitePlaylistCompleteList
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.ui.resources.Res
import org.jetbrains.compose.resources.stringResource

/**
 * The shared loader dialog. The host owns every transition and this only renders them and forwards the
 * choices. Cancel while loading cancels the load, and a whole-list action never offers a partial reload.
 */
@Composable
fun YouTubePlaylistLoadDialog(host: YouTubePlaylistMenuHost) {
    val state by host.loadState.collectAsState()
    if (!state.showDialog || (!state.loading && !state.incomplete)) return
    AlertDialog(
        onDismissRequest = {
            if (state.loading) host.cancelLoading()
            host.dismissLoadError()
        },
        text = {
            Text(stringResource(if (state.loading) Res.string.m6yp_youtube_playlist_loading else Res.string.m6yp_youtube_playlist_incomplete))
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.End) {
                if (state.incomplete) {
                    TextButton(onClick = { host.retryLoading() }) {
                        Text(stringResource(Res.string.m6yp_youtube_playlist_retry))
                    }
                    if (!state.wholeAction) {
                        TextButton(onClick = { host.reloadFromBeginning() }) {
                            Text(stringResource(Res.string.m6yp_youtube_playlist_reload))
                        }
                    }
                }
                TextButton(onClick = {
                    if (state.loading) host.cancelLoading()
                    host.dismissLoadError()
                }) {
                    Text(stringResource(Res.string.m6_cancel))
                }
            }
        },
    )
}

/**
 * The in-sheet copy of the loader state, so a recovery path stays reachable while the dialog is closed.
 * It renders nothing unless a load is running or incomplete.
 */
@Composable
fun YouTubePlaylistRecoveryActions(host: YouTubePlaylistMenuHost) {
    val state by host.loadState.collectAsState()
    if (!state.loading && !state.incomplete) return
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            stringResource(if (state.loading) Res.string.m6yp_youtube_playlist_loading else Res.string.m6yp_youtube_playlist_incomplete),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (state.loading) {
            TextButton(onClick = { host.cancelLoading() }) {
                Text(stringResource(Res.string.m6yp_youtube_playlist_cancel))
            }
        } else {
            Row {
                TextButton(onClick = { host.retryLoading() }) {
                    Text(stringResource(Res.string.m6yp_youtube_playlist_retry))
                }
                if (!state.wholeAction) {
                    TextButton(onClick = { host.reloadFromBeginning() }) {
                        Text(stringResource(Res.string.m6yp_youtube_playlist_reload))
                    }
                }
            }
        }
    }
}

/** A duplicate found after the songs loaded. The import is held until the user chooses update or copy. */
private class PendingImport(
    val name: String,
    val songs: FinitePlaylistCompleteList<ContentOccurrence>,
    val localPlaylistId: String,
)

/**
 * Imports the playlist as a bookmarked, linked local record. An existing record for the same browse id is
 * found first, and the user then chooses between updating it and importing a copy.
 *
 * The baseline closed the name field as soon as it was confirmed, so the outcome appeared after the dialog
 * was gone. This dialog stays open until the write settles, and it reports failures inline. Cancel closes
 * the whole import, as the baseline did.
 */
@Composable
fun YouTubePlaylistImportDialog(
    facts: YouTubePlaylistMenuFacts,
    host: YouTubePlaylistMenuHost,
    onNotice: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val ref = facts.item.ref
    val scope = rememberCoroutineScope()
    var draft by remember(ref) { mutableStateOf(facts.item.title) }
    var importing by remember(ref) { mutableStateOf(false) }
    var failure by remember(ref) { mutableStateOf<String?>(null) }
    var duplicate by remember(ref) { mutableStateOf<PendingImport?>(null) }

    val syncedText = stringResource(Res.string.m6yp_playlist_synced)
    val importFailedText = stringResource(Res.string.m6yp_import_failed)
    val unknownErrorText = stringResource(Res.string.m6yp_unknown_error)
    val cancelText = stringResource(Res.string.m6_cancel)

    fun runImport(work: suspend () -> Unit) {
        if (importing) return
        importing = true
        failure = null
        scope.launch {
            try {
                work()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failure = "$importFailedText: ${error.message ?: unknownErrorText}"
            } finally {
                importing = false
            }
        }
    }

    val pending = duplicate
    if (pending == null) {
        AlertDialog(
            onDismissRequest = { if (!importing) onDismiss() },
            title = { Text(stringResource(Res.string.m6yp_import_playlist)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        singleLine = true,
                        enabled = !importing,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    failure?.let { message ->
                        Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (importing) {
                        CircularProgressIndicator(modifier = Modifier.padding(top = 12.dp).size(20.dp), strokeWidth = 2.dp)
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = !importing && draft.isNotBlank(), onClick = {
                    val name = draft.trim()
                    runImport {
                        val songs = host.loadComplete(ref)
                        if (songs.songs.isEmpty()) {
                            failure = importFailedText
                        } else {
                            val existing = host.findLocalPlaylistId(ref)
                            if (existing == null) {
                                host.importLinked(facts, name, songs)
                                onNotice(syncedText)
                                onDismiss()
                            } else {
                                host.ensureBookmarked(existing)
                                duplicate = PendingImport(name, songs, existing)
                            }
                        }
                    }
                }) {
                    Text(stringResource(Res.string.m6yp_ok))
                }
            },
            dismissButton = {
                TextButton(enabled = !importing, onClick = onDismiss) {
                    Text(cancelText)
                }
            },
        )
    } else {
        AlertDialog(
            onDismissRequest = { if (!importing) duplicate = null },
            title = { Text(stringResource(Res.string.m6yp_import_playlist)) },
            text = {
                Column {
                    Text(stringResource(Res.string.m6yp_already_in_playlist), style = MaterialTheme.typography.bodyMedium)
                    if (importing) {
                        CircularProgressIndicator(modifier = Modifier.padding(top = 12.dp).size(20.dp), strokeWidth = 2.dp)
                    }
                    failure?.let { message ->
                        Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.End) {
                    TextButton(enabled = !importing, onClick = {
                        duplicate = null
                        onDismiss()
                    }) {
                        Text(cancelText)
                    }
                    TextButton(enabled = !importing, onClick = {
                        runImport {
                            host.importLocalCopy(pending.name, pending.songs)
                            duplicate = null
                            onNotice(syncedText)
                            onDismiss()
                        }
                    }) {
                        Text(stringResource(Res.string.m6yp_import_playlist))
                    }
                    TextButton(enabled = !importing, onClick = {
                        runImport {
                            host.ensureBookmarked(pending.localPlaylistId)
                            host.addMissingSongs(pending.localPlaylistId, pending.songs)
                            duplicate = null
                            onNotice(syncedText)
                            onDismiss()
                        }
                    }) {
                        Text(stringResource(Res.string.m6yp_update_button))
                    }
                }
            },
        )
    }
}
