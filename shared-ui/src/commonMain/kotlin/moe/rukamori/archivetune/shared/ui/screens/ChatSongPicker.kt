package moe.rukamori.archivetune.shared.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.ui.glass.BusyCircularProgress
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

data class ChatSongPickerUi(val query: String, val page: PagedContentState, val current: ContentItem?, val busy: Boolean)
data class ChatSongPickerActions(
    val query: (String) -> Unit, val search: () -> Unit, val share: (ContentItem) -> Unit,
    val retry: () -> Unit, val loadMore: () -> Unit, val dismiss: () -> Unit,
)

/**
 * Share-a-song sheet: a search field, a now-playing quick-share row and song rows with artwork,
 * duration and a send affordance. Search timing, cancellation and paging stay with the owner;
 * this composable only renders the state it is handed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatSongPicker(state: ChatSongPickerUi, actions: ChatSongPickerActions, artwork: ContentArtwork) {
    ModalBottomSheet(
        onDismissRequest = { if (!state.busy) actions.dismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
            Text(
                text = stringResource(Res.string.m4_chat_pick_song),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 10.dp),
            )
            OutlinedTextField(
                value = state.query,
                onValueChange = actions.query,
                enabled = !state.busy,
                placeholder = { Text(stringResource(Res.string.m4_chat_pick_song_hint)) },
                leadingIcon = {
                    Icon(
                        painter = painterResource(Res.drawable.m4_icon_search),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (!state.busy && state.query.isNotBlank()) actions.search() }),
                modifier = Modifier.fillMaxWidth(),
            )
            state.current?.let { current ->
                Spacer(Modifier.height(10.dp))
                NowPlayingShare(current = current, enabled = !state.busy, onShare = { actions.share(current) })
            }
            Spacer(Modifier.height(8.dp))
            PickerResults(state = state, actions = actions, artwork = artwork)
        }
    }
}

@Composable
private fun PickerResults(state: ChatSongPickerUi, actions: ChatSongPickerActions, artwork: ContentArtwork) {
    val page = state.page
    when {
        state.query.isBlank() -> PickerHint(stringResource(Res.string.m4_chat_pick_song_hint))
        page.items.isEmpty() && page.loading -> Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
        ) { BusyCircularProgress() }
        page.items.isEmpty() && page.failed -> PageStatus(page, ContentActions({ }, null, actions.loadMore, actions.retry))
        page.items.isEmpty() && page.initialized -> PickerHint(stringResource(Res.string.m4_chat_pick_song_no_results))
        page.items.isEmpty() -> Unit
        else -> LazyColumn(
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.heightIn(max = 400.dp),
        ) {
            items(page.items, key = { it.occurrenceId }) { row ->
                PickerSongRow(item = row.item, artwork = artwork, enabled = !state.busy, onPick = { actions.share(row.item) })
            }
            item { PageStatus(page, ContentActions({ row -> actions.share(row.item) }, null, actions.loadMore, actions.retry)) }
        }
    }
}

@Composable
private fun NowPlayingShare(current: ContentItem, enabled: Boolean, onShare: () -> Unit) {
    val detail = listOf(current.title, current.subtitle).filter { it.isNotBlank() }.joinToString(" • ")
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable(enabled = enabled, onClick = onShare).padding(8.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(44.dp).clip(CircleShape),
            ) {
                Icon(
                    painter = painterResource(Res.drawable.m4_icon_music_note),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(Res.string.m4_now_playing),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                painter = painterResource(Res.drawable.m4_icon_send),
                contentDescription = stringResource(Res.string.m4_chat_share_current_song),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun PickerSongRow(item: ContentItem, artwork: ContentArtwork, enabled: Boolean, onPick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onPick)
            .padding(horizontal = 4.dp, vertical = 6.dp),
    ) {
        artwork(item, Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.subtitle.isNotBlank()) {
                Text(
                    text = item.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        val duration = pickerDurationLabel(item.durationMs)
        if (duration.isNotEmpty()) {
            Text(
                text = duration,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(4.dp))
        }
        Icon(
            painter = painterResource(Res.drawable.m4_icon_send),
            contentDescription = stringResource(Res.string.m4_chat_share_song),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun PickerHint(text: String) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** m:ss, switching to h:mm:ss from one hour; blank when the duration is unknown. */
private fun pickerDurationLabel(durationMs: Long?): String {
    val totalSeconds = (durationMs ?: 0L) / 1000L
    if (totalSeconds <= 0L) return ""
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = (totalSeconds % 60L).toString().padStart(2, '0')
    return if (hours > 0L) "$hours:${minutes.toString().padStart(2, '0')}:$seconds" else "$minutes:$seconds"
}
