package moe.rukamori.archivetune.shared.ui.screens.artistalbum

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.ui.glass.LocalOpticalPolicy
import moe.rukamori.archivetune.shared.ui.navigation.LocalShellContentPadding
import moe.rukamori.archivetune.shared.ui.resources.Res
import moe.rukamori.archivetune.shared.ui.resources.*
import moe.rukamori.archivetune.shared.ui.screens.ContentArtwork
import org.jetbrains.compose.resources.stringResource

/**
 * The artist's library songs in the order the repository returned for [sort]. The screen displays and reports the sort
 * choice; the host re-requests the list. The library list is always complete, so a tapped song queues all of it from
 * that position, and the shuffle action queues a shuffled copy of the same complete list.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ArtistSongsScreen(
    artistName: String,
    songs: List<ContentOccurrence>,
    sort: ArtistSongSort,
    nowPlaying: ArtistAlbumNowPlaying,
    artwork: ContentArtwork,
    host: ArtistAlbumHost,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = LocalShellContentPadding.current,
) {
    val policy = LocalOpticalPolicy.current
    val haptic = LocalHapticFeedback.current
    val scheme = MaterialTheme.colorScheme
    val rows = remember(songs) { songs.distinctBy { it.occurrenceId } }
    val listState = rememberLazyListState()
    val revealed by rememberRevealOnScrollUp(listState)
    val displayName = artistName.ifBlank { stringResource(Res.string.m5_artist_songs) }
    val shuffleAll: () -> Unit = {
        val shuffled = rows.shuffled()
        shuffled.firstOrNull()?.let { host.playOccurrences(shuffled, it.occurrenceId) }
    }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(text = displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        )

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
            ) {
                item(key = "m5-songs-sort") {
                    ArtistSongSortHeader(
                        sort = sort,
                        countText = songCountText(rows.size),
                        onSortChange = { host.setArtistSongSort(it) },
                    )
                }
                itemsIndexed(
                    rows,
                    key = { _, occurrence -> "m5-songs-${occurrence.occurrenceId}" },
                ) { _, occurrence ->
                    val active = occurrence.item.ref.id == nowPlaying.songId
                    ArtistAlbumTrackRow(
                        item = occurrence.item,
                        subtitle = occurrence.item.subtitle,
                        artwork = artwork,
                        active = active,
                        playing = nowPlaying.isPlaying,
                        onClick = {
                            if (active) {
                                host.togglePlayPause()
                            } else {
                                host.playOccurrences(rows, occurrence.occurrenceId)
                            }
                        },
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            host.openMenu(ArtistAlbumMenuTarget(occurrence.item.ref, occurrence))
                        },
                        modifier = if (policy.motion) Modifier.animateItem() else Modifier,
                        trailing = {
                            ArtistAlbumMoreButton(
                                description = stringResource(Res.string.m5_artist_more_options),
                                onClick = { host.openMenu(ArtistAlbumMenuTarget(occurrence.item.ref, occurrence)) },
                            )
                        },
                    )
                }
            }

            ArtistAlbumFab(
                visible = rows.isNotEmpty() && revealed,
                label = stringResource(Res.string.m5_shuffle),
                symbol = ArtistAlbumSymbol.SHUFFLE,
                onClick = shuffleAll,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = contentPadding.calculateBottomPadding() + 16.dp),
            )
        }
    }
}

/**
 * Sort type as a dropdown and direction as a separate toggle, matching the baseline split control. A change is only
 * reported; the host persists it and re-requests the songs in that order.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArtistSongSortHeader(
    sort: ArtistSongSort,
    countText: String,
    onSortChange: (ArtistSongSort) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { expanded = true }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = sortTypeLabel(sort.type),
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.primary,
                )
                ArtistAlbumIcon(
                    symbol = ArtistAlbumSymbol.SORT,
                    tint = scheme.primary,
                    size = 18.dp,
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                ArtistSongSortType.entries.forEach { type ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = sortTypeLabel(type),
                                color = if (type == sort.type) scheme.primary else scheme.onSurface,
                            )
                        },
                        onClick = {
                            expanded = false
                            if (type != sort.type) onSortChange(sort.copy(type = type))
                        },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        ArtistAlbumIcon(
            symbol = if (sort.descending) ArtistAlbumSymbol.ARROW_DOWN else ArtistAlbumSymbol.ARROW_UP,
            tint = scheme.onSurfaceVariant,
            modifier = Modifier
                .clip(CircleShape)
                .clickable { onSortChange(sort.copy(descending = !sort.descending)) }
                .padding(6.dp),
            size = 20.dp,
            description = stringResource(
                if (sort.descending) Res.string.m5_artist_descending else Res.string.m5_artist_ascending,
            ),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = countText,
            style = MaterialTheme.typography.titleSmall,
            color = scheme.secondary,
        )
    }
}

@Composable
private fun sortTypeLabel(type: ArtistSongSortType): String =
    stringResource(
        when (type) {
            ArtistSongSortType.CREATE_DATE -> Res.string.m5_artist_sort_create_date
            ArtistSongSortType.NAME -> Res.string.m5_artist_sort_name
            ArtistSongSortType.PLAY_TIME -> Res.string.m5_artist_sort_play_time
        },
    )

/**
 * "1 song" / "n songs". Plural forms are carried as two strings because the shared resource pattern has no plural
 * accessor; the count is the only argument so both forms stay correct for English and for translations that only
 * need the one-versus-many split.
 */
@Composable
internal fun songCountText(count: Int): String =
    if (count == 1) {
        stringResource(Res.string.m5_artist_song_count_one, count)
    } else {
        stringResource(Res.string.m5_artist_song_count_other, count)
    }
