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
import moe.rukamori.archivetune.shared.domain.queue.*
import moe.rukamori.archivetune.shared.domain.repository.*
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.stringResource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/** A partial remote page must not be substituted for the confirmed whole album/artist/playlist. */
fun collectionQueueActions(
    complete: FinitePlaylistCompleteList<ContentOccurrence>?,
    queue: QueueController,
): List<OverflowAction> {
    val entries = complete?.songs?.map { it.item }
    val enabled = !entries.isNullOrEmpty()
    fun selected(): List<ContentOccurrence> = requireNotNull(entries).also { require(it.isNotEmpty()) }
    return listOf(
        OverflowAction("play", Res.string.m6_play, enabled) {
            val all = selected()
            queue.replace(all, all.first().occurrenceId, true)
        },
        OverflowAction("shuffle", Res.string.m6_shuffle, enabled) {
            val all = selected().shuffled()
            queue.replace(all, all.first().occurrenceId, true)
        },
        OverflowAction("play_next", Res.string.m6_play_next, enabled) { queue.insert(selected(), UserQueuedKind.PLAY_NEXT) },
        OverflowAction("add_queue", Res.string.m6_add_queue, enabled) { queue.insert(selected(), UserQueuedKind.ADD_TO_QUEUE) },
    )
}

/**
 * Shared local/remote collection commands. Candidates are actual editable playlists from the host
 * repository; committing uses occurrence IDs, not deduplicated media IDs or translated labels.
 */
@Composable
fun CollectionOverflowMenu(
    identity: String,
    expanded: Boolean,
    complete: FinitePlaylistCompleteList<ContentOccurrence>?,
    queue: QueueController,
    library: LibraryRepository,
    editablePlaylists: List<ContentItem>,
    onDismiss: () -> Unit,
    onFailure: () -> Unit,
) = OccurrenceOverflowMenu(
    identity, expanded, complete?.songs?.map { it.item }, collectionQueueActions(complete, queue),
    library, editablePlaylists, onDismiss, onFailure,
)

/**
 * Shared body of the collection and selection menus: the base commands plus one playlist chooser
 * that commits the occurrence ids the menu was opened with.
 */
