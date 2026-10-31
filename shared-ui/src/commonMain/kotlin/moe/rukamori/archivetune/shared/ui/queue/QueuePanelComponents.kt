package moe.rukamori.archivetune.shared.ui.queue

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.domain.queue.QueueRepeatMode
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.ui.player.PlayerIcon
import moe.rukamori.archivetune.shared.ui.player.PlayerSymbol
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun QueuePanelHeader(
    summary: String, source: String?, infinite: Boolean, loading: Boolean, locked: Boolean,
    shuffle: Boolean, repeat: QueueRepeatMode, ready: Boolean, hasTracks: Boolean,
    onInfinite: (Boolean) -> Unit, onShuffle: () -> Unit, onRepeat: () -> Unit, onLock: () -> Unit,
    onJump: () -> Unit, onSave: () -> Unit, onClear: () -> Unit, onSelect: () -> Unit,
) {
    var more by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.queue_title), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onJump, enabled = hasTracks, modifier = Modifier.testTag("queue-jump")) {
                Text(stringResource(Res.string.queue_jump_current))
            }
            Box {
                PlayerIcon(PlayerSymbol.MORE, stringResource(Res.string.queue_actions), { more = true })
                DropdownMenu(more, { more = false }) {
                    DropdownMenuItem(text = { Text(stringResource(Res.string.queue_save_playlist)) }, enabled = hasTracks,
                        onClick = { more = false; onSave() })
                    DropdownMenuItem(text = { Text(stringResource(Res.string.queue_clear_upcoming)) }, enabled = ready && hasTracks,
                        onClick = { more = false; onClear() })
                    DropdownMenuItem(text = { Text(stringResource(Res.string.queue_select_tracks)) }, enabled = hasTracks,
                        onClick = { more = false; onSelect() })
                    DropdownMenuItem(text = { Text(stringResource(if (locked) Res.string.queue_unlock_edits else Res.string.queue_lock_edits)) },
                        enabled = ready, onClick = { more = false; onLock() })
                }
            }
        }
        source?.let {
            Text(stringResource(Res.string.queue_source_title, it), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall)
        }
        Text(summary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(vertical = 4.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            // Reuse the player controls: the player-animation lane owns their drawing and motion.
            PlayerIcon(PlayerSymbol.SHUFFLE, stringResource(Res.string.m5_shuffle), onShuffle,
                enabled = hasTracks, selected = shuffle, modifier = Modifier.testTag("queue-shuffle"))
            PlayerIcon(PlayerSymbol.REPEAT, stringResource(Res.string.m5_repeat), onRepeat, enabled = ready,
                selected = repeat != QueueRepeatMode.OFF, stateLabel = stringResource(when (repeat) {
                    QueueRepeatMode.ONE -> Res.string.m5_repeat_one
                    QueueRepeatMode.ALL -> Res.string.m5_on
                    QueueRepeatMode.OFF -> Res.string.m5_off
                }), modifier = Modifier.testTag("queue-repeat"))
            FilterChip(selected = infinite, onClick = { onInfinite(!infinite) }, enabled = ready,
                label = { Text(stringResource(Res.string.queue_infinite)) },
                leadingIcon = {
                    if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text(stringResource(Res.string.queue_infinite_symbol), style = MaterialTheme.typography.titleLarge)
                }, modifier = Modifier.testTag("queue-infinite"))
        }
    }
}

/** Height of the one-row header that replaces the expanded header once the list scrolls. */
private val QueuePanelCompactHeight = 56.dp

/**
 * The one-row header shown over the list once it scrolls. The playback toggles stay on the row and the queue-level
 * actions move into its menu, so nothing is lost while the expanded header is out of view.
 */
