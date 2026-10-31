package moe.rukamori.archivetune.shared.ui.queue

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.item
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.playlistimport.PlaylistImportLibrary
import moe.rukamori.archivetune.shared.domain.queue.QueueRepeatMode
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.QueueController
import moe.rukamori.archivetune.shared.domain.repository.QueueEditingCommands
import moe.rukamori.archivetune.shared.domain.settings.AutoLoadMoreKey
import moe.rukamori.archivetune.shared.domain.settings.QueueEditLockKey
import moe.rukamori.archivetune.shared.domain.settings.RepeatModeKey
import moe.rukamori.archivetune.shared.domain.settings.SettingsStore
import moe.rukamori.archivetune.shared.ui.resources.*
import moe.rukamori.archivetune.shared.ui.screens.PlaylistDragHandle
import moe.rukamori.archivetune.shared.ui.screens.playlistRowDragViewport
import moe.rukamori.archivetune.shared.ui.screens.playlistRowKey
import moe.rukamori.archivetune.shared.ui.screens.rememberPlaylistRowDrag
import org.jetbrains.compose.resources.stringResource

/** Lazy item key of the expanded header, which is the first item of the list. */
private const val QueuePanelHeaderKey = "queue-panel-header"

/** List items before the first row (the expanded header). Scroll targets add this to a row index. */
private const val QueuePanelHeaderItems = 1

