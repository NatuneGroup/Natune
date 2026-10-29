package moe.rukamori.archivetune.shared.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.ui.resources.*
import moe.rukamori.archivetune.shared.ui.navigation.LocalShellContentPadding
import org.jetbrains.compose.resources.stringResource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

enum class HistorySource { LOCAL, REMOTE, SPOTIFY }
/** Only local database events have a numeric ID; all rows retain the provider occurrence identity. */
data class HistoryEventUi(val eventId: Long?, val row: ContentOccurrence, val timeLabel: String)
data class HistorySectionUi(val key: String, val dateLabel: String, val events: List<HistoryEventUi>)
data class HistoryUi(
    val source: HistorySource = HistorySource.LOCAL, val availableSources: Set<HistorySource> = setOf(HistorySource.LOCAL),
    val sections: List<HistorySectionUi> = emptyList(), val query: String = "",
    val selectedEventIds: Set<Long> = emptySet(), val loading: Boolean = false, val failed: Boolean = false,
    val canLoadMore: Boolean = false, val busy: Boolean = false, val rowMenu: Boolean = true,
    val paginationUnavailable: Boolean = false,
)
class HistoryActions(
    val source: (HistorySource) -> Unit, val query: (String) -> Unit, val back: () -> Unit,
    /** The owner retains section continuation; this callback must not seed an incomplete finite queue. */
    val play: (HistoryEventUi, sectionKey: String) -> Unit, val more: (ContentOccurrence) -> Unit,
    val toggleSelection: (Long) -> Unit, val selectAllVisible: (Set<Long>) -> Unit, val clearSelection: () -> Unit,
    val removeEvents: (Set<Long>) -> Unit, val retry: () -> Unit, val loadMore: () -> Unit,
    /** The owner completes remote paging before shuffling query-filtered rows. Null for read-only sources. */
    val shuffleAll: (() -> Unit)? = null,
)

