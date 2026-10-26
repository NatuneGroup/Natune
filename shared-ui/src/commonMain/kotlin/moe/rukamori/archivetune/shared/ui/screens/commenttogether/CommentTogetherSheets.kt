package moe.rukamori.archivetune.shared.ui.screens.commenttogether

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import moe.rukamori.archivetune.shared.data.listentogether.TrackInfo
import moe.rukamori.archivetune.shared.ui.resources.Res
import moe.rukamori.archivetune.shared.ui.screens.ChatEmojiCategory
import moe.rukamori.archivetune.shared.ui.screens.EmojiCatalog
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** Debounce window for the song search field, so a query is sent once the typing pauses. */
private const val CommentSongSearchDebounceMillis = 400L

/** What the song picker shows below its search field. */
private sealed interface CommentSongSearch {
    data object Idle : CommentSongSearch
    data object Searching : CommentSongSearch
    data class Found(val tracks: List<TrackInfo>) : CommentSongSearch
    data object Failed : CommentSongSearch
}

/**
 * Full emoji sheet opened from the anchored popup's "all emojis" chip. Every category of [EmojiCatalog] is listed
 * under its localized header; picking an emoji reports it and the caller decides what the pick acts on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CommentEmojiPickerSheet(
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(44.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight * 0.72f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                EmojiCatalog.forEach { (category, emojis) ->
                    // A full-row header: the baseline placed it in one grid cell, which squeezed the label.
                    item(
                        key = "comment-emoji-header-${category.name}",
                        span = { GridItemSpan(maxLineSpan) },
                    ) {
                        Text(
                            text = stringResource(commentEmojiCategoryLabel(category)),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 4.dp, top = 10.dp, bottom = 2.dp),
                        )
                    }
                    // Keyed by position, so an emoji listed twice in one category can never collide.
                    items(
                        count = emojis.size,
                        key = { index -> "comment-emoji-${category.name}-$index" },
                    ) { index ->
                        CommentEmojiCell(emoji = emojis[index], onPick = onPick)
                    }
                }
            }
        }
    }
}

@Composable
private fun CommentEmojiCell(emoji: String, onPick: (String) -> Unit) {
    Box(
        modifier = Modifier
            .padding(4.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { onPick(emoji) },
            )
            .padding(6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = emoji, fontSize = 24.sp)
    }
}

private fun commentEmojiCategoryLabel(category: ChatEmojiCategory): StringResource = when (category) {
    ChatEmojiCategory.SMILEYS -> Res.string.m4_emoji_smileys
    ChatEmojiCategory.PEOPLE -> Res.string.m4_emoji_people
    ChatEmojiCategory.ANIMALS -> Res.string.m4_emoji_animals
    ChatEmojiCategory.FOOD -> Res.string.m4_emoji_food
    ChatEmojiCategory.TRAVEL -> Res.string.m4_emoji_travel
    ChatEmojiCategory.ACTIVITIES -> Res.string.m4_emoji_activities
    ChatEmojiCategory.OBJECTS -> Res.string.m4_emoji_objects
    ChatEmojiCategory.SYMBOLS -> Res.string.m4_emoji_symbols
    ChatEmojiCategory.FLAGS -> Res.string.m4_emoji_flags
}

/**
 * Song share sheet: a debounced search through the host ([searchSongs]), the room's current song as a quick action,
 * and the results. Any row or the current-song row reports the choice; the host performs the share.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CommentSongPickerSheet(
    currentTrack: TrackInfo?,
    searchSongs: suspend (query: String) -> Result<List<TrackInfo>>,
    thumbnail: CommentThumbnail,
    onShareTrack: (TrackInfo) -> Unit,
    onShareCurrent: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        val currentSearch by rememberUpdatedState(searchSongs)
        val focusManager = LocalFocusManager.current
        var query by remember { mutableStateOf("") }
        var search by remember { mutableStateOf<CommentSongSearch>(CommentSongSearch.Idle) }

        LaunchedEffect(query) {
            if (query.isBlank()) {
                search = CommentSongSearch.Idle
                return@LaunchedEffect
            }
            search = CommentSongSearch.Searching
            // Captured so a search overtaken by a newer keystroke cannot land its results or clear the loading state.
            val searchedFor = query
            delay(CommentSongSearchDebounceMillis)
            val result = currentSearch(searchedFor)
            if (searchedFor == query) {
                val next: CommentSongSearch = result.fold(
                    onSuccess = { tracks -> CommentSongSearch.Found(tracks) },
                    onFailure = { CommentSongSearch.Failed },
                )
                search = next
            }
        }

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .heightIn(max = maxHeight * 0.82f),
            ) {
                Text(
                    text = stringResource(Res.string.m4c_pick_song),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 10.dp),
                )

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(Res.string.m4c_pick_song_hint)) },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(Res.drawable.m4c_icon_search),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                    modifier = Modifier.fillMaxWidth(),
                )

                currentTrack?.let { track ->
                    Spacer(Modifier.height(10.dp))
                    CommentNowPlayingRow(track = track, onShare = onShareCurrent)
                }

                Spacer(Modifier.height(8.dp))

                when (val state = search) {
                    CommentSongSearch.Idle -> CommentPickerHint(text = stringResource(Res.string.m4c_pick_song_hint))
                    CommentSongSearch.Searching -> Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 28.dp),
                    ) {
                        CircularProgressIndicator()
                    }
                    CommentSongSearch.Failed -> CommentPickerHint(
                        text = stringResource(Res.string.m4c_pick_song_failed),
                        error = true,
                    )
                    is CommentSongSearch.Found -> {
                        if (state.tracks.isEmpty()) {
                            CommentPickerHint(text = stringResource(Res.string.m4c_pick_song_no_results))
                        } else {
                            LazyColumn(
                                contentPadding = PaddingValues(vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                // Paged or repeated search results can repeat a track id, and LazyColumn throws on a duplicate key.
                                items(items = state.tracks.distinctBy { it.id }, key = { track -> track.id }) { track ->
                                    CommentSongPickerRow(
                                        track = track,
                                        thumbnail = thumbnail,
                                        onPick = { onShareTrack(track) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommentNowPlayingRow(track: TrackInfo, onShare: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable(onClick = onShare)
                .padding(8.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape),
            ) {
                Icon(
                    painter = painterResource(Res.drawable.m4c_icon_music_note),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(Res.string.m4c_now_playing),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "${track.title} • ${track.artist}",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                painter = painterResource(Res.drawable.m4c_icon_send_chat),
                contentDescription = stringResource(Res.string.m4c_share_song),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun CommentSongPickerRow(
    track: TrackInfo,
    thumbnail: CommentThumbnail,
    onPick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onPick)
            .padding(horizontal = 4.dp, vertical = 6.dp),
    ) {
        thumbnail(track.thumbnail, Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = track.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        val duration = formatCommentTrackDuration(track.duration)
        if (duration.isNotEmpty()) {
            Text(
                text = duration,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(4.dp))
        Icon(
            painter = painterResource(Res.drawable.m4c_icon_send_chat),
            contentDescription = stringResource(Res.string.m4c_share_song),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun CommentPickerHint(text: String, error: Boolean = false) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 28.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
