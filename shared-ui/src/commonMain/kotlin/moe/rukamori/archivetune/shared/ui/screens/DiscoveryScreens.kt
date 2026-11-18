package moe.rukamori.archivetune.shared.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import moe.rukamori.archivetune.shared.domain.repository.*
import moe.rukamori.archivetune.shared.ui.navigation.LocalShellContentPadding
import moe.rukamori.archivetune.shared.ui.resources.*
import moe.rukamori.archivetune.shared.ui.songmetadata.effectiveSong
import org.jetbrains.compose.resources.stringResource
import androidx.compose.runtime.getValue

/** Metadata is already policy-filtered by its repository; IDs/params are never derived from titles. */
data class MoodGenreUi(val key: String, val title: String, val stripeArgb: Long, val request: ContentRequest)
data class BrowseShelfUi(
    val key: String,
    val title: String,
    val collection: ContentRequest,
    val rows: List<ContentOccurrence>,
    val more: ContentRequest?,
    val ranked: Boolean = false,
)
enum class DiscoveryTab { EXPLORE, SUGGESTIONS, MOODS }
data class DiscoveryUi(
    val shelves: List<BrowseShelfUi>,
    val moods: List<MoodGenreUi>,
    val tab: DiscoveryTab = DiscoveryTab.EXPLORE,
    val loading: Boolean = false,
    val failed: Boolean = false,
    /** The suggestion feed's shelves, shown only while [tab] is SUGGESTIONS; the owner's suggestion source fills it. */
    val suggestions: List<BrowseShelfUi> = emptyList(),
)
class DiscoveryActions(
    val back: () -> Unit,
    val tab: (DiscoveryTab) -> Unit,
    val section: (ContentRequest) -> Unit,
    val open: (ContentOccurrence, ContentRequest) -> Unit,
    val more: ((ContentOccurrence) -> Unit)?,
    val refresh: () -> Unit,
    val retry: () -> Unit,
    val charts: () -> Unit,
    val releases: () -> Unit,
    val moods: () -> Unit,
)

