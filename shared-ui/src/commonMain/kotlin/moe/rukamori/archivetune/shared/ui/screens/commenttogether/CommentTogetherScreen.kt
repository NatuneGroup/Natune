package moe.rukamori.archivetune.shared.ui.screens.commenttogether

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.data.listentogether.RepliedMessage
import moe.rukamori.archivetune.shared.ui.glass.LocalOpticalPolicy
import moe.rukamori.archivetune.shared.ui.glass.layerBackdrop
import moe.rukamori.archivetune.shared.ui.glass.rememberBackdrop
import moe.rukamori.archivetune.shared.ui.navigation.LocalShellContentPadding
import moe.rukamori.archivetune.shared.ui.resources.Res
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

private const val CommentOlderDividerKey = "comment-older-messages-divider"
private const val CommentJumpHighlightMillis = 1400L
private const val CommentInitialSnapSettleMillis = 150L

/*
 * Ported from the baseline CommentTogether route (app/.../ui/screens/CommentTogether.kt). The screen keeps only
 * presentation state: scroll position, the anchored action popup, the emoji and song sheets and the jump
 * highlight. Room, chat, search, clipboard and playback effects leave through CommentTogetherActions.
 */

/** A jump request. Each new instance restarts the highlight timer, so repeating a jump to the same message flashes again. */
private class CommentJumpTarget(val messageId: String)

/** The older-messages divider follows the last restored message, so the divider row sits right after that index. */
private fun List<CommentMessageUi>.restoredDividerIndex(): Int = indexOfLast { it.restored }

/** LazyColumn index of the message at [messageIndex], shifted by one row when the divider row precedes it. */
private fun List<CommentMessageUi>.listIndexOf(messageIndex: Int): Int {
    val divider = restoredDividerIndex()
    return if (divider >= 0 && messageIndex > divider) messageIndex + 1 else messageIndex
}

/** Total LazyColumn rows: one per message plus the older-messages divider when history was restored. */
private fun List<CommentMessageUi>.listRowCount(): Int = size + (if (restoredDividerIndex() >= 0) 1 else 0)

/**
 * The Listen Together comment-together chat. The host maps its room and chat state into [state] and performs
 * every effect through [actions]; the screen never fakes a success. [avatar] and [thumbnail] are host-owned art
 * slots. [contentPadding] keeps the chat clear of the shell chrome.
 */
@Composable
fun CommentTogetherScreen(
    state: CommentTogetherUi,
    actions: CommentTogetherActions,
    avatar: CommentAvatar,
    thumbnail: CommentThumbnail,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = LocalShellContentPadding.current,
) {
    key(state.sessionKey) {
        CommentTogetherContent(state, actions, avatar, thumbnail, modifier, contentPadding)
    }
}