@Composable
internal fun OccurrenceOverflowMenu(
    identity: String,
    expanded: Boolean,
    selected: List<ContentOccurrence>?,
    baseActions: List<OverflowAction>,
    library: LibraryRepository,
    editablePlaylists: List<ContentItem>,
    onDismiss: () -> Unit,
    onFailure: () -> Unit,
) {
    var choosingPlaylist by remember(identity) { mutableStateOf(false) }
    var saving by remember(identity) { mutableStateOf(false) }
    val lifetime = remember(identity, expanded) { Any() }
    val latestLifetime by rememberUpdatedState(lifetime)
    LaunchedEffect(expanded) { if (!expanded) choosingPlaylist = false }
    val scope = rememberCoroutineScope()
    val commands = baseActions + OverflowAction(
        "add_playlist", Res.string.m6_add_playlist, !selected.isNullOrEmpty(), dismissOnSuccess = false,
    ) { choosingPlaylist = true }
    SharedOverflowMenu(identity, expanded && !choosingPlaylist, onDismiss, commands, onFailure)
    if (expanded && choosingPlaylist) AlertDialog(
        onDismissRequest = { if (!saving) choosingPlaylist = false },
        title = { Text(stringResource(Res.string.m6_add_playlist)) },
        text = { LazyColumn(Modifier.heightIn(max = 400.dp)) {
            items(editablePlaylists, key = { it.ref }) { playlist ->
                TextButton(enabled = !saving && !selected.isNullOrEmpty(), onClick = {
                    if (!saving) {
                        saving = true
                        scope.launch {
                            try {
                                library.mutate(LibraryMutation.AddToPlaylist(playlist.ref.id, requireNotNull(selected)))
                                choosingPlaylist = false
                                if (latestLifetime === lifetime) onDismiss()
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { if (latestLifetime === lifetime) onFailure() }
                            finally { saving = false }
                        }
                    }
                }, modifier = Modifier.testTag("playlist:${playlist.ref.id}")) { Text(playlist.title) }
            }
        } },
        confirmButton = {},
        dismissButton = { TextButton(enabled = !saving, onClick = { choosingPlaylist = false }) { Text(stringResource(Res.string.m6_cancel)) } },
    )
}

/**
 * Batch song commands for a multi-selection. The host supplies [likedRefs] from its library snapshot
 * and an [identity] that changes with the selection. [queueContext] and [playlistId] decide which
 * removal command applies, so the menu never offers a removal the open surface cannot honour.
 */
@Composable
fun SelectionOverflowMenu(
    identity: String,
    expanded: Boolean,
    selection: List<ContentOccurrence>,
    likedRefs: Set<ContentRef>,
    queueContext: Boolean,
    playlistId: String?,
    queue: QueueController,
    library: LibraryRepository,
    editablePlaylists: List<ContentItem>,
    onDismiss: () -> Unit,
    onFailure: () -> Unit,
    /** Host-backed batch rows, such as [selectionSongLibraryAndDownloadActions], appended after the base commands. */
    extraActions: List<OverflowAction> = emptyList(),
) {
    OccurrenceOverflowMenu(
        identity, expanded, selection,
        selectionSongOverflowActions(selection, likedRefs, queue, library, queueContext, playlistId) + extraActions,
        library, editablePlaylists, onDismiss, onFailure,
    )
}

/** Editing is never exposed for automatic/download collections; mutation completion is durable. */
@Composable
fun PlaylistRenameMenu(
    playlist: ContentItem,
    editable: Boolean,
    autoPlaylist: Boolean,
    expanded: Boolean,
    library: LibraryRepository,
    onDismiss: () -> Unit,
    onFailure: () -> Unit,
    downloadPlaylist: Boolean = false,
) {
    PlaylistOverflowBody(
        identity = playlist.ref.toString(), expanded = expanded, ref = playlist.ref, title = playlist.title,
        allowed = editable && !autoPlaylist && !downloadPlaylist, library = library, onDismiss = onDismiss,
        onFailure = onFailure,
    ) { rename -> listOfNotNull(rename) }
}

/**
 * The playlist overflow menu: the baseline playlist rows over [facts]. The rename row sits where the baseline
 * puts it, and the share or export row comes last.
 */
@Composable
fun PlaylistOverflowMenu(
    identity: String,
    expanded: Boolean,
    facts: PlaylistMenuFacts,
    queue: QueueController,
    library: LibraryRepository,
    sharing: MenuShareHandler,
    host: PlaylistMenuHost,
    exportLocal: suspend () -> Unit,
    onDismiss: () -> Unit,
    onFailure: () -> Unit,
) {
    PlaylistOverflowBody(
        identity = identity, expanded = expanded, ref = facts.playlist, title = facts.name,
        allowed = facts.editable && !facts.automatic && !facts.downloadPlaylist, library = library,
        onDismiss = onDismiss, onFailure = onFailure,
    ) { rename -> playlistMenuActions(facts, host, queue, sharing, exportLocal, rename) }
}

/**
 * Shared body of the playlist menus. It owns the rename dialog state; [actions] receives the rename row, or null
 * when renaming is not allowed, and places that row where the baseline shows it.
 */
@Composable
internal fun PlaylistOverflowBody(
    identity: String,
    expanded: Boolean,
    ref: ContentRef,
    title: String,
    allowed: Boolean,
    library: LibraryRepository,
    onDismiss: () -> Unit,
    onFailure: () -> Unit,
    actions: (rename: OverflowAction?) -> List<OverflowAction>,
) {
    var editing by remember(ref) { mutableStateOf(false) }
    var draft by remember(ref, title) { mutableStateOf(title) }
    var saving by remember(ref) { mutableStateOf(false) }
    val lifetime = remember(ref, expanded) { Any() }
    val latestLifetime by rememberUpdatedState(lifetime)
    val scope = rememberCoroutineScope()
    LaunchedEffect(expanded, allowed) { if (!expanded || !allowed) { editing = false; draft = title } }
    val rename = if (allowed) OverflowAction("rename", Res.string.m6_rename, dismissOnSuccess = false) { editing = true } else null
    SharedOverflowMenu(identity, expanded && !editing, onDismiss, actions(rename), onFailure)
    if (expanded && editing && allowed) AlertDialog(
        onDismissRequest = { if (!saving) editing = false },
        title = { Text(stringResource(Res.string.m6_rename)) },
        text = { OutlinedTextField(draft, { draft = it }, enabled = !saving, singleLine = true, modifier = Modifier.testTag("playlist-name")) },
        confirmButton = { TextButton(enabled = !saving && draft.isNotBlank(), onClick = {
            if (!saving) {
                saving = true
                scope.launch {
                    try {
                        library.mutate(LibraryMutation.RenamePlaylist(ref.id, draft.trim()))
                        editing = false
                        if (latestLifetime === lifetime) onDismiss()
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { if (latestLifetime === lifetime) onFailure() }
                    finally { saving = false }
                }
            }
        }) { Text(stringResource(Res.string.m6_save)) } },
        dismissButton = { TextButton(enabled = !saving, onClick = { editing = false }) { Text(stringResource(Res.string.m6_cancel)) } },
    )
}