@Composable
fun DiscoveryScreen(
    state: DiscoveryUi,
    actions: DiscoveryActions,
    artwork: ContentArtwork,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = LocalShellContentPadding.current,
    embedded: Boolean = false,
) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = contentPadding) {
        item("header") {
            if (!embedded) ManagementHeader(stringResource(Res.string.m4_discover), actions.back)
            if (embedded) PhoneSearchDiscoveryTabs(state.tab, actions.tab)
            else LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(if (embedded) listOf(DiscoveryTab.EXPLORE, DiscoveryTab.SUGGESTIONS) else DiscoveryTab.entries, key = { it.name }) { tab ->
                    FilterChip(tab == state.tab, { actions.tab(tab) },
                        modifier = Modifier.testTag("discovery-tab-${tab.name}"),
                        label = { Text(stringResource(when (tab) {
                            DiscoveryTab.EXPLORE -> Res.string.m4_explore
                            DiscoveryTab.SUGGESTIONS -> Res.string.m4_suggestions
                            DiscoveryTab.MOODS -> Res.string.m4_moods_genres
                        })) })
                }
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp)) {
                item { TextButton(actions.charts) { Text(stringResource(Res.string.m4_charts)) } }
                item { TextButton(actions.releases) { Text(stringResource(Res.string.m4_new_releases)) } }
                item { TextButton(actions.moods) { Text(stringResource(Res.string.m4_moods_genres)) } }
                item { TextButton(actions.refresh, enabled = !state.loading) { Text(stringResource(Res.string.m4_refresh)) } }
            }
            val visibleEmpty =
                when (state.tab) {
                    DiscoveryTab.MOODS -> state.moods.isEmpty()
                    DiscoveryTab.SUGGESTIONS -> state.suggestions.isEmpty()
                    else -> state.shelves.isEmpty() && state.moods.isEmpty()
                }
            LoadFeedback(state.loading, state.failed, visibleEmpty, actions.retry)
        }
        when (state.tab) {
            DiscoveryTab.MOODS -> items(state.moods, key = { "mood-${it.key}" }) { MoodTile(it, actions.section) }
            DiscoveryTab.SUGGESTIONS ->
                items(state.suggestions, key = { "suggestion-${it.key}" }) {
                    Shelf(it, actions.section, actions.open, actions.more, artwork)
                }
            else -> {
                items(state.shelves, key = { "shelf-${it.key}" }) { Shelf(it, actions.section, actions.open, actions.more, artwork) }
                if (embedded) items(state.moods.chunked(2), key = { "moods-${it.first().key}" }) { group ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        group.forEach { mood -> Box(Modifier.weight(1f)) { MoodTile(mood, actions.section) } }
                        if (group.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

class ChartsActions(
    val back: () -> Unit,
    val section: (ContentRequest) -> Unit,
    val open: (ContentOccurrence, ContentRequest) -> Unit,
    val more: ((ContentOccurrence) -> Unit)?,
    val retry: () -> Unit,
    val loadMore: () -> Unit,
)

@Composable
fun ChartsScreen(
    sections: List<BrowseShelfUi>,
    loading: Boolean,
    failed: Boolean,
    continuation: String?,
    actions: ChartsActions,
    artwork: ContentArtwork,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = LocalShellContentPadding.current,
) {
    // Reaching the last shelf with a token still open asks for the next batch; the button is the same request manual.
    val loadMore by rememberUpdatedState(actions.loadMore)
    LaunchedEffect(listState, continuation, loading, failed) {
        if (continuation == null || loading || failed) return@LaunchedEffect
        snapshotFlow {
            listState.layoutInfo.let {
                it.totalItemsCount > 0 && (it.visibleItemsInfo.lastOrNull()?.index ?: -1) >= it.totalItemsCount - 2
            }
        }.distinctUntilChanged().collect { if (it) loadMore() }
    }
    LazyColumn(modifier.fillMaxSize().testTag("charts-list"), state = listState, contentPadding = contentPadding) {
        item("header") {
            ManagementHeader(stringResource(Res.string.m4_charts), actions.back)
            LoadFeedback(loading, failed, sections.isEmpty(), actions.retry)
        }
        items(sections, key = { "chart-${it.key}" }) { Shelf(it, actions.section, actions.open, actions.more, artwork) }
        if (continuation != null) {
            item("charts-more") {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) {
                    Button(onClick = actions.loadMore, enabled = !loading, modifier = Modifier.testTag("charts-load-more")) {
                        Text(stringResource(Res.string.m4_load_more))
                    }
                }
            }
        }
    }
}

@Composable
private fun Shelf(
    shelf: BrowseShelfUi,
    section: (ContentRequest) -> Unit,
    open: (ContentOccurrence, ContentRequest) -> Unit,
    more: ((ContentOccurrence) -> Unit)?,
    artwork: ContentArtwork,
) {
    Column(Modifier.padding(vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(shelf.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            shelf.more?.let { request ->
                TextButton({ section(request) }, Modifier.testTag("browse-more-${shelf.key}")) {
                    Text(stringResource(Res.string.m4_see_all))
                }
            }
        }
        if (shelf.ranked) {
            LazyRow(contentPadding = PaddingValues(horizontal = 8.dp)) {
                itemsIndexed(shelf.rows.chunked(4), key = { i, _ -> i }) { column, rows ->
                    Column(Modifier.widthIn(min = 280.dp, max = 360.dp)) {
                        rows.forEachIndexed { row, occurrence ->
                            ListItem(headlineContent = { Text(effectiveSong(occurrence.item).title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                supportingContent = { Text(effectiveSong(occurrence.item).subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                leadingContent = {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text((column * 4 + row + 1).toString())
                                        artwork(occurrence.item, Modifier.size(48.dp))
                                    }
                                },
                                trailingContent = more?.let { menu -> {
                                    ItemMenuButton({ menu(occurrence) })
                                } },
                                modifier = Modifier.clickable { open(occurrence, shelf.collection) }
                                    .testTag("chart-${shelf.key}-${occurrence.occurrenceId}"))
                        }
                    }
                }
            }
        } else {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(shelf.rows, key = { it.occurrenceId }) { row ->
                    CatalogCard(row, { open(row, shelf.collection) }, more?.let { { it(row) } }, artwork)
                }
            }
        }
    }
}

@Composable
internal fun CatalogCard(row: ContentOccurrence, open: () -> Unit, more: (() -> Unit)?, artwork: ContentArtwork) {
    Column(Modifier.width(160.dp).clickable(onClick = open).testTag("catalog-${row.occurrenceId}")) {
        artwork(row.item, Modifier.fillMaxWidth().aspectRatio(1f))
        Text(effectiveSong(row.item).title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(effectiveSong(row.item).subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        more?.let { ItemMenuButton(it) }
    }
}

@Composable
fun MoodGenresScreen(
    moods: List<MoodGenreUi>,
    loading: Boolean,
    failed: Boolean,
    back: () -> Unit,
    open: (ContentRequest) -> Unit,
    retry: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = LocalShellContentPadding.current,
) {
    LazyVerticalGrid(GridCells.Adaptive(180.dp), modifier.fillMaxSize(), contentPadding = contentPadding) {
        item("header", span = { GridItemSpan(maxLineSpan) }) {
            Column {
                ManagementHeader(stringResource(Res.string.m4_moods_genres), back)
                LoadFeedback(loading, failed, moods.isEmpty(), retry)
            }
        }
        items(moods, key = { "mood-${it.key}" }) { MoodTile(it, open) }
    }
}

@Composable
private fun MoodTile(mood: MoodGenreUi, open: (ContentRequest) -> Unit) {
    Card(onClick = { open(mood.request) }, modifier = Modifier.padding(6.dp).fillMaxWidth()
        .testTag("mood-${mood.key}")) {
        Row(Modifier.heightIn(min = 88.dp)) {
            Box(Modifier.width(8.dp).height(88.dp).background(Color(mood.stripeArgb)))
            Text(mood.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
        }
    }
}

/** Both browse/{browseId} and youtube_browse/{browseId}?params= retain real continuation and menus. */
@Composable
fun BrowseScreen(
    title: String,
    state: PagedContentState,
    actions: ContentActions,
    back: () -> Unit,
    artwork: ContentArtwork,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = LocalShellContentPadding.current,
) {
    val grid = rememberLazyGridState()
    val loadMore by rememberUpdatedState(actions.loadMore)
    LaunchedEffect(grid, state.continuation, state.loading, state.failed) {
        if (state.continuation == null || state.loading || state.failed) return@LaunchedEffect
        snapshotFlow { grid.layoutInfo.let { it.totalItemsCount > 0 &&
            (it.visibleItemsInfo.lastOrNull()?.index ?: -1) >= it.totalItemsCount - 4 } }
            .distinctUntilChanged().collect { if (it) loadMore() }
    }
    LazyVerticalGrid(GridCells.Adaptive(160.dp), modifier.fillMaxSize().testTag("browse-grid"),
        state = grid, contentPadding = contentPadding, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        item("header", span = { GridItemSpan(maxLineSpan) }) { ManagementHeader(title, back) }
        items(state.items, key = { "row-${it.occurrenceId}" }) { row ->
            Column(Modifier.padding(8.dp).fillMaxWidth().clickable { actions.open(row) }
                .testTag("browse-${row.occurrenceId}")) {
                artwork(row.item, Modifier.fillMaxWidth().aspectRatio(1f))
                Text(effectiveSong(row.item).title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(effectiveSong(row.item).subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                actions.more?.let { more ->
                    ItemMenuButton({ more(row) })
                }
            }
        }
        item("status", span = { GridItemSpan(maxLineSpan) }) { PageStatus(state, actions) }
    }
}
