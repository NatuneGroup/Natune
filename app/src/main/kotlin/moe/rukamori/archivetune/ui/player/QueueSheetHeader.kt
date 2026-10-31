@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package moe.rukamori.archivetune.ui.player

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import coil3.compose.AsyncImage
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.ui.component.BottomSheetState
import moe.rukamori.archivetune.ui.component.bottomSheetDraggable
import moe.rukamori.archivetune.ui.utils.LocalItemPreferences
import moe.rukamori.archivetune.utils.makeTimeString

/** Height of the drag strip above the queue list, not counting the status-bar inset it also pads. */
internal val QueueDragStripHeight: Dp = 12.dp

/** Height of the one-row header that replaces the now-playing card once the list has scrolled. */
internal val QueueCompactHeaderHeight: Dp = 56.dp

/**
 * Drag strip above the queue list. It never scrolls, so the sheet stays draggable whatever the list is doing; the list
 * owns the rest of the gesture.
 */
@Composable
internal fun QueueSheetDragStrip(
    sheetState: BottomSheetState,
    backgroundColor: Color,
    onBackgroundColor: Color,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(backgroundColor)
                .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .bottomSheetDraggable(sheetState)
                .padding(top = 4.dp, bottom = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier =
                Modifier
                    .width(48.dp)
                    .height(5.dp)
                    .clip(RoundedCornerShape(2.5.dp))
                    .background(onBackgroundColor.copy(alpha = 0.4f)),
        )
    }
}

/**
 * Now-playing card that opens as the first item of the queue list. It scrolls away with the rows, and
 * [QueueCompactHeader] takes its place once the list has moved, which hands the rows the card's height.
 */