/** Both portable hosts render the same controls over their real queue, settings and transactional library owners. */
@Composable
fun PlaybackQueuePanel(
    queue: QueueController,
    edits: QueueEditingCommands,
    settings: SettingsStore,
    library: PlaylistImportLibrary,
    modifier: Modifier = Modifier,
    artwork: @Composable (ContentOccurrence, Modifier) -> Unit,
) {
    val state by queue.state.collectAsState()
    val playback by queue.player.snapshot.collectAsState()
    val preferences by settings.snapshots.collectAsState()
    val loading by edits.infiniteQueueLoading.collectAsState()
    val source by edits.queueSource.collectAsState()
    val rows = remember(state.entries, state.playOrder) {
        val byId = state.entries.associateBy { it.occurrenceId }
        state.playOrder.mapNotNull { byId[it] }
    }
    val locked = preferences?.get(QueueEditLockKey) ?: true
    val infinite = preferences?.get(AutoLoadMoreKey) ?: true
    val repeat = QueueRepeatMode.fromStoredValue(preferences?.get(RepeatModeKey) ?: 0)
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    // Read in the composition only through this state, so the panel recomposes when the overlay toggles, not per scroll tick.
    val listScrolled by remember { derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 0 } }
    val snackbar = remember { SnackbarHostState() }
    var selected by remember(queue) { mutableStateOf(emptySet<String>()) }
    var saveEntries by remember(queue) { mutableStateOf<List<ContentOccurrence>?>(null) }
    var name by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var undoJob by remember { mutableStateOf<Job?>(null) }
    val failedLabel = stringResource(Res.string.queue_action_failed)
    val removedLabel = stringResource(Res.string.queue_removed)
    val undoLabel = stringResource(Res.string.queue_undo)
    val savedLabel = stringResource(Res.string.queue_playlist_saved)
    val currentIndex = rows.indexOfFirst { it.occurrenceId == state.currentOccurrenceId }
    var firstScroll by remember(queue) { mutableStateOf(true) }
    LaunchedEffect(rows, state.currentOccurrenceId) {
        selected = selected.intersect(rows.mapTo(HashSet()) { it.occurrenceId })
        if (firstScroll && currentIndex >= 0) {
            list.scrollToItem(currentIndex + QueuePanelHeaderItems)
            firstScroll = false
        }
    }

    fun command(action: suspend () -> Unit) {
        scope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                snackbar.showSnackbar(failedLabel)
            }
        }
    }

    fun remove(action: suspend () -> Long?) {
        undoJob?.cancel()
        undoJob = scope.launch {
            try {
                val ticket = action() ?: return@launch
                selected = emptySet()
                if (snackbar.showSnackbar(removedLabel, undoLabel) == SnackbarResult.ActionPerformed &&
                    !edits.undoRemoval(ticket)
                ) snackbar.showSnackbar(failedLabel)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                snackbar.showSnackbar(failedLabel)
            }
        }
    }

    fun save(entries: List<ContentOccurrence>) {
        if (entries.isNotEmpty()) {
            name = ""
            saveEntries = entries.toList()
        }
    }

    fun move(sourceId: String, neighbourId: String, after: Boolean) {
        val order = rows.filterNot { it.occurrenceId == sourceId }
        val neighbour = order.indexOfFirst { it.occurrenceId == neighbourId }
        if (neighbour < 0) return
        val anchor = if (after) order.getOrNull(neighbour + 1)?.occurrenceId else neighbourId
        command { edits.moveInPlayOrder(sourceId, anchor) }
    }

    val padding = PaddingValues(bottom = 12.dp)
    val onDragMove: ((ContentOccurrence, ContentOccurrence, Boolean) -> Unit)? =
        if (locked || selected.isNotEmpty()) null else { from, to, after -> move(from.occurrenceId, to.occurrenceId, after) }
    val drag = rememberPlaylistRowDrag(list, rows, padding, onDragMove)
    val position = if (playback?.occurrenceId == state.currentOccurrenceId) {
        queue.player.positionMs() ?: playback?.positionMs ?: 0L
    } else 0L
    val times = queueTimes(rows, state.currentOccurrenceId, position)
    val unknown = stringResource(Res.string.queue_unknown_duration)
    val summary = stringResource(Res.string.queue_summary, rows.size,
        times.totalMs?.let(::queueTimeString) ?: unknown, times.remainingMs?.let(::queueTimeString) ?: unknown)
    val sourceLabel = source?.text?.takeIf { it.isNotBlank() } ?: source?.parent?.id ?: rows.firstOrNull()?.let {
        stringResource(if (it.item.ref.provider == ContentProvider.YOUTUBE.id) Res.string.queue_source_youtube
            else Res.string.queue_source_library)
    }

    val onInfinite: (Boolean) -> Unit = { enabled -> command {
        settings.edit { set(AutoLoadMoreKey, enabled) }
        edits.refreshInfiniteQueue()
    } }
    val onShuffle: () -> Unit = { command { check(edits.setShuffle(!state.shuffleEnabled)) } }
    val onRepeat: () -> Unit = { command {
        val next = when (repeat) {
            QueueRepeatMode.OFF -> QueueRepeatMode.ALL
            QueueRepeatMode.ALL -> QueueRepeatMode.ONE
            QueueRepeatMode.ONE -> QueueRepeatMode.OFF
        }
        settings.edit { set(RepeatModeKey, next.storedValue) }
        edits.refreshInfiniteQueue()
    } }
    val onLock: () -> Unit = { command { settings.edit { set(QueueEditLockKey, !locked) } } }
    val onJump: () -> Unit = { if (currentIndex >= 0) scope.launch { list.animateScrollToItem(currentIndex + QueuePanelHeaderItems) } }
    val onSave: () -> Unit = { save(rows) }
    val onClear: () -> Unit = { remove {
        settings.edit { set(AutoLoadMoreKey, false) }
        edits.refreshInfiniteQueue()
        edits.clearUpcoming()
    } }
    val onSelect: () -> Unit = { selected = rows.mapTo(HashSet()) { it.occurrenceId } }
    val header: @Composable () -> Unit = {
        QueuePanelHeader(
            summary, sourceLabel, infinite, loading, locked, state.shuffleEnabled, repeat,
            ready = preferences != null,
            hasTracks = rows.isNotEmpty(),
            onInfinite = onInfinite, onShuffle = onShuffle, onRepeat = onRepeat, onLock = onLock,
            onJump = onJump, onSave = onSave, onClear = onClear, onSelect = onSelect,
        )
    }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (rows.isEmpty()) {
                header()
                Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(Res.string.queue_empty))
                }
            } else {
                LazyColumn(Modifier.weight(1f).playlistRowDragViewport(drag), state = list, contentPadding = padding) {
                    // The expanded header is the first item: it scrolls away and the compact header takes its place.
                    item(key = QueuePanelHeaderKey) {
                        Column {
                            header()
                            if (selected.isNotEmpty()) QueueSelectionActions(
                                selected.size, selected.size == rows.size,
                                onAll = { selected = rows.mapTo(HashSet()) { it.occurrenceId } },
                                onClose = { selected = emptySet() },
                                onSave = { save(rows.filter { it.occurrenceId in selected }) },
                                onRemove = { val ids = selected; remove { edits.removeUndoable(ids) } },
                            )
                        }
                    }
                    itemsIndexed(drag.displayRows(rows), key = { _, entry -> playlistRowKey(entry.occurrenceId) }) { index, entry ->
                        QueuePanelTrack(
                            entry, current = entry.occurrenceId == state.currentOccurrenceId,
                            selected = entry.occurrenceId in selected, selecting = selected.isNotEmpty(), locked = locked,
                            modifier = drag.rowModifier(entry.occurrenceId, androidx.compose.material3.MaterialTheme.colorScheme.surface),
                            artwork = { artwork(entry, it) },
                            handle = { if (!locked && selected.isEmpty()) PlaylistDragHandle(entry.occurrenceId, drag) },
                            onSelect = { selected = if (entry.occurrenceId in selected) selected - entry.occurrenceId
                                else selected + entry.occurrenceId },
                            onPlay = {
                                if (entry.occurrenceId == state.currentOccurrenceId) {
                                    if (playback?.playWhenReady == true) queue.player.pause() else queue.player.play()
                                } else command { queue.select(entry.occurrenceId) }
                            },
                            onRemove = { remove { edits.removeUndoable(setOf(entry.occurrenceId)) } },
                            onNext = { command { edits.moveNext(entry.occurrenceId) } },
                            onTop = { rows.firstOrNull()?.takeIf { it != entry }?.let {
                                command { edits.moveInPlayOrder(entry.occurrenceId, it.occurrenceId) }
                            } },
                            onUp = rows.getOrNull(index - 1)?.let { before -> { move(entry.occurrenceId, before.occurrenceId, false) } },
                            onDown = rows.getOrNull(index + 1)?.let { after -> { move(entry.occurrenceId, after.occurrenceId, true) } },
                        )
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = listScrolled && rows.isNotEmpty(),
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            QueuePanelCompactHeader(
                hasTracks = rows.isNotEmpty(), ready = preferences != null, infinite = infinite, locked = locked,
                shuffle = state.shuffleEnabled, repeat = repeat,
                onInfinite = onInfinite, onShuffle = onShuffle, onRepeat = onRepeat, onLock = onLock,
                onJump = onJump, onSave = onSave, onClear = onClear, onSelect = onSelect,
            )
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(12.dp))
    }
    saveEntries?.let { entries ->
        AlertDialog(
            onDismissRequest = { if (!saving) saveEntries = null },
            title = { Text(stringResource(Res.string.queue_save_playlist)) },
            text = { OutlinedTextField(name, { name = it }, enabled = !saving, singleLine = true,
                label = { Text(stringResource(Res.string.queue_playlist_name)) }) },
            confirmButton = { TextButton(enabled = name.trim().isNotEmpty() && !saving, onClick = {
                saving = true
                command {
                    try {
                        val id = "LP" + Random.nextLong().toULong().toString(16) + Random.nextLong().toULong().toString(16)
                        library.createImportedPlaylist(id, name.trim(), entries, emptySet())
                        saveEntries = null
                        snackbar.showSnackbar(savedLabel)
                    } finally { saving = false }
                }
            }) { Text(stringResource(Res.string.queue_save)) } },
            dismissButton = { TextButton(enabled = !saving, onClick = { saveEntries = null }) {
                Text(stringResource(Res.string.queue_cancel))
            } },
        )
    }
}
