package moe.rukamori.archivetune.shared.ui.menu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.library.LibrarySortState
import moe.rukamori.archivetune.shared.domain.playlist.PlaylistAddOutcome
import moe.rukamori.archivetune.shared.domain.playlist.PlaylistAddProgress
import moe.rukamori.archivetune.shared.domain.playlist.PlaylistPickerCandidate
import moe.rukamori.archivetune.shared.domain.playlist.PlaylistPickerOrder
import moe.rukamori.archivetune.shared.domain.playlist.PlaylistPickerSortType
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.LibraryMutation
import moe.rukamori.archivetune.shared.domain.repository.LibraryRepository
import moe.rukamori.archivetune.shared.ui.component.LibrarySortHeader
import moe.rukamori.archivetune.shared.ui.resources.Res
import moe.rukamori.archivetune.shared.ui.resources.m4_empty
import moe.rukamori.archivetune.shared.ui.resources.m4_loading
import moe.rukamori.archivetune.shared.ui.resources.m4_name
import moe.rukamori.archivetune.shared.ui.resources.m4_recently_added
import moe.rukamori.archivetune.shared.ui.resources.m4_recently_updated
import moe.rukamori.archivetune.shared.ui.resources.m4_track_count
import moe.rukamori.archivetune.shared.ui.resources.m6_add_playlist
import moe.rukamori.archivetune.shared.ui.resources.m6_cancel
import moe.rukamori.archivetune.shared.ui.resources.playlist_add
import moe.rukamori.archivetune.shared.ui.resources.playlist_add_failed
import moe.rukamori.archivetune.shared.ui.resources.playlist_add_progress
import moe.rukamori.archivetune.shared.ui.resources.playlist_add_retry
import moe.rukamori.archivetune.shared.ui.resources.playlist_add_stopped
import moe.rukamori.archivetune.shared.ui.resources.playlist_add_uncertain
import moe.rukamori.archivetune.shared.ui.resources.playlist_already_in
import org.jetbrains.compose.resources.stringResource

/**
 * The host source of the add-to-playlist picker's candidates. The repository computes them, so every host's picker
 * shows the same playlists, the same membership hints and the same sort values without holding library internals.
 */
interface PlaylistPickerSource {
    /** Editable playlists with their membership against [occurrences], re-emitted on every library change. */
    fun candidates(occurrences: List<ContentOccurrence>): Flow<List<PlaylistPickerCandidate>>
}

/**
 * Everything the shared playlist picker needs from the host: the candidates plus the persisted sort. Hosts provide it
 * as [LocalPlaylistPickerContext]; a host without one keeps the legacy single-playlist chooser.
 */
class PlaylistPickerContext(
    val source: PlaylistPickerSource,
    val sort: LibrarySortState<PlaylistPickerSortType>,
    val onSort: (PlaylistPickerSortType, Boolean) -> Unit,
)

val LocalPlaylistPickerContext = compositionLocalOf<PlaylistPickerContext?> { null }

private enum class PlaylistRowStatus { IDLE, BUSY, DONE, FAILED, UNCERTAIN }

private val playlistPickerTextOrder = Comparator<String> { left, right -> left.compareTo(right, ignoreCase = true) }

/**
 * The multi-playlist add-to-playlist chooser: checkbox rows pre-checked by membership, the persisted picker sort, and
 * a batch add reporting per-playlist progress, failures and the unconfirmed tail of a stopped run. A fully clean run
 * finishes through [onFinished]; a run with failures or an unconfirmed remainder stays open on its report, so a retry
 * can be chosen without losing what committed.
 */