@Composable
internal fun QueuePanelCompactHeader(
    hasTracks: Boolean, ready: Boolean, infinite: Boolean, locked: Boolean, shuffle: Boolean, repeat: QueueRepeatMode,
    onInfinite: (Boolean) -> Unit, onShuffle: () -> Unit, onRepeat: () -> Unit, onLock: () -> Unit,
    onJump: () -> Unit, onSave: () -> Unit, onClear: () -> Unit, onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var more by remember { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth().height(QueuePanelCompactHeight)
            .background(MaterialTheme.colorScheme.surface).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(Res.string.queue_title), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        PlayerIcon(PlayerSymbol.SHUFFLE, stringResource(Res.string.m5_shuffle), onShuffle,
            enabled = hasTracks, selected = shuffle, modifier = Modifier.testTag("queue-compact-shuffle"))
        PlayerIcon(PlayerSymbol.REPEAT, stringResource(Res.string.m5_repeat), onRepeat, enabled = ready,
            selected = repeat != QueueRepeatMode.OFF, stateLabel = stringResource(when (repeat) {
                QueueRepeatMode.ONE -> Res.string.m5_repeat_one
                QueueRepeatMode.ALL -> Res.string.m5_on
                QueueRepeatMode.OFF -> Res.string.m5_off
            }), modifier = Modifier.testTag("queue-compact-repeat"))
        Box {
            PlayerIcon(PlayerSymbol.MORE, stringResource(Res.string.queue_actions), { more = true })
            DropdownMenu(more, { more = false }) {
                DropdownMenuItem(text = { Text(stringResource(Res.string.queue_jump_current)) }, enabled = hasTracks,
                    onClick = { more = false; onJump() })
                DropdownMenuItem(text = { Text(stringResource(Res.string.queue_infinite)) }, enabled = ready,
                    leadingIcon = { Checkbox(checked = infinite, onCheckedChange = null, enabled = ready) },
                    onClick = { more = false; onInfinite(!infinite) })
                DropdownMenuItem(text = { Text(stringResource(Res.string.queue_save_playlist)) }, enabled = hasTracks,
                    onClick = { more = false; onSave() })
                DropdownMenuItem(text = { Text(stringResource(Res.string.queue_clear_upcoming)) }, enabled = ready && hasTracks,
                    onClick = { more = false; onClear() })
                DropdownMenuItem(text = { Text(stringResource(Res.string.queue_select_tracks)) }, enabled = hasTracks,
                    onClick = { more = false; onSelect() })
                DropdownMenuItem(text = { Text(stringResource(if (locked) Res.string.queue_unlock_edits else Res.string.queue_lock_edits)) },
                    enabled = ready, onClick = { more = false; onLock() })
            }
        }
    }
}

@Composable
internal fun QueueSelectionActions(
    count: Int, all: Boolean, onAll: () -> Unit, onClose: () -> Unit, onSave: () -> Unit, onRemove: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Text(stringResource(Res.string.queue_selection_count, count), style = MaterialTheme.typography.labelMedium)
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            if (!all) TextButton(onClick = onAll) { Text(stringResource(Res.string.queue_select_all)) }
            TextButton(onClick = onSave) { Text(stringResource(Res.string.queue_save_playlist)) }
            TextButton(onClick = onRemove) { Text(stringResource(Res.string.queue_remove)) }
            TextButton(onClick = onClose) { Text(stringResource(Res.string.queue_clear_selection)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun QueuePanelTrack(
    entry: ContentOccurrence, current: Boolean, selected: Boolean, selecting: Boolean, locked: Boolean,
    modifier: Modifier, artwork: @Composable (Modifier) -> Unit, handle: @Composable () -> Unit,
    onSelect: () -> Unit, onPlay: () -> Unit, onRemove: () -> Unit, onNext: () -> Unit, onTop: () -> Unit,
    onUp: (() -> Unit)?, onDown: (() -> Unit)?,
) {
    var more by remember(entry.occurrenceId) { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val dismiss = rememberSwipeToDismissBoxState()
    LaunchedEffect(dismiss.currentValue) {
        if (dismiss.currentValue != SwipeToDismissBoxValue.Settled) {
            onRemove()
            dismiss.reset()
        }
    }
    val row: @Composable () -> Unit = {
        Row(modifier.fillMaxWidth().background(if (current) colors.secondaryContainer else colors.surface)
            .combinedClickable(onClick = { if (selecting) onSelect() else onPlay() }, onLongClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 6.dp).testTag("queue-row-${entry.occurrenceId}"),
            verticalAlignment = Alignment.CenterVertically) {
            if (selecting) Checkbox(selected, { onSelect() })
            artwork(Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(entry.item.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontWeight = if (current) FontWeight.Bold else FontWeight.Normal)
                val artist = entry.item.artists.joinToString(", ") { it.name }.ifEmpty { entry.item.subtitle }
                if (artist.isNotEmpty()) Text(artist, style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, color = colors.onSurfaceVariant)
            }
            handle()
            Box {
                PlayerIcon(PlayerSymbol.MORE, stringResource(Res.string.queue_actions), { more = true })
                DropdownMenu(more, { more = false }) {
                    DropdownMenuItem(text = { Text(stringResource(Res.string.queue_move_next)) }, enabled = !current,
                        onClick = { more = false; onNext() })
                    DropdownMenuItem(text = { Text(stringResource(Res.string.queue_move_top)) }, enabled = !locked,
                        onClick = { more = false; onTop() })
                    DropdownMenuItem(text = { Text(stringResource(Res.string.queue_move_up)) }, enabled = !locked && onUp != null,
                        onClick = { more = false; onUp?.invoke() })
                    DropdownMenuItem(text = { Text(stringResource(Res.string.queue_move_down)) }, enabled = !locked && onDown != null,
                        onClick = { more = false; onDown?.invoke() })
                    DropdownMenuItem(text = { Text(stringResource(Res.string.queue_select_tracks)) },
                        onClick = { more = false; onSelect() })
                    DropdownMenuItem(text = { Text(stringResource(Res.string.queue_remove)) },
                        onClick = { more = false; onRemove() })
                }
            }
        }
    }
    if (locked || selecting) row()
    else SwipeToDismissBox(state = dismiss, backgroundContent = {}, content = { row() })
}
