package moe.rukamori.archivetune.shared.ui.menu

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.queue.UserQueuedKind
import moe.rukamori.archivetune.shared.domain.repository.*
import moe.rukamori.archivetune.shared.domain.lyrics.ai.AiLyricsOperation
import moe.rukamori.archivetune.shared.ui.resources.*
import moe.rukamori.archivetune.shared.ui.session.LyricsSessions
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.painterResource
import androidx.compose.foundation.layout.Column
import moe.rukamori.archivetune.shared.ui.host.LocalAndroidPhonePresentation
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

class OverflowAction(
    val id: String, val label: StringResource, val enabled: Boolean = true,
    val dismissOnSuccess: Boolean = true,
    /** Error-coloured row for removal and deletion, as the baseline menus colour those commands. */
    val destructive: Boolean = false,
    /** Asks for confirmation with this message before [run]. Cancelling leaves the data untouched. */
    val confirm: StringResource? = null,
    /** Format arguments of [confirm], such as the playlist name the baseline confirmations include. */
    val confirmArgs: List<Any> = emptyList(),
    val description: StringResource? = null,
    val run: suspend () -> Unit,
)

@Composable
fun SharedOverflowMenu(
    menuIdentity: String, expanded: Boolean, onDismiss: () -> Unit, actions: List<OverflowAction>, onFailure: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var busy by remember(menuIdentity, expanded) { mutableStateOf(false) }
    var confirming by remember(menuIdentity, expanded) { mutableStateOf<OverflowAction?>(null) }
    val lifetime = remember(menuIdentity, expanded) { Any() }
    val latestLifetime by rememberUpdatedState(lifetime)
    fun perform(action: OverflowAction) {
        if (busy || !action.enabled) return
        busy = true
        scope.launch {
            try {
                action.run()
                if (action.dismissOnSuccess && latestLifetime === lifetime) onDismiss()
            }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (latestLifetime === lifetime) onFailure() }
            finally { busy = false }
        }
    }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        actions.forEach { action ->
            DropdownMenuItem(
                text = { Column {
                    Text(stringResource(action.label), color = if (action.destructive) MaterialTheme.colorScheme.error else Color.Unspecified)
                    action.description?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
                } },
                leadingIcon = if (LocalAndroidPhonePresentation.current) { { Icon(painterResource(phoneMenuIcon(action.id)), null) } } else null,
                modifier = Modifier.testTag("menu:${action.id}"),
                enabled = action.enabled && !busy, onClick = onClick@{
                if (action.confirm != null) {
                    confirming = action
                    return@onClick
                }
                perform(action)
            })
        }
    }
    confirming?.let { action ->
        val message = action.confirm
        AlertDialog(
            onDismissRequest = { confirming = null },
            text = { if (message != null) Text(stringResource(message, *action.confirmArgs.toTypedArray())) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = null
                    perform(action)
                }) { Text(stringResource(Res.string.m6_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text(stringResource(Res.string.m6_cancel)) } },
        )
    }
}

/** Commands target the menu's selected occurrence, not whichever track happens to be playing. */
fun songOverflowActions(
    selected: ContentOccurrence,
    queue: QueueController,
    library: LibraryRepository,
    sharing: MenuShareHandler,
    liked: Boolean,
    isLocal: Boolean,
    mimeType: String?,
    viewArtists: () -> Unit,
    canvasSave: CanvasSaveMenuState? = null,
): List<OverflowAction> = listOfNotNull(
    OverflowAction("play_next", Res.string.m6_play_next) { queue.insert(listOf(selected), UserQueuedKind.PLAY_NEXT) },
    OverflowAction("add_queue", Res.string.m6_add_queue) { queue.insert(listOf(selected), UserQueuedKind.ADD_TO_QUEUE) },
    OverflowAction("like", if (liked) Res.string.m6_unlike else Res.string.m6_like) {
        library.mutate(LibraryMutation.Like(selected.item.ref, !liked))
    },
    OverflowAction("view_artist", Res.string.m6_view_artist) { viewArtists() },
    OverflowAction("share", Res.string.m6_share) {
        check(sharing.song(selected.item.ref.id, isLocal, mimeType)) { "Share target unavailable" }
    },
    // Local files have no shareable link; a Telegram song resolves its message link like share does.
    if (!isLocal || selected.item.ref.id.startsWith("telegram://")) {
        OverflowAction("copy_link", Res.string.m5pq_copy_link) {
            check(sharing.copySongLink(selected.item.ref.id, isLocal)) { "Link unavailable" }
        }
    } else null,
    // Save canvas is the baseline SongMenu row; the host mounts CanvasSourcePicker beside the menu.
    canvasSave?.saveCanvasOverflowAction(),
)