@Composable
private fun CommentTogetherContent(
    state: CommentTogetherUi,
    actions: CommentTogetherActions,
    avatar: CommentAvatar,
    thumbnail: CommentThumbnail,
    modifier: Modifier,
    contentPadding: PaddingValues,
) {
    val currentState by rememberUpdatedState(state)
    val currentActions by rememberUpdatedState(actions)
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Local glass source for the anchored popup. It records the chat content only while the popup is open and the
    // popup is a sibling of that content, so the sample is a one-way read that cannot recurse into the layer.
    val glassPolicy = LocalOpticalPolicy.current
    val glassEnabled = glassPolicy.recordLiquidGlass
    val glassSource = rememberBackdrop(MaterialTheme.colorScheme.background)

    var actionTarget by remember { mutableStateOf<CommentActionTarget?>(null) }
    var showEmojiPicker by remember { mutableStateOf(false) }
    var showSongPicker by remember { mutableStateOf(false) }
    var jumpTarget by remember { mutableStateOf<CommentJumpTarget?>(null) }
    var atBottomOnFocus by remember { mutableStateOf(true) }

    // Auto-follow the newest messages only while the reader is at or near the bottom, so a pinned-history read is
    // never yanked down.
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = info.totalItemsCount
            total == 0 || last >= total - 2
        }
    }

    // While the chat is on screen the host suppresses chat notifications; leaving the screen restores them.
    DisposableEffect(Unit) {
        currentActions.screenVisible(true)
        onDispose { currentActions.screenVisible(false) }
    }

    LaunchedEffect(state.messages.size) {
        currentActions.markRead()
        val messages = currentState.messages
        if (messages.isNotEmpty() && atBottom) {
            listState.animateScrollToItem(messages.listRowCount() - 1)
        }
    }

    // The chat opens on the most recent message: wait for the first real layout, give late-arriving restored history
    // one settle beat, then snap to the last row.
    LaunchedEffect(Unit) {
        snapshotFlow { listState.layoutInfo.totalItemsCount }.filter { it > 0 }.first()
        delay(CommentInitialSnapSettleMillis)
        val total = listState.layoutInfo.totalItemsCount
        if (total > 0) listState.scrollToItem(total - 1)
    }

    // The keyboard shrinks the list viewport without moving the scroll offset, which pushes the newest message out of
    // view. The near-bottom state is captured when the composer takes focus, the only moment it can be read before the
    // resize, and a reader who was at the bottom is re-pinned to the newest message as the viewport resizes.
    var composerFocused by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        snapshotFlow { listState.layoutInfo.viewportSize.height }
            .distinctUntilChanged()
            .drop(1)
            .collect {
                val total = listState.layoutInfo.totalItemsCount
                if (composerFocused && atBottomOnFocus && total > 0) {
                    listState.animateScrollToItem(total - 1)
                }
            }
    }

    LaunchedEffect(jumpTarget) {
        if (jumpTarget == null) return@LaunchedEffect
        delay(CommentJumpHighlightMillis)
        jumpTarget = null
    }

    // The anchored popup must not outlive its message: a deleted or vanished target closes it.
    LaunchedEffect(state.messages) {
        val open = actionTarget ?: return@LaunchedEffect
        val live = currentState.messages.firstOrNull { it.id == open.message.id }
        if (live == null || live.deleted) actionTarget = null
    }

    val notice = state.notice
    val noticeText = when (notice) {
        null -> null
        CommentNotice.MessageCopied -> stringResource(Res.string.m4c_message_copied)
        CommentNotice.SongPlayed -> stringResource(Res.string.m4c_play_song)
        CommentNotice.NothingPlaying -> stringResource(Res.string.m4c_nothing_playing)
    }
    // The snackbar runs on the composition scope so clearing the notice does not cancel it mid-display.
    LaunchedEffect(notice) {
        if (notice == null) return@LaunchedEffect
        currentActions.noticeShown()
        if (noticeText != null) scope.launch { snackbarHostState.showSnackbar(noticeText) }
    }

    fun jumpTo(messageId: String) {
        val messages = currentState.messages
        val index = messages.indexOfFirst { it.id == messageId }
        if (index < 0) return
        jumpTarget = CommentJumpTarget(messageId)
        // Instant rather than animated, so a long history snaps straight to the target row.
        scope.launch { listState.scrollToItem(messages.listIndexOf(index)) }
    }

    fun send() {
        val composer = currentState.composer
        if (composer.draft.isBlank()) return
        val text = composer.draft.trim()
        val editing = composer.editing
        currentActions.send(
            if (editing != null) {
                CommentSendUi(text = text, replyTo = null, editMessageId = editing.messageId)
            } else {
                CommentSendUi(text = text, replyTo = composer.replyTo, editMessageId = null)
            },
        )
        focusManager.clearFocus()
    }

    val recordingGlass = glassEnabled && actionTarget != null
    val pinned = remember(state.messages) { state.messages.filter { it.pinned } }
    val restoredDivider = state.messages.restoredDividerIndex()

    Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (recordingGlass) Modifier.layerBackdrop(glassSource) else Modifier),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding()
                    .padding(contentPadding),
            ) {
                CommentHeader(roomCode = state.roomCode, onBack = currentActions.back)

                if (pinned.isNotEmpty()) {
                    CommentPinnedStack(
                        pinned = pinned,
                        onJump = { messageId -> jumpTo(messageId) },
                        onUnpin = { messageId -> currentActions.setPinned(messageId, false) },
                    )
                }

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    if (state.messages.isEmpty() && state.typing.isEmpty()) {
                        CommentEmptyPlaceholder(modifier = Modifier.fillMaxSize())
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            state.messages.forEachIndexed { index, message ->
                                item(key = message.id) {
                                    CommentMessageRow(
                                        message = message,
                                        canReply = !message.deleted,
                                        interactive = true,
                                        highlighted = jumpTarget?.messageId == message.id,
                                        reactionsEnabled = true,
                                        avatar = avatar,
                                        thumbnail = thumbnail,
                                        onReply = { currentActions.reply(message.id) },
                                        onLongPress = { bounds -> actionTarget = CommentActionTarget(message, bounds) },
                                        onReact = { emoji -> currentActions.react(message.id, emoji) },
                                        onPlay = { track -> currentActions.playShared(track) },
                                        onLink = { url -> currentActions.openLink(url) },
                                    )
                                }
                                if (index == restoredDivider) {
                                    item(key = CommentOlderDividerKey) {
                                        CommentOlderMessagesDivider()
                                    }
                                }
                            }
                        }
                    }
                }

                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    AnimatedVisibility(
                        visible = state.composer.replyTo != null || state.composer.editing != null,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut(),
                    ) {
                        CommentComposeStrip(
                            editing = state.composer.editing,
                            replyTo = state.composer.replyTo,
                            onCancel = currentActions.cancelCompose,
                        )
                    }

                    AnimatedVisibility(
                        visible = state.typing.isNotEmpty(),
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut(),
                    ) {
                        CommentTypingRow(
                            typing = state.typing,
                            avatar = avatar,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }

                    if (state.supported) {
                        CommentComposer(
                            draft = state.composer.draft,
                            onDraftChanged = { text -> currentActions.draftChanged(text) },
                            onTyping = { currentActions.notifyTyping() },
                            onSend = { send() },
                            onPickSong = { showSongPicker = true },
                            onComposerFocus = { focused ->
                                composerFocused = focused
                                if (focused) atBottomOnFocus = atBottom
                            },
                        )
                    } else {
                        CommentUnsupportedNotice()
                    }
                }
            }
        }

        // The popup is a sibling of the recorded content, so it can sample the glass without recursing into it.
        actionTarget?.let { target ->
            val message = state.messages.firstOrNull { it.id == target.message.id } ?: target.message
            CommentActionsPopup(
                target = CommentActionTarget(message = message, bounds = target.bounds),
                isHost = state.localHost,
                backdrop = if (glassEnabled) glassSource else null,
                onReact = { emoji -> currentActions.react(message.id, emoji) },
                onOpenEmojiPicker = { showEmojiPicker = true },
                onReply = { currentActions.reply(message.id) },
                onCopy = { currentActions.copyText(message.text) },
                onEdit = { currentActions.edit(message.id) },
                onPinToggle = { currentActions.setPinned(message.id, !message.pinned) },
                onDeleteForMe = { currentActions.delete(message.id, false) },
                onDeleteForEveryone = { currentActions.delete(message.id, true) },
                onDismiss = { actionTarget = null },
            )
        }

        if (showEmojiPicker) {
            CommentEmojiPickerSheet(
                onPick = { emoji ->
                    val targetId = actionTarget?.message?.id
                    showEmojiPicker = false
                    actionTarget = null
                    if (targetId != null) currentActions.react(targetId, emoji)
                },
                onDismiss = { showEmojiPicker = false },
            )
        }

        if (showSongPicker) {
            CommentSongPickerSheet(
                currentTrack = state.currentTrack,
                searchSongs = currentActions.searchSongs,
                thumbnail = thumbnail,
                onShareTrack = { track ->
                    showSongPicker = false
                    currentActions.shareTrack(track)
                },
                onShareCurrent = {
                    showSongPicker = false
                    currentActions.shareCurrent()
                },
                onDismiss = { showSongPicker = false },
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .imePadding()
                .padding(contentPadding),
        )
    }
}

