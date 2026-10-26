/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori; © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.shared.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.domain.library.LibrarySortState
import moe.rukamori.archivetune.shared.domain.library.PlaylistSortType
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.ui.component.LibrarySortHeader
import moe.rukamori.archivetune.shared.ui.navigation.LocalShellContentPadding
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.stringResource

data class PlaylistCollectionRow(
    val item: ContentItem, val songCount: Int, val createdEpochMs: Long = 0, val updatedEpochMs: Long = 0,
    val hidden: Boolean = false, val tagIds: Set<String> = emptySet(), val editable: Boolean = true,
    val exportable: Boolean = false,
)
data class PlaylistCollectionUi(
    val rows: List<PlaylistCollectionRow>, val tags: List<PlaylistTagUi> = emptyList(),
    val filterTagIds: Set<String> = emptySet(), val query: String = "", val sort: PlaylistSortType = PlaylistSortType.CUSTOM,
    val descending: Boolean = true, val showHidden: Boolean = false, val locked: Boolean = true, val grid: Boolean = false,
    val loading: Boolean = false, val failed: Boolean = false, val busy: Boolean = false,
)
class PlaylistCollectionActions(
    val back: () -> Unit, val query: (String) -> Unit, val sort: (PlaylistSortType, Boolean) -> Unit,
    val showHidden: () -> Unit, val filterTag: (String) -> Unit, val grid: () -> Unit, val lock: () -> Unit,
    val create: () -> Unit, val manageTags: () -> Unit, val open: (ContentRef) -> Unit, val more: (ContentRef) -> Unit,
    val assignTags: (ContentRef) -> Unit, val exportCsv: (ContentRef) -> Unit,
    /** Relative insertion into the owner's complete custom order, never replace it with a filtered subset. */
    val move: (ref: ContentRef, neighbour: ContentRef, after: Boolean) -> Unit, val retry: () -> Unit,
)

fun visiblePlaylists(state: PlaylistCollectionUi, textOrder: Comparator<String> = defaultCollectionTextOrder): List<PlaylistCollectionRow> {
    val filtered = state.rows.filter {
        (state.showHidden || !it.hidden) && !it.item.title.contains("episode", true) &&
            (state.filterTagIds.isEmpty() || it.tagIds.any { id -> id in state.filterTagIds }) &&
            (state.query.isBlank() || it.item.title.contains(state.query.trim(), true))
    }
    val sorted = when (state.sort) {
        PlaylistSortType.CUSTOM -> return filtered
        PlaylistSortType.NAME -> filtered.sortedWith(compareBy(textOrder) { it.item.title })
        PlaylistSortType.CREATE_DATE -> filtered.sortedBy { it.createdEpochMs }
        PlaylistSortType.SONG_COUNT -> filtered.sortedBy { it.songCount }
        PlaylistSortType.LAST_UPDATED -> filtered.sortedBy { it.updatedEpochMs }
    }
    return if (state.descending) sorted.asReversed() else sorted
}