/**
 * Batch song commands for the selected occurrences. The selection is the menu's own list, never
 * whatever is playing. Library, download, cache and history rows are host-backed and come from
 * [selectionSongLibraryAndDownloadActions], which the caller appends through SelectionOverflowMenu's
 * extraActions.
 */
fun selectionSongOverflowActions(
    selection: List<ContentOccurrence>,
    likedRefs: Set<ContentRef>,
    queue: QueueController,
    library: LibraryRepository,
    queueContext: Boolean,
    playlistId: String?,
): List<OverflowAction> {
    val enabled = selection.isNotEmpty()
    fun selected(): List<ContentOccurrence> = selection.also { require(it.isNotEmpty()) { "Empty selection" } }
    // Baseline like-all: when every distinct song is liked, unlike them all; otherwise like only the unliked ones.
    val distinctRefs = selection.map { it.item.ref }.distinct()
    val allLiked = distinctRefs.isNotEmpty() && distinctRefs.all { it in likedRefs }
    val likeTargets = if (allLiked) distinctRefs else distinctRefs.filterNot { it in likedRefs }
    return buildList {
        add(OverflowAction("play", Res.string.m6_play, enabled) {
            val all = selected()
            queue.replace(all, all.first().occurrenceId, true)
        })
        add(OverflowAction("shuffle", Res.string.m6_shuffle, enabled) {
            val all = selected().shuffled()
            queue.replace(all, all.first().occurrenceId, true)
        })
        add(OverflowAction("add_queue", Res.string.m6_add_queue, enabled) { queue.insert(selected(), UserQueuedKind.ADD_TO_QUEUE) })
        add(OverflowAction("like_all", if (allLiked) Res.string.m6_dislike_all else Res.string.m6_like_all, enabled) {
            likeTargets.forEach { library.mutate(LibraryMutation.Like(it, !allLiked)) }
        })
        if (queueContext) add(OverflowAction("remove_queue", Res.string.m6_remove_from_queue, enabled) {
            queue.remove(selected().mapTo(HashSet()) { it.occurrenceId })
        })
        if (playlistId != null) add(OverflowAction("remove_playlist", Res.string.m6_remove_playlist, enabled) {
            library.mutate(LibraryMutation.RemoveFromPlaylist(playlistId, selected().mapTo(HashSet()) { it.occurrenceId }))
        })
    }
}

fun playlistOverflowActions(
    browseId: String?, shareLink: String?, sharing: MenuShareHandler, exportLocal: suspend () -> Unit,
): List<OverflowAction> =
    if (browseId.isNullOrBlank() && shareLink.isNullOrBlank()) listOf(OverflowAction("export", Res.string.m6_export, run = exportLocal))
    else listOf(
        OverflowAction("share", Res.string.m6_share) {
            check(sharing.playlist(browseId, shareLink)) { "Share target unavailable" }
        },
        OverflowAction("copy_link", Res.string.m5pq_copy_link) {
            check(sharing.copyPlaylistLink(browseId, shareLink)) { "Link unavailable" }
        },
    )

/** The single M7 repository owns remote-before-local commit and duplicate occurrence mapping. */
fun playlistOccurrenceRemoval(
    playlistId: String, occurrence: ContentOccurrence, library: LibraryRepository,
): OverflowAction = OverflowAction("remove_playlist", Res.string.m6_remove_playlist) {
    library.mutate(LibraryMutation.RemoveFromPlaylist(playlistId, setOf(occurrence.occurrenceId)))
}

fun lyricsOverflowActions(
    before: LyricsEditSnapshot,
    sessions: LyricsSessions,
    edits: LyricsMenuEdits,
    isCurrent: () -> Boolean,
    translateOrdinary: suspend (String) -> Map<Int, String>,
): List<OverflowAction> = listOf(
    OverflowAction("translate", Res.string.m6_translate) {
        edits.translate(before, translateOrdinary(before.lyrics), isCurrent)
    },
    OverflowAction("ai_translate", Res.string.m6_ai_translate) { check(sessions.manual(AiLyricsOperation.TRANSLATE)) },
    OverflowAction("ai_romanize", Res.string.m6_romanize) { check(sessions.manual(AiLyricsOperation.ROMANIZE)) },
    OverflowAction("cancel_translation", Res.string.m6_cancel_request, sessions.translation.state.value.running) {
        sessions.translation.cancel()
    },
    OverflowAction("undo_translation", Res.string.m6_undo_translation, edits.undo.value?.applied?.mediaId == before.mediaId) {
        if (edits.undo(isCurrent)) sessions.translationUndone(before.mediaId)
    },
)