@Composable
fun HistoryScreen(
    state: HistoryUi, actions: HistoryActions, artwork: ContentArtwork,
    modifier: Modifier = Modifier, contentPadding: PaddingValues = LocalShellContentPadding.current,
) {
    var removal by remember(state.source) { mutableStateOf<Set<Long>?>(null) }
    val sections = remember(state.sections, state.query) {
        val query = state.query.trim()
        (if (query.isEmpty()) state.sections else state.sections.map { section -> section.copy(events = section.events.filter {
            it.row.item.title.contains(query, true) || it.row.item.subtitle.contains(query, true)
        }) }).filter { it.events.isNotEmpty() }
    }
    val local = state.source == HistorySource.LOCAL
    val list = key(state.source) { rememberLazyListState() }
    val loadMore by rememberUpdatedState(actions.loadMore)
    LaunchedEffect(list, state.canLoadMore, state.loading, state.failed, state.busy, state.sections) {
        if (!state.canLoadMore || state.loading || state.failed || state.busy) return@LaunchedEffect
        snapshotFlow { list.layoutInfo.let { layout ->
            layout.totalItemsCount > 0 && (layout.visibleItemsInfo.lastOrNull()?.index ?: -1) >= layout.totalItemsCount - 4
        } }.collect { if (it) loadMore() }
    }
    val localIds = state.sections.flatMap { it.events }.mapNotNull { it.eventId }.toSet()
    val selected = if (local) state.selectedEventIds intersect localIds else emptySet()
    val visibleIds = sections.flatMap { it.events }.mapNotNull { it.eventId }.toSet()
    val sources = HistorySource.entries.filter { it == HistorySource.LOCAL || it in state.availableSources }
    LaunchedEffect(state.source, sources) {
        if (state.source !in sources) actions.source(HistorySource.LOCAL)
    }
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        ManagementHeader(stringResource(Res.string.m4_history), actions.back)
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sources) { source ->
                FilterChip(state.source == source, { actions.source(source) }, label = { Text(stringResource(when (source) {
                    HistorySource.LOCAL -> Res.string.m4_local_history
                    HistorySource.REMOTE -> Res.string.m4_remote_history
                    HistorySource.SPOTIFY -> Res.string.m4_spotify_history
                })) })
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(when (state.source) {
                HistorySource.LOCAL -> Res.string.m4_local_history_summary
                HistorySource.REMOTE -> Res.string.m4_remote_history_summary
                HistorySource.SPOTIFY -> Res.string.m4_spotify_history_summary
            }), Modifier.weight(1f))
            val shuffleAll = actions.shuffleAll
            if (shuffleAll != null) {
                TextButton(shuffleAll, enabled = !state.loading && !state.busy && !state.failed,
                    modifier = Modifier.testTag("history-shuffle")) {
                    Text(stringResource(Res.string.m4_shuffle))
                }
            }
        }
        OutlinedTextField(state.query, actions.query, Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            label = { Text(stringResource(Res.string.m4_search_query)) }, singleLine = true)
        if (selected.isNotEmpty()) Row {
            TextButton({ actions.selectAllVisible(visibleIds) }) { Text(stringResource(Res.string.m4_select_all)) }
            TextButton(actions.clearSelection) { Text(stringResource(Res.string.m4_cancel)) }
            TextButton({ removal = selected.toSet() }, enabled = !state.busy,
                modifier = Modifier.testTag("history-remove")) { Text(stringResource(Res.string.m4_remove)) }
        }
        LazyColumn(Modifier.weight(1f), state = list, contentPadding = contentPadding) {
            item("load") { LoadFeedback(state.loading, state.failed, sections.isEmpty(), actions.retry) }
            sections.forEach { section ->
                item("header-${section.key}") { Text(section.dateLabel, Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium) }
                items(section.events, key = { "event-${it.row.occurrenceId}" }) { event ->
                    val id = event.eventId
                    ListItem(headlineContent = { Text(event.row.item.title) },
                        supportingContent = {
                            Column {
                                Text(event.row.item.subtitle + "\n" + event.timeLabel)
                                PodcastEpisodeProgress(event.row.item.ref)
                            }
                        },
                        leadingContent = {
                            if (selected.isNotEmpty() && id != null) Checkbox(id in selected, { actions.toggleSelection(id) }, enabled = !state.busy)
                            else artwork(event.row.item, Modifier.size(48.dp))
                        },
                        trailingContent = {
                            if (selected.isEmpty() && state.rowMenu) TextButton({ actions.more(event.row) }) { Text(stringResource(Res.string.m4_more)) }
                        },
                        modifier = Modifier.testTag("history-event-${event.row.occurrenceId}").combinedClickable(
                            onClick = { if (selected.isNotEmpty()) { if (!state.busy && id != null) actions.toggleSelection(id) }
                                else actions.play(event, section.key) },
                            onLongClick = if (local && id != null && !state.busy) ({ actions.toggleSelection(id) }) else null,
                        ))
                }
            }
            if (state.canLoadMore) item("more") {
                TextButton(actions.loadMore, enabled = !state.loading && !state.failed && !state.busy) { Text(stringResource(Res.string.m4_load_more)) }
            }
            if (state.paginationUnavailable) item("route-boundary") {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(Res.string.history_paging_route_unavailable))
                    TextButton(actions.retry, enabled = !state.loading) { Text(stringResource(Res.string.m4_retry)) }
                }
            }
        }
    }
    removal?.let { ids ->
        AlertDialog(onDismissRequest = { if (!state.busy) removal = null },
            title = { Text(stringResource(Res.string.m4_remove_history)) },
            text = { Text(stringResource(Res.string.m4_history_remove_count, ids.size)) },
            confirmButton = {
                TextButton({ actions.removeEvents(ids); removal = null }, enabled = !state.busy,
                    modifier = Modifier.testTag("history-confirm-remove")) { Text(stringResource(Res.string.m4_remove)) }
            },
            dismissButton = { TextButton({ removal = null }, enabled = !state.busy) { Text(stringResource(Res.string.m4_cancel)) } })
    }
}