@Composable
fun PlaylistPickerDialog(
    occurrences: List<ContentOccurrence>,
    context: PlaylistPickerContext,
    library: LibraryRepository,
    /** Called once when the batch closes: the committed playlist titles let the caller report the add. */
    onFinished: (completedNames: List<String>) -> Unit,
) {
    val candidates by produceState<List<PlaylistPickerCandidate>?>(null, occurrences, context.source) {
        try {
            context.source.candidates(occurrences).collect { value = it }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            value = emptyList()
        }
    }
    val refs = remember(occurrences) { occurrences.map { it.item.ref } }
    val selected = remember(occurrences) { mutableStateSetOf<String>() }
    var primed by remember(occurrences) { mutableStateOf(false) }
    val statuses = remember(occurrences) { mutableStateMapOf<String, PlaylistRowStatus>() }
    var progress by remember(occurrences) { mutableStateOf(PlaylistAddProgress()) }
    var job by remember(occurrences) { mutableStateOf<Job?>(null) }
    val running = job != null
    val scope = rememberCoroutineScope()

    // Membership pre-check happens once: the first candidates list marks the playlists already holding the batch.
    val loaded = candidates
    if (loaded != null && !primed) {
        primed = true
        selected.addAll(loaded.filter { it.holdsAll(refs) }.map { it.item.ref.id })
    }

    val ordered = remember(loaded, context.sort) {
        PlaylistPickerOrder.sorted(loaded.orEmpty(), context.sort.type, context.sort.descending, playlistPickerTextOrder)
    }

    fun run(targets: List<PlaylistPickerCandidate>) {
        if (running || targets.isEmpty()) return
        // Earlier runs' committed names survive a retry, so the finish notice reports the whole batch's commits.
        var current =
            PlaylistAddProgress(total = occurrences.size * targets.size, completedNames = progress.completedNames)
        progress = current
        job = scope.launch {
            // Membership is re-read at run start, so a retried uncertain playlist that committed is skipped instead
            // of written a second time.
            val fresh = try {
                context.source.candidates(occurrences).first().associateBy { it.item.ref.id }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyMap()
            }
            for (candidate in targets) {
                val playlistId = candidate.item.ref.id
                statuses[playlistId] = PlaylistRowStatus.BUSY
                val known = fresh[playlistId]?.memberOccurrenceIds ?: candidate.memberOccurrenceIds
                val toAdd = occurrences.filter { it.occurrenceId !in known }
                if (toAdd.isEmpty()) {
                    statuses[playlistId] = PlaylistRowStatus.DONE
                    current = current.record(candidate.item.title, occurrences.size, PlaylistAddOutcome.ALREADY_PRESENT)
                    progress = current
                    continue
                }
                try {
                    library.mutate(LibraryMutation.AddToPlaylist(playlistId, toAdd))
                    statuses[playlistId] = PlaylistRowStatus.DONE
                    current = current.record(candidate.item.title, occurrences.size, PlaylistAddOutcome.COMPLETED)
                    progress = current
                } catch (cancelled: CancellationException) {
                    // The write may or may not have committed before the stop reached it.
                    statuses[playlistId] = PlaylistRowStatus.UNCERTAIN
                    current = current.stoppedAt(candidate.item.title, occurrences.size)
                    progress = current
                    break
                } catch (failure: Exception) {
                    statuses[playlistId] = PlaylistRowStatus.FAILED
                    current = current.record(
                        candidate.item.title,
                        occurrences.size,
                        PlaylistAddOutcome.FAILED,
                        failure::class.simpleName ?: "error",
                    )
                    progress = current
                }
            }
            job = null
            if (current.clean) onFinished(current.completedNames)
        }
    }

    AlertDialog(
        onDismissRequest = { if (running) job?.cancel() else onFinished(progress.completedNames) },
        title = { Text(stringResource(Res.string.m6_add_playlist)) },
        text = {
            Column {
                LibrarySortHeader(
                    sortState = context.sort,
                    sortTypes = PlaylistPickerSortType.entries,
                    sortTypeLabel = { type ->
                        when (type) {
                            PlaylistPickerSortType.NAME -> Res.string.m4_name
                            PlaylistPickerSortType.CREATE_DATE -> Res.string.m4_recently_added
                            PlaylistPickerSortType.LAST_UPDATED -> Res.string.m4_recently_updated
                            PlaylistPickerSortType.SONG_COUNT -> Res.string.m4_track_count
                        }
                    },
                    onSortStateChange = { next -> context.onSort(next.type, next.descending) },
                )
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    if (candidates == null) {
                        item("loading") { Text(stringResource(Res.string.m4_loading)) }
                    } else if (ordered.isEmpty()) {
                        item("empty") { Text(stringResource(Res.string.m4_empty)) }
                    } else {
                        items(ordered, key = { it.item.ref.id }) { candidate ->
                            val playlistId = candidate.item.ref.id
                            val status = statuses[playlistId] ?: PlaylistRowStatus.IDLE
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !running) {
                                        if (playlistId in selected) selected -= playlistId else selected += playlistId
                                    }
                                    .testTag("playlist-row-$playlistId"),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = playlistId in selected,
                                    onCheckedChange = null,
                                    enabled = !running,
                                    modifier = Modifier.testTag("playlist-pick-$playlistId"),
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(candidate.item.title, style = MaterialTheme.typography.bodyLarge)
                                    if (candidate.memberRefs.isNotEmpty()) {
                                        Text(
                                            stringResource(Res.string.playlist_already_in, candidate.item.title),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                when (status) {
                                    PlaylistRowStatus.BUSY ->
                                        CircularProgressIndicator(Modifier.size(20.dp))
                                    PlaylistRowStatus.DONE ->
                                        Text("\u2713", color = MaterialTheme.colorScheme.primary)
                                    PlaylistRowStatus.FAILED, PlaylistRowStatus.UNCERTAIN ->
                                        Text("!", color = MaterialTheme.colorScheme.error)
                                    PlaylistRowStatus.IDLE -> {}
                                }
                            }
                        }
                    }
                }
                if (running || progress.processed > 0) {
                    Text(
                        stringResource(Res.string.playlist_add_progress, progress.processed, progress.total),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (progress.stopped) {
                    Text(
                        stringResource(Res.string.playlist_add_stopped, progress.settled, progress.unconfirmed),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                progress.failed.forEach { failure ->
                    Text(
                        stringResource(Res.string.playlist_add_failed, failure.name, failure.reason),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (progress.uncertain.isNotEmpty() && !progress.stopped) {
                    Text(
                        stringResource(Res.string.playlist_add_uncertain),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            when {
                running -> {}
                progress.failed.isNotEmpty() || progress.uncertain.isNotEmpty() -> TextButton(
                    onClick = {
                        val retryIds = statuses.filterValues {
                            it == PlaylistRowStatus.FAILED || it == PlaylistRowStatus.UNCERTAIN
                        }.keys
                        val retry = ordered.filter { it.item.ref.id in retryIds }
                        run(retry)
                    },
                ) {
                    Text(stringResource(Res.string.playlist_add_retry))
                }
                else -> TextButton(
                    enabled = selected.isNotEmpty(),
                    onClick = { run(ordered.filter { it.item.ref.id in selected }) },
                    modifier = Modifier.testTag("playlist-picker-add"),
                ) {
                    Text(stringResource(Res.string.playlist_add))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { if (running) job?.cancel() else onFinished(progress.completedNames) }) {
                Text(stringResource(Res.string.m6_cancel))
            }
        },
    )
}
