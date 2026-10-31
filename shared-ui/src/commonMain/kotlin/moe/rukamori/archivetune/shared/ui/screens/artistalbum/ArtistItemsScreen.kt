package moe.rukamori.archivetune.shared.ui.screens.artistalbum

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentRequest
import moe.rukamori.archivetune.shared.domain.repository.TypedContentRepository
import moe.rukamori.archivetune.shared.ui.navigation.LocalShellContentPadding
import moe.rukamori.archivetune.shared.ui.marquee.MarqueeText
import moe.rukamori.archivetune.shared.ui.resources.*
import moe.rukamori.archivetune.shared.ui.screens.ContentActions
import moe.rukamori.archivetune.shared.ui.screens.ContentArtwork
import moe.rukamori.archivetune.shared.ui.screens.PageStatus
import moe.rukamori.archivetune.shared.ui.screens.rememberPagedContent
import org.jetbrains.compose.resources.stringResource
import androidx.compose.runtime.setValue

private val ArtistGridCellMinSize = 168.dp
private const val ArtistItemsLookahead = 4

/**
 * One provider section, opened from an artist page's "more" affordance. Paging belongs to the presenter. Whole-list
 * play is offered only when the presenter reports the list complete, so a partial page is never queued as the full
 * section. A single song plays its own provider watch queue while the list is still loading.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ArtistItemsScreen(
    title: String,
    request: ContentRequest,
    layout: ArtistShelfLayout,
    repository: TypedContentRepository,
    nowPlaying: ArtistAlbumNowPlaying,
    artwork: ContentArtwork,
    host: ArtistAlbumHost,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = LocalShellContentPadding.current,
) {
    val scheme = MaterialTheme.colorScheme
    val presenter = rememberPagedContent(repository, request)
    val state by presenter.state.collectAsState()
    val actions = remember(presenter) {
        ContentActions(
            open = { _ -> },
            more = null,
            loadMore = { presenter.loadMore() },
            retry = { presenter.retry() },
        )
    }
    val rows = remember(state.items) { state.items.distinctBy { it.item.ref } }
    val songs = remember(state.items) { state.items.filter { it.item.ref.kind == ContentKind.SONG } }
    val listMode = layout == ArtistShelfLayout.LIST && state.items.firstOrNull()?.item?.ref?.kind == ContentKind.SONG

    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    // The list and grid layout infos share no supertype, so each branch reads its own and yields plain numbers.
    val nearEnd by remember(listMode) {
        derivedStateOf {
            val lastVisible = if (listMode) {
                listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
            } else {
                gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
            }
            val total = if (listMode) listState.layoutInfo.totalItemsCount else gridState.layoutInfo.totalItemsCount
            lastVisible != null && total > 0 && lastVisible >= total - ArtistItemsLookahead
        }
    }
    // loadMore is a no-op while a page is loading or after a failure, so this cannot spin on a failed page.
    LaunchedEffect(nearEnd, state.continuation, state.loading, state.failed) {
        if (nearEnd) presenter.loadMore()
    }

    val playAll: () -> Unit = {
        if (state.complete) {
            songs.firstOrNull()?.let { host.playOccurrences(songs, it.occurrenceId) }
        }
    }
    val shuffleAll: () -> Unit = {
        if (state.complete) {
            val shuffled = songs.shuffled()
            shuffled.firstOrNull()?.let { host.playOccurrences(shuffled, it.occurrenceId) }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                MarqueeText(text = title)
            },
            navigationIcon = {
                // A plain box carries the long press, which IconButton would swallow.
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .combinedClickable(onClick = { host.back() }, onLongClick = { host.backToMain() }),
                    contentAlignment = Alignment.Center,
                ) {
                    ArtistAlbumIcon(
                        symbol = ArtistAlbumSymbol.BACK,
                        tint = scheme.onSurface,
                        description = stringResource(Res.string.m5_artist_back),
                    )
                }
            },
            actions = {
                if (songs.isNotEmpty()) {
                    IconButton(onClick = playAll, enabled = state.complete) {
                        ArtistAlbumIcon(
                            symbol = ArtistAlbumSymbol.PLAY,
                            tint = scheme.onSurface,
                            description = stringResource(Res.string.m5_play),
                        )
                    }
                    IconButton(onClick = shuffleAll, enabled = state.complete) {
                        ArtistAlbumIcon(
                            symbol = ArtistAlbumSymbol.SHUFFLE,
                            tint = scheme.onSurface,
                            description = stringResource(Res.string.m5_shuffle),
                        )
                    }
                }
            },
        )

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            if (listMode) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
                ) {
                    itemsIndexed(rows, key = { _, occurrence -> "m5-items-${occurrence.occurrenceId}" }) { _, occurrence ->
                        ArtistAlbumTrackRow(
                            item = occurrence.item,
                            subtitle = occurrence.item.subtitle,
                            artwork = artwork,
                            active = occurrence.isActive(nowPlaying),
                            playing = nowPlaying.isPlaying,
                            onClick = { activate(host, occurrence, songs, state.complete, nowPlaying.songId) },
                            onLongClick = { host.openMenu(ArtistAlbumMenuTarget(occurrence.item.ref, occurrence)) },
                            trailing = {
                                ArtistAlbumMoreButton(
                                    description = stringResource(Res.string.m5_artist_more_options),
                                    onClick = { host.openMenu(ArtistAlbumMenuTarget(occurrence.item.ref, occurrence)) },
                                )
                            },
                        )
                    }
                    item(key = "m5-items-status") {
                        PageStatus(state, actions)
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = ArtistGridCellMinSize),
                    state = gridState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
                ) {
                    items(rows, key = { "m5-items-${it.occurrenceId}" }) { occurrence ->
                        ArtistAlbumCard(
                            item = occurrence.item,
                            subtitle = occurrence.item.subtitle,
                            artwork = artwork,
                            active = occurrence.isActive(nowPlaying),
                            onClick = { activate(host, occurrence, songs, state.complete, nowPlaying.songId) },
                            onLongClick = { host.openMenu(ArtistAlbumMenuTarget(occurrence.item.ref, occurrence)) },
                            width = null,
                        )
                    }
                    item(key = "m5-items-status", span = { GridItemSpan(maxLineSpan) }) {
                        PageStatus(state, actions)
                    }
                }
            }
        }
    }
}

private fun ContentOccurrence.isActive(nowPlaying: ArtistAlbumNowPlaying): Boolean =
    when (item.ref.kind) {
        ContentKind.SONG -> item.ref.id == nowPlaying.songId
        ContentKind.ALBUM -> item.ref.id == nowPlaying.albumId
        else -> false
    }

/**
 * Routes one row. Songs queue the whole page only once it is complete; a partial page starts the song's own provider
 * watch queue instead. Albums, artists and playlists open through the navigation owner.
 */
private fun activate(
    host: ArtistAlbumHost,
    occurrence: ContentOccurrence,
    pageSongs: List<ContentOccurrence>,
    complete: Boolean,
    nowSongId: String?,
) {
    val ref = occurrence.item.ref
    when (ref.kind) {
        ContentKind.SONG -> when {
            ref.id == nowSongId -> host.togglePlayPause()
            complete -> host.playOccurrences(pageSongs, occurrence.occurrenceId)
            else -> host.playSong(occurrence, emptyList())
        }
        ContentKind.ALBUM, ContentKind.ARTIST, ContentKind.PLAYLIST -> host.openContent(ref)
        ContentKind.PODCAST, ContentKind.EPISODE -> Unit
    }
}