/** Back button, title and the room code, the baseline top bar. */
@Composable
private fun CommentHeader(roomCode: String?, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                painter = painterResource(Res.drawable.m4c_icon_arrow_back),
                contentDescription = stringResource(Res.string.m4c_back),
            )
        }
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                text = stringResource(Res.string.m4c_comments),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (roomCode != null) {
                Text(
                    text = stringResource(Res.string.m4c_room_code, roomCode),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Reply or edit preview above the composer. An edit takes precedence, as on Android. */
@Composable
private fun CommentComposeStrip(
    editing: CommentEditTargetUi?,
    replyTo: RepliedMessage?,
    onCancel: () -> Unit,
) {
    val accent = if (editing != null) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
    Surface(
        modifier = Modifier
            .padding(bottom = 8.dp)
            .fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier
                .padding(8.dp)
                .height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(accent),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            ) {
                Text(
                    text = if (editing != null) {
                        stringResource(Res.string.m4c_edit_message)
                    } else {
                        replyTo?.username.orEmpty()
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = accent,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = editing?.originalText ?: replyTo?.message.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onCancel) {
                Icon(
                    painter = painterResource(Res.drawable.m4c_icon_close),
                    contentDescription = stringResource(Res.string.m4c_close),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/** The composer row: song share, the draft field and send. The draft is host-owned and mirrored here. */
@Composable
private fun CommentComposer(
    draft: String,
    onDraftChanged: (String) -> Unit,
    onTyping: () -> Unit,
    onSend: () -> Unit,
    onPickSong: () -> Unit,
    onComposerFocus: (Boolean) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            IconButton(onClick = onPickSong) {
                Icon(
                    painter = painterResource(Res.drawable.m4c_icon_music_note),
                    contentDescription = stringResource(Res.string.m4c_pick_song),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
            }
            OutlinedTextField(
                value = draft,
                onValueChange = { text ->
                    onDraftChanged(text)
                    if (text.isNotBlank()) onTyping()
                },
                placeholder = { Text(stringResource(Res.string.m4c_type_message)) },
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
                    .onFocusChanged { focus -> onComposerFocus(focus.isFocused) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                ),
                maxLines = 4,
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Send,
                    keyboardType = KeyboardType.Text,
                ),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
            )
            FloatingActionButton(
                onClick = onSend,
                shape = CircleShape,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(48.dp),
                elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp),
            ) {
                Icon(
                    painter = painterResource(Res.drawable.m4c_icon_send_chat),
                    contentDescription = stringResource(Res.string.m4c_send),
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

/** Replaces the composer when the server carries no chat (protobuf-only). */
@Composable
private fun CommentUnsupportedNotice() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Icon(
                painter = painterResource(Res.drawable.m4c_icon_chat_msg),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(Res.string.m4c_unsupported_server),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Shown while the room has no messages and nobody is typing. */
@Composable
private fun CommentEmptyPlaceholder(modifier: Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.primaryContainer,
                            MaterialTheme.colorScheme.secondaryContainer,
                        ),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(Res.drawable.m4c_icon_chat_msg),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(56.dp),
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = stringResource(Res.string.m4c_no_messages_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(Res.string.m4c_no_messages_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
