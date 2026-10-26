package moe.rukamori.archivetune.shared.ui.screens.artistalbum

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.ui.navigation.LocalShellContentPadding
import moe.rukamori.archivetune.shared.ui.resources.Res
import moe.rukamori.archivetune.shared.ui.resources.*
import moe.rukamori.archivetune.shared.ui.screens.ContentArtwork
import org.jetbrains.compose.resources.stringResource

private val ArtistAlbumsGridCellMinSize = 168.dp

/** The artist's library albums. The list arrives already filtered by the library owner's explicit/video policy. */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ArtistAlbumsScreen(
    title: String,
    albums: List<ArtistReleaseUi>,
    nowPlaying: ArtistAlbumNowPlaying,
    artwork: ContentArtwork,
    host: ArtistAlbumHost,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = LocalShellContentPadding.current,
) {
    val scheme = MaterialTheme.colorScheme
    val rows = albums.distinctBy { it.album }
    val gridState = rememberLazyGridState()

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(text = title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            navigationIcon = {
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
        )

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = ArtistAlbumsGridCellMinSize),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
            ) {
                item(key = "m5-albums-header", span = { GridItemSpan(maxLineSpan) }) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                    ) {
                        Text(
                            text = albumCountText(rows.size),
                            style = MaterialTheme.typography.titleSmall,
                            color = scheme.secondary,
                            modifier = Modifier.align(Alignment.CenterEnd),
                        )
                    }
                }
                items(rows, key = { "m5-albums-${it.album.id}" }) { release ->
                    ArtistAlbumCard(
                        item = release.asContentItem(),
                        subtitle = release.year?.toString().orEmpty(),
                        artwork = artwork,
                        active = release.album.id == nowPlaying.albumId,
                        onClick = { host.openContent(release.album) },
                        onLongClick = { host.openMenu(ArtistAlbumMenuTarget(release.album)) },
                        width = null,
                    )
                }
            }
        }
    }
}

@Composable
private fun albumCountText(count: Int): String =
    if (count == 1) {
        stringResource(Res.string.m5_artist_album_count_one, count)
    } else {
        stringResource(Res.string.m5_artist_album_count_other, count)
    }