@Composable
internal fun QueueSongHeader(
    mediaMetadata: MediaMetadata?,
    liked: Boolean,
    repeatMode: Int,
    shuffleModeEnabled: Boolean,
    locked: Boolean,
    songCount: Int,
    queueDuration: Int,
    queueTitle: String?,
    queueRemainingMs: Long?,
    infiniteQueueEnabled: Boolean,
    infiniteQueueLoading: Boolean,
    onBackgroundColor: Color,
    onToggleLike: () -> Unit,
    onMenuClick: () -> Unit,
    onClearQueueClick: () -> Unit,
    onRepeatClick: () -> Unit,
    onShuffleClick: () -> Unit,
    onLockClick: () -> Unit,
    onInfiniteQueueClick: () -> Unit,
    onSaveQueueClick: () -> Unit,
    onJumpToCurrentClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val enableHapticFeedback = LocalItemPreferences.current.hapticFeedbackEnabled
    val infiniteQueueLabel = stringResource(R.string.queue_infinite)
    val detailsText =
        pluralStringResource(R.plurals.n_song, songCount, songCount) +
            "  •  " +
            makeTimeString(queueDuration * 1000L) +
            "  •  " +
            stringResource(
                R.string.queue_time_remaining,
                queueRemainingMs?.let(::makeTimeString) ?: stringResource(R.string.unknown),
            )

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AsyncImage(
                model = mediaMetadata?.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(onBackgroundColor.copy(alpha = 0.06f)),
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = mediaMetadata?.title ?: "",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = onBackgroundColor,
                )
                Text(
                    text = mediaMetadata?.artists?.joinToString(", ") { it.name } ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = onBackgroundColor.copy(alpha = 0.6f),
                )
            }

            IconButton(
                onClick = onToggleLike,
                modifier = Modifier.size(40.dp),
                colors =
                    IconButtonDefaults.iconButtonColors(
                        contentColor =
                            if (liked) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                onBackgroundColor
                            },
                    ),
            ) {
                Icon(
                    painter = painterResource(if (liked) R.drawable.favorite else R.drawable.favorite_border),
                    contentDescription = stringResource(R.string.action_like),
                    modifier = Modifier.size(24.dp).playerControlMotion(PlayerControlKind.Like, liked),
                )
            }

            IconButton(
                onClick = onMenuClick,
                modifier = Modifier.size(40.dp),
                colors = IconButtonDefaults.iconButtonColors(contentColor = onBackgroundColor.copy(alpha = 0.7f)),
            ) {
                Icon(
                    painter = painterResource(R.drawable.more_vert),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        Spacer(modifier = Modifier.height(2.dp))

        QueueHeaderDetails(
            title = queueTitle,
            detailsText = detailsText,
            color = onBackgroundColor,
            locked = locked,
            onLock = onLockClick,
            onSave = onSaveQueueClick,
            onJump = onJumpToCurrentClick,
            onClear = onClearQueueClick,
        )

        Spacer(modifier = Modifier.height(2.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val uncheckedColors =
                ToggleButtonDefaults.toggleButtonColors(
                    containerColor = onBackgroundColor.copy(alpha = 0.12f),
                    contentColor = onBackgroundColor,
                )
            val checkedColors =
                ToggleButtonDefaults.toggleButtonColors(
                    checkedContainerColor = onBackgroundColor.copy(alpha = 0.22f),
                    checkedContentColor = onBackgroundColor,
                )
            val infiniteCheckedColors =
                ToggleButtonDefaults.toggleButtonColors(
                    checkedContainerColor = MaterialTheme.colorScheme.primary,
                    checkedContentColor = MaterialTheme.colorScheme.onPrimary,
                    containerColor = onBackgroundColor.copy(alpha = 0.12f),
                    contentColor = onBackgroundColor.copy(alpha = 0.5f),
                )

            ToggleButton(
                checked = shuffleModeEnabled,
                onCheckedChange = {
                    view.queueHapticClick(enableHapticFeedback)
                    onShuffleClick()
                },
                modifier = Modifier.weight(1f).height(40.dp),
                shapes = ButtonGroupDefaults.connectedLeadingButtonShapes(),
                colors = if (shuffleModeEnabled) checkedColors else uncheckedColors,
            ) {
                Icon(
                    painter = painterResource(R.drawable.shuffle),
                    contentDescription = stringResource(R.string.action_shuffle_on),
                    modifier = Modifier.size(22.dp).playerControlMotion(PlayerControlKind.Shuffle, shuffleModeEnabled),
                )
            }

            ToggleButton(
                checked = repeatMode != Player.REPEAT_MODE_OFF,
                onCheckedChange = {
                    view.queueHapticClick(enableHapticFeedback)
                    onRepeatClick()
                },
                modifier = Modifier.weight(1f).height(40.dp),
                shapes = ButtonGroupDefaults.connectedMiddleButtonShapes(),
                colors = if (repeatMode != Player.REPEAT_MODE_OFF) checkedColors else uncheckedColors,
            ) {
                Icon(
                    painter =
                        painterResource(
                            when (repeatMode) {
                                Player.REPEAT_MODE_ONE -> R.drawable.repeat_one_on
                                Player.REPEAT_MODE_ALL -> R.drawable.repeat_on
                                else -> R.drawable.repeat
                            },
                        ),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp).playerControlMotion(PlayerControlKind.Repeat, repeatMode),
                )
            }

            ToggleButton(
                checked = infiniteQueueEnabled,
                onCheckedChange = { onInfiniteQueueClick() },
                modifier = Modifier.weight(1f).height(40.dp).semantics { contentDescription = infiniteQueueLabel },
                shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(),
                colors = infiniteCheckedColors,
            ) {
                AnimatedContent(
                    targetState = infiniteQueueLoading,
                    label = "InfiniteQueueLoading",
                ) { loading ->
                    if (loading) {
                        CircularWavyProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = LocalContentColor.current,
                        )
                    } else {
                        Icon(
                            painter = painterResource(R.drawable.all_inclusive),
                            contentDescription = stringResource(R.string.queue_infinite),
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }

        QueueAutoplayFooter(infiniteQueueEnabled, onBackgroundColor)
    }
}

/**
 * One-row header that replaces the now-playing card once the list scrolls. It keeps the playback toggles on the row
 * and moves the queue-level actions into an overflow menu, so nothing is lost while the card is out of view.
 */
@Composable
internal fun QueueCompactHeader(
    mediaMetadata: MediaMetadata?,
    locked: Boolean,
    shuffleModeEnabled: Boolean,
    repeatMode: Int,
    infiniteQueueEnabled: Boolean,
    backgroundColor: Color,
    onBackgroundColor: Color,
    onShuffleClick: () -> Unit,
    onRepeatClick: () -> Unit,
    onLockClick: () -> Unit,
    onInfiniteQueueClick: () -> Unit,
    onSaveQueueClick: () -> Unit,
    onJumpToCurrentClick: () -> Unit,
    onClearQueueClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val enableHapticFeedback = LocalItemPreferences.current.hapticFeedbackEnabled
    var overflowOpen by remember { mutableStateOf(false) }

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(QueueCompactHeaderHeight)
                .background(backgroundColor)
                .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AsyncImage(
            model = mediaMetadata?.thumbnailUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier =
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(onBackgroundColor.copy(alpha = 0.06f)),
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = mediaMetadata?.title ?: "",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = onBackgroundColor,
            )
            Text(
                text = mediaMetadata?.artists?.joinToString(", ") { it.name } ?: "",
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = onBackgroundColor.copy(alpha = 0.6f),
            )
        }

        IconButton(
            onClick = {
                view.queueHapticClick(enableHapticFeedback)
                onShuffleClick()
            },
            modifier = Modifier.size(40.dp),
            colors =
                IconButtonDefaults.iconButtonColors(
                    contentColor = if (shuffleModeEnabled) MaterialTheme.colorScheme.primary else onBackgroundColor.copy(alpha = 0.7f),
                ),
        ) {
            Icon(
                painter = painterResource(R.drawable.shuffle),
                contentDescription = stringResource(R.string.action_shuffle_on),
                modifier = Modifier.size(22.dp).playerControlMotion(PlayerControlKind.Shuffle, shuffleModeEnabled),
            )
        }

        IconButton(
            onClick = {
                view.queueHapticClick(enableHapticFeedback)
                onRepeatClick()
            },
            modifier = Modifier.size(40.dp),
            colors =
                IconButtonDefaults.iconButtonColors(
                    contentColor =
                        if (repeatMode != Player.REPEAT_MODE_OFF) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            onBackgroundColor.copy(alpha = 0.7f)
                        },
                ),
        ) {
            Icon(
                painter =
                    painterResource(
                        when (repeatMode) {
                            Player.REPEAT_MODE_ONE -> R.drawable.repeat_one_on
                            Player.REPEAT_MODE_ALL -> R.drawable.repeat_on
                            else -> R.drawable.repeat
                        },
                    ),
                contentDescription = null,
                modifier = Modifier.size(22.dp).playerControlMotion(PlayerControlKind.Repeat, repeatMode),
            )
        }

        Box {
            IconButton(
                onClick = { overflowOpen = true },
                modifier = Modifier.size(40.dp),
                colors = IconButtonDefaults.iconButtonColors(contentColor = onBackgroundColor.copy(alpha = 0.7f)),
            ) {
                Icon(
                    painter = painterResource(R.drawable.more_vert),
                    contentDescription = stringResource(R.string.queue_actions),
                    modifier = Modifier.size(22.dp),
                )
            }

            DropdownMenu(
                expanded = overflowOpen,
                onDismissRequest = { overflowOpen = false },
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(if (locked) R.string.queue_unlock_edits else R.string.queue_lock_edits)) },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(if (locked) R.drawable.lock_open else R.drawable.lock),
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        overflowOpen = false
                        onLockClick()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.queue_save_playlist)) },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(R.drawable.playlist_add),
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        overflowOpen = false
                        onSaveQueueClick()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.queue_jump_current)) },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(R.drawable.location_on),
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        overflowOpen = false
                        onJumpToCurrentClick()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.queue_infinite)) },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(R.drawable.all_inclusive),
                            contentDescription = null,
                        )
                    },
                    trailingIcon = {
                        Checkbox(checked = infiniteQueueEnabled, onCheckedChange = null)
                    },
                    onClick = {
                        overflowOpen = false
                        onInfiniteQueueClick()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.clear)) },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(R.drawable.delete),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    },
                    onClick = {
                        overflowOpen = false
                        onClearQueueClick()
                    },
                )
            }
        }
    }
}

private fun View.queueHapticClick(enabled: Boolean) {
    if (enabled) {
        performHapticFeedback(
            HapticFeedbackConstants.CONTEXT_CLICK,
            HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING,
        )
    }
}
