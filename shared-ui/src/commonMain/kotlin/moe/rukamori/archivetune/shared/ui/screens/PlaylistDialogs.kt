package moe.rukamori.archivetune.shared.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.stringResource

/** Drafts remain controlled through failures; only the real committed-success owner dismisses them. */
data class CreatePlaylistUi(
    val name: String = "", val syncRequested: Boolean = false, val allowSyncing: Boolean = true,
    val signedIn: Boolean = false, val syncEnabled: Boolean = false,
    val loading: Boolean = false, val submitting: Boolean = false, val failed: Boolean = false,
)
class CreatePlaylistActions(
    val name: (String) -> Unit, val sync: (Boolean) -> Unit, val create: (String, Boolean) -> Unit, val dismiss: () -> Unit,
)

@Composable
fun CreatePlaylistDialog(state: CreatePlaylistUi, actions: CreatePlaylistActions) {
    val busy = state.loading || state.submitting
    val eligible = state.allowSyncing && state.signedIn && state.syncEnabled
    val sync = state.allowSyncing && state.syncRequested
    AlertDialog(onDismissRequest = { if (!busy) actions.dismiss() }, modifier = Modifier.testTag("create-playlist-dialog"),
        title = { Text(stringResource(Res.string.m4_create_playlist)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(state.name, actions.name, Modifier.testTag("create-playlist-name"), enabled = !busy,
                    label = { Text(stringResource(Res.string.m4_name)) }, singleLine = true)
                if (state.allowSyncing) {
                    Row {
                        Checkbox(state.syncRequested, actions.sync, enabled = !busy && (eligible || state.syncRequested))
                        Text(stringResource(Res.string.m4_sync_playlist))
                    }
                    Text(stringResource(when {
                        !state.signedIn -> Res.string.m4_sync_sign_in
                        !state.syncEnabled -> Res.string.m4_sync_disabled
                        else -> Res.string.m4_sync_description
                    }))
                }
                if (busy) Text(stringResource(Res.string.m4_saving))
                if (state.failed) Text(stringResource(Res.string.m4_playlist_write_failed), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton({ actions.create(state.name.trim(), sync) },
                enabled = !busy && state.name.isNotBlank() && (!sync || eligible),
                modifier = Modifier.testTag("create-playlist-submit")) { Text(stringResource(Res.string.m4_create_playlist)) }
        },
        dismissButton = { TextButton(actions.dismiss, enabled = !busy) { Text(stringResource(Res.string.m4_cancel)) } })
}

data class PlaylistTagUi(val id: String, val name: String, val color: Color)
data class PlaylistChoiceUi(val ref: ContentRef, val name: String, val songCount: Int)
data class AssignPlaylistTagsUi(
    val tags: List<PlaylistTagUi>, val selectedTagIds: Set<String> = emptySet(),
    /** Null means a single playlist already bound by the owner; non-null enables the bulk picker. */
    val playlists: List<PlaylistChoiceUi>? = null, val selectedPlaylists: Set<ContentRef> = emptySet(),
    val loading: Boolean = false, val busy: Boolean = false, val failed: Boolean = false, val canSave: Boolean = true,
)
class AssignPlaylistTagsActions(
    val tag: (String) -> Unit, val playlist: (ContentRef) -> Unit,
    val save: (tagIds: Set<String>, playlistRefs: Set<ContentRef>) -> Unit, val manage: () -> Unit, val dismiss: () -> Unit,
)

@Composable
fun AssignPlaylistTagsDialog(state: AssignPlaylistTagsUi, actions: AssignPlaylistTagsActions) {
    val busy = state.loading || state.busy
    val validTags = state.selectedTagIds intersect state.tags.map { it.id }.toSet()
    val validPlaylists = state.selectedPlaylists intersect state.playlists.orEmpty().map { it.ref }.toSet()
    AlertDialog(onDismissRequest = { if (!busy) actions.dismiss() },
        title = { Text(stringResource(Res.string.m4_assign_tags)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item("state") {
                    if (busy) Text(stringResource(Res.string.m4_loading))
                    if (state.failed) Text(stringResource(Res.string.m4_playlist_write_failed), color = MaterialTheme.colorScheme.error)
                    if (state.tags.isEmpty() && !state.loading) Text(stringResource(Res.string.m4_no_tags))
                    TextButton(actions.manage, enabled = !busy) { Text(stringResource(Res.string.m4_manage_tags)) }
                }
                items(state.tags, key = { "tag-${it.id}" }) { tag ->
                    Row {
                        Checkbox(tag.id in validTags, { actions.tag(tag.id) }, enabled = !busy)
                        Box(Modifier.padding(8.dp).size(16.dp).background(tag.color, CircleShape))
                        Text(tag.name, Modifier.padding(top = 12.dp))
                    }
                }
                state.playlists?.let { playlists ->
                    item("playlist-title") { Text(stringResource(Res.string.m4_choose_playlists), style = MaterialTheme.typography.titleMedium) }
                    items(playlists, key = { "playlist-${it.ref.collectionUiKey()}" }) { playlist ->
                        Row {
                            Checkbox(playlist.ref in validPlaylists, { actions.playlist(playlist.ref) }, enabled = !busy)
                            Column(Modifier.padding(top = 8.dp)) {
                                Text(playlist.name)
                                Text(stringResource(Res.string.m4_song_count, playlist.songCount))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton({ actions.save(validTags, validPlaylists) },
                enabled = !busy && state.canSave && (state.playlists == null || validPlaylists.isNotEmpty()),
                modifier = Modifier.testTag("assign-tags-save")) { Text(stringResource(Res.string.m4_save)) }
        },
        dismissButton = { TextButton(actions.dismiss, enabled = !busy) { Text(stringResource(Res.string.m4_cancel)) } })
}

data class PlaylistExportUi(val playlists: List<PlaylistChoiceUi>, val busy: Boolean = false, val failed: Boolean = false)

/**
 * Selects a real playlist for the owner's CSV use case and native document picker. The owner loads
 * songs, produces the baseline Title/Artist/Album/Media ID CSV and reports cancellation/write errors.
 */
@Composable
fun PlaylistExportDialog(state: PlaylistExportUi, choose: (ContentRef) -> Unit, dismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val rows = state.playlists.filter { it.songCount > 0 && it.name.contains(query, true) }
    AlertDialog(onDismissRequest = { if (!state.busy) dismiss() }, modifier = Modifier.testTag("playlist-export"),
        title = { Text(stringResource(Res.string.m4_export_csv)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.m4_export_csv_description))
                OutlinedTextField(query, { query = it }, enabled = !state.busy, singleLine = true,
                    label = { Text(stringResource(Res.string.m4_search_query)) })
                if (state.failed) Text(stringResource(Res.string.m4_export_failed), color = MaterialTheme.colorScheme.error)
                if (state.busy) Text(stringResource(Res.string.m4_saving))
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    if (rows.isEmpty()) item { Text(stringResource(Res.string.m4_empty)) }
                    items(rows, key = { it.ref.collectionUiKey() }) { playlist ->
                        ListItem(headlineContent = { Text(playlist.name) },
                            supportingContent = { Text(stringResource(Res.string.m4_song_count, playlist.songCount)) },
                            modifier = Modifier.clickable(enabled = !state.busy) { choose(playlist.ref) })
                    }
                }
            }
        }, confirmButton = {},
        dismissButton = { TextButton(dismiss, enabled = !state.busy) { Text(stringResource(Res.string.m4_cancel)) } })
}
