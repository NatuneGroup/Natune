package moe.rukamori.archivetune.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.flow.collect
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.innertube.pages.HistoryPage
import moe.rukamori.archivetune.ui.component.YouTubeListItem
import moe.rukamori.archivetune.ui.screens.settings.SettingsAnchorScreens
import moe.rukamori.archivetune.viewmodels.RemoteHistoryUiState

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun RemoteHistoryFeed(
    listState: LazyListState,
    topPadding: Dp,
    headerContent: @Composable () -> Unit,
    remoteHistoryState: RemoteHistoryUiState,
    filteredSections: List<HistoryPage.HistorySection>,
    isPlaying: Boolean,
    activeMediaId: String?,
    navController: NavController,
    onRetry: () -> Unit,
    continuation: String?,
    loadingMore: Boolean,
    loadMoreFailed: Boolean,
    paginationUnavailable: Boolean,
    onLoadMore: () -> Unit,
    onSongMenu: (SongItem) -> Unit,
    onSongClick: (SongItem) -> Unit,
) {
    LaunchedEffect(listState, continuation, loadingMore, loadMoreFailed, paginationUnavailable) {
        if (continuation == null || loadingMore || loadMoreFailed || paginationUnavailable) return@LaunchedEffect
        snapshotFlow {
            listState.layoutInfo.let { layout ->
                layout.totalItemsCount > 0 &&
                    (layout.visibleItemsInfo.lastOrNull()?.index ?: -1) >= layout.totalItemsCount - 4
            }
        }.collect { if (it) onLoadMore() }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .wrapContentWidth(Alignment.CenterHorizontally)
            .widthIn(max = 840.dp)
            .padding(top = topPadding)
            .windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
            ),
        contentPadding = PaddingValues(bottom = 112.dp),
    ) {
        item("history_overview") { headerContent() }
        if (paginationUnavailable) item("remote_history_route_boundary") {
            Surface(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(stringResource(R.string.history_paging_route_unavailable))
                    TextButton(
                        onClick = { navController.navigate("${SettingsAnchorScreens.INTERNET}?scrollTo=ip_rotation") },
                    ) {
                        Text(stringResource(R.string.history_open_internet_settings))
                    }
                    TextButton(onRetry, enabled = !loadingMore) { Text(stringResource(R.string.retry)) }
                }
            }
        }
        when (remoteHistoryState) {
            RemoteHistoryUiState.Loading -> item("remote_history_loading") {
                HistoryStateCard(
                    title = stringResource(R.string.history_remote_loading),
                    description = stringResource(R.string.history_remote_summary),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    loading = true,
                )
            }
            RemoteHistoryUiState.Empty -> item("remote_history_empty") {
                HistoryStateCard(
                    title = stringResource(R.string.history_remote_empty_title),
                    description = stringResource(R.string.history_remote_empty_desc),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    icon = R.drawable.history,
                )
            }
            RemoteHistoryUiState.Error -> item("remote_history_error") {
                HistoryStateCard(
                    title = stringResource(R.string.history_remote_error_title),
                    description = stringResource(R.string.history_remote_error_desc),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    actionLabel = stringResource(R.string.retry),
                    onActionClick = onRetry,
                    icon = R.drawable.history,
                )
            }
            is RemoteHistoryUiState.Success -> {
                if (filteredSections.isEmpty()) {
                    item("remote_history_search_empty") {
                        HistoryStateCard(
                            title = stringResource(R.string.history_no_results_title),
                            description = stringResource(R.string.history_no_results_desc),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            icon = R.drawable.search,
                        )
                    }
                } else {
                    filteredSections.forEach { section ->
                        stickyHeader(key = "header_${section.title}") {
                            HistorySectionHeader(title = section.title, songCount = section.songs.size)
                        }
                        itemsIndexed(
                            items = section.songs,
                            key = { _, song -> "song_${section.title.length}:${section.title}:${song.id}" },
                            contentType = { _, _ -> "remote_history_song" },
                        ) { index, song ->
                            val isActive = song.id == activeMediaId
                            HistorySongGroupItem(
                                index = index,
                                lastIndex = section.songs.lastIndex,
                                isActive = isActive,
                                modifier = Modifier.animateItem(),
                            ) { containerColor ->
                                YouTubeListItem(
                                    item = song,
                                    isActive = isActive,
                                    isPlaying = isPlaying,
                                    swipeContentBackgroundColor = containerColor,
                                    showActiveContainer = false,
                                    trailingContent = {
                                        IconButton(onClick = { onSongMenu(song) }) {
                                            Icon(
                                                painter = painterResource(R.drawable.more_vert),
                                                contentDescription = null,
                                            )
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth().combinedClickable(
                                        onClick = { onSongClick(song) },
                                        onLongClick = { onSongMenu(song) },
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }
        if (!paginationUnavailable && remoteHistoryState is RemoteHistoryUiState.Success && (continuation != null || loadMoreFailed)) {
            item("remote_history_more") {
                Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (loadingMore) {
                        ContainedLoadingIndicator()
                    } else if (loadMoreFailed) {
                        TextButton(onRetry) { Text(stringResource(R.string.retry)) }
                    } else {
                        TextButton(onLoadMore) { Text(stringResource(R.string.history_load_more)) }
                    }
                }
            }
        }
    }
}
