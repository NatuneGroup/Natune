@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package moe.rukamori.archivetune.shared.ui.queue

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.domain.queue.QueueRepeatMode
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.ui.player.PlayerGlyph
import moe.rukamori.archivetune.shared.ui.player.PlayerIcon
import moe.rukamori.archivetune.shared.ui.player.PlayerSymbol
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

internal data class PhoneQueuePlayerActions(
    val occurrenceId: String?, val liked: Boolean,
    val onLike: (() -> Unit)?, val onMore: (() -> Unit)?,
)

internal val LocalPhoneQueuePlayerActions = staticCompositionLocalOf {
    PhoneQueuePlayerActions(null, false, null, null)
}

@Composable
internal fun PhoneQueueDragStrip() {
    Box(Modifier.fillMaxWidth().height(12.dp).testTag("queue-drag-strip"), contentAlignment = Alignment.Center) {
        Box(Modifier.size(48.dp, 5.dp).clip(RoundedCornerShape(2.5.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = .4f)))
    }
}

@Composable
internal fun PhoneQueueHeader(
    current: ContentOccurrence?, summary: String, source: String?,
    infinite: Boolean, loading: Boolean, locked: Boolean, shuffle: Boolean, repeat: QueueRepeatMode,
    ready: Boolean, hasTracks: Boolean, artwork: @Composable (ContentOccurrence, Modifier) -> Unit,
    onInfinite: (Boolean) -> Unit, onShuffle: () -> Unit, onRepeat: () -> Unit, onLock: () -> Unit,
    onJump: () -> Unit, onSave: () -> Unit, onClear: () -> Unit,
) {
    val color = MaterialTheme.colorScheme.onSurface
    val actions = LocalPhoneQueuePlayerActions.current
    val matching = current?.occurrenceId == actions.occurrenceId && current != null
    val menuAnchor = moe.rukamori.archivetune.shared.ui.menu.LocalOverflowMenuAnchor.current
    var menuBounds by remember { mutableStateOf<Rect?>(null) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("queue-phone-header")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = .06f))) {
                if (current != null) artwork(current, Modifier.fillMaxSize())
            }
            Column(Modifier.weight(1f)) {
                Text(current?.item?.title.orEmpty(), style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(current?.item?.subtitle.orEmpty(), style = MaterialTheme.typography.bodySmall,
                    color = color.copy(alpha = .6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            PlayerIcon(PlayerSymbol.HEART, stringResource(Res.string.m5_like), { actions.onLike?.invoke() },
                enabled = matching && actions.onLike != null, selected = matching && actions.liked,
                contentColor = if (matching && actions.liked) MaterialTheme.colorScheme.primary else color,
                size = 40.dp, modifier = Modifier.testTag("queue-current-like"))
            IconButton({ menuAnchor?.bounds = menuBounds; actions.onMore?.invoke() }, enabled = matching && actions.onMore != null,
                modifier = Modifier.size(40.dp).onGloballyPositioned { menuBounds = it.boundsInWindow() }.testTag("queue-current-more")) {
                Icon(painterResource(Res.drawable.reference_queue_more_vert), stringResource(Res.string.m5_more), Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.height(2.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                source?.let { Text(stringResource(Res.string.queue_source_title, it), maxLines = 1,
                    overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium) }
                Text(summary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall, color = color.copy(alpha = .6f))
            }
            PhoneQueueAction(if (locked) Res.drawable.phone_reference_lock else Res.drawable.reference_queue_lock_open,
                stringResource(if (locked) Res.string.queue_unlock_edits else Res.string.queue_lock_edits),
                ready, onLock, "queue-lock", color.copy(alpha = .7f))
            PhoneQueueAction(Res.drawable.phone_reference_playlist_add, stringResource(Res.string.queue_save_playlist),
                hasTracks, onSave, "queue-save", color)
            PhoneQueueAction(Res.drawable.phone_reference_location_on, stringResource(Res.string.queue_jump_current),
                hasTracks, onJump, "queue-jump", color)
            PhoneQueueAction(Res.drawable.phone_reference_delete, stringResource(Res.string.queue_clear_upcoming),
                ready && hasTracks, onClear, "queue-clear", MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(2.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
            val idle = ToggleButtonDefaults.toggleButtonColors(containerColor = color.copy(alpha = .12f), contentColor = color,
                checkedContainerColor = color.copy(alpha = .22f), checkedContentColor = color)
            ToggleButton(shuffle, { onShuffle() }, enabled = hasTracks,
                modifier = Modifier.weight(1f).height(40.dp).testTag("queue-shuffle"),
                shapes = ButtonGroupDefaults.connectedLeadingButtonShapes(), colors = idle) {
                Icon(painterResource(Res.drawable.phone_reference_shuffle), stringResource(Res.string.m5_shuffle), Modifier.size(22.dp))
            }
            ToggleButton(repeat != QueueRepeatMode.OFF, { onRepeat() }, enabled = ready,
                modifier = Modifier.weight(1f).height(40.dp).testTag("queue-repeat"),
                shapes = ButtonGroupDefaults.connectedMiddleButtonShapes(), colors = idle) {
                Icon(painterResource(if (repeat == QueueRepeatMode.ONE) Res.drawable.reference_queue_repeat_one
                    else Res.drawable.phone_reference_repeat_on), stringResource(if (repeat == QueueRepeatMode.ONE)
                    Res.string.m5_repeat_one else Res.string.m5_repeat), Modifier.size(22.dp))
            }
            ToggleButton(infinite, { onInfinite(!infinite) }, enabled = ready,
                modifier = Modifier.weight(1f).height(40.dp).testTag("queue-infinite"),
                shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(),
                colors = ToggleButtonDefaults.toggleButtonColors(containerColor = color.copy(alpha = .12f), contentColor = color.copy(alpha = .5f),
                    checkedContainerColor = MaterialTheme.colorScheme.primary, checkedContentColor = MaterialTheme.colorScheme.onPrimary)) {
                if (loading) CircularProgressIndicator(Modifier.size(18.dp), color = LocalContentColor.current, strokeWidth = 2.dp)
                else Text(stringResource(Res.string.queue_infinite_symbol), style = MaterialTheme.typography.titleLarge)
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(Res.string.queue_continue_playing), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Text(stringResource(if (infinite) Res.string.queue_autoplaying_similar else Res.string.queue_autoplay_off),
                Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = color.copy(alpha = .5f),
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        HorizontalDivider(color = color.copy(alpha = .08f))
    }
}

@Composable
private fun PhoneQueueAction(icon: DrawableResource, label: String, enabled: Boolean,
    onClick: () -> Unit, tag: String, color: Color) {
    IconButton(onClick, enabled = enabled, modifier = Modifier.testTag(tag)) {
        Icon(painterResource(icon), label, Modifier.size(20.dp), tint = color.copy(alpha = if (enabled) color.alpha else .38f))
    }
}