@Composable
fun PlaylistCollectionScreen(
    state: PlaylistCollectionUi, actions: PlaylistCollectionActions, artwork: ContentArtwork,
    modifier: Modifier = Modifier, contentPadding: PaddingValues = LocalShellContentPadding.current,
    textOrder: Comparator<String> = defaultCollectionTextOrder,
) {
    val rows = remember(state.rows, state.query, state.sort, state.descending, state.showHidden, state.filterTagIds, textOrder) {
        visiblePlaylists(state, textOrder)
    }
    BoxWithConstraints(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val columns = if (state.grid) (if (maxWidth >= 840.dp) 4 else 2) else 1
        LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
            item("header") {
                ManagementHeader(stringResource(Res.string.m4_playlists), actions.back)
                OutlinedTextField(state.query, actions.query, Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    label = { Text(stringResource(Res.string.m4_search_query)) }, singleLine = true)
                LazyRow(contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { TextButton(actions.create, enabled = !state.busy) { Text(stringResource(Res.string.m4_create_playlist)) } }
                    item { TextButton(actions.manageTags) { Text(stringResource(Res.string.m4_manage_tags)) } }
                    item { TextButton(actions.grid) { Text(stringResource(if (state.grid) Res.string.m4_list_view else Res.string.m4_grid_view)) } }
                    item { TextButton(actions.showHidden) { Text(stringResource(if (state.showHidden) Res.string.m4_hide_hidden else Res.string.m4_show_hidden)) } }
                    item { TextButton(actions.lock, Modifier.testTag("playlist-order-lock")) {
                        Text(stringResource(if (state.locked) Res.string.m4_unlock_order else Res.string.m4_lock_order))
                    } }
                }
                LibrarySortHeader(
                    sortState = LibrarySortState(state.sort, state.descending),
                    sortTypes = PlaylistSortType.entries,
                    sortTypeLabel = { sort -> when (sort) {
                        PlaylistSortType.CUSTOM -> Res.string.m4_custom_order
                        PlaylistSortType.NAME -> Res.string.m4_name
                        PlaylistSortType.CREATE_DATE -> Res.string.m4_recently_added
                        PlaylistSortType.SONG_COUNT -> Res.string.m4_track_count
                        PlaylistSortType.LAST_UPDATED -> Res.string.m4_recently_updated
                    } },
                    // A to Z starts ascending when it is chosen, as the baseline playlist list does. A direction toggle
                    // keeps the chosen type, so it is not treated as a new choice of A to Z.
                    onSortStateChange = { next ->
                        val directionToggled = next.type == state.sort && next.descending != state.descending
                        val chosenNameAscending = next.type == PlaylistSortType.NAME && !directionToggled
                        actions.sort(next.type, if (chosenNameAscending) false else next.descending)
                    },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.tags, key = { it.id }) { tag ->
                        FilterChip(tag.id in state.filterTagIds, { actions.filterTag(tag.id) }, label = { Text(tag.name) })
                    }
                }
                LoadFeedback(state.loading, state.failed, rows.isEmpty(), actions.retry)
            }
            if (state.grid) {
                itemsIndexed(rows.chunked(columns), key = { index, _ -> "grid-$index" }) { _, group ->
                    Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        group.forEach { row -> Card(Modifier.weight(1f)) {
                            Column(Modifier.fillMaxWidth().padding(8.dp).clickable { actions.open(row.item.ref) }) {
                                artwork(row.item, Modifier.fillMaxWidth().aspectRatio(1f))
                                Text(row.item.title, style = MaterialTheme.typography.titleMedium)
                                PlaylistCollectionControls(row, state.busy, actions)
                            }
                        } }
                        repeat(columns - group.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            } else itemsIndexed(rows, key = { _, row -> row.item.ref.collectionUiKey() }) { index, row ->
                Column {
                    ListItem(headlineContent = { Text(row.item.title) },
                        supportingContent = { Text(stringResource(Res.string.m4_song_count, row.songCount)) },
                        leadingContent = { artwork(row.item, Modifier.size(56.dp)) },
                        modifier = Modifier.clickable { actions.open(row.item.ref) })
                    PlaylistCollectionControls(row, state.busy, actions)
                    if (!state.locked && state.sort == PlaylistSortType.CUSTOM) Row {
                        TextButton({ actions.move(row.item.ref, rows[index - 1].item.ref, false) },
                            enabled = index > 0 && !state.busy, modifier = Modifier.testTag("playlist-up-${row.item.ref.id}")) {
                            Text(stringResource(Res.string.m4_move_up))
                        }
                        TextButton({ actions.move(row.item.ref, rows[index + 1].item.ref, true) },
                            enabled = index < rows.lastIndex && !state.busy, modifier = Modifier.testTag("playlist-down-${row.item.ref.id}")) {
                            Text(stringResource(Res.string.m4_move_down))
                        }
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun PlaylistCollectionControls(row: PlaylistCollectionRow, busy: Boolean, actions: PlaylistCollectionActions) {
    FlowRow {
        TextButton({ actions.more(row.item.ref) }) { Text(stringResource(Res.string.m4_more)) }
        if (row.editable) TextButton({ actions.assignTags(row.item.ref) }, enabled = !busy) { Text(stringResource(Res.string.m4_assign_tags)) }
        if (row.exportable && row.songCount > 0) TextButton({ actions.exportCsv(row.item.ref) }, enabled = !busy) {
            Text(stringResource(Res.string.m4_export_csv))
        }
    }
}
