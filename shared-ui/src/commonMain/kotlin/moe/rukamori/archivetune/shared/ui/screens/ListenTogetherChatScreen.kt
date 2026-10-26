package moe.rukamori.archivetune.shared.ui.screens

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.repository.*
import moe.rukamori.archivetune.shared.ui.glass.BusyLinearProgress
import moe.rukamori.archivetune.shared.ui.navigation.LocalShellContentPadding
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs

data class ChatPermissionsUi(
    val reply: Boolean, val edit: Boolean, val deleteSelf: Boolean, val deleteEveryone: Boolean,
    val pin: Boolean, val react: Boolean,
)
data class ChatReactionUi(val emoji: String, val count: Int, val mine: Boolean)
data class ChatReplyUi(val messageId: String?, val author: String, val text: String)
data class ChatMessageUi(
    val id: String, val authorId: String, val authorName: String, val time: String,
    val text: String, val own: Boolean, val restored: Boolean, val deleted: Boolean,
    val edited: Boolean, val pinned: Boolean, val reply: ChatReplyUi?, val track: ContentItem?,
    val reactions: List<ChatReactionUi>, val permissions: ChatPermissionsUi,
)
data class ChatComposerUi(
    val text: String, val replyTo: ChatReplyUi?, val editMessageId: String?, val busy: Boolean, val failed: Boolean,
) {
    init { require(replyTo == null || editMessageId == null) }
}
data class ListenChatUi(
    /** The live room/account generation, NOT only its publicly reusable room code. */
    val sessionKey: String, val messages: List<ChatMessageUi>, val composer: ChatComposerUi,
    val connected: Boolean, val inRoom: Boolean, val supported: Boolean, val typingNames: List<String>,
    val hasOlder: Boolean, val loadingOlder: Boolean, val olderFailed: Boolean,
)
data class ChatSendUi(val text: String, val replyTo: ChatReplyUi?, val editMessageId: String?)
data class ListenChatActions(
    val back: () -> Unit, val visible: (Boolean) -> Unit, val markRead: (String?) -> Unit,
    val text: (String) -> Unit, val send: (ChatSendUi) -> Unit, val cancelCompose: () -> Unit,
    val reply: (String) -> Unit, val edit: (String) -> Unit, val delete: (String, Boolean) -> Unit,
    val pin: (String, Boolean) -> Unit, val react: (String, String) -> Unit, val copyText: (String) -> Unit,
    val playShared: (ContentItem) -> Unit, val pickSong: () -> Unit, val loadOlder: () -> Unit,
    val openLink: (String) -> Unit,
)

@Composable
fun ListenTogetherChatScreen(
    state: ListenChatUi,
    actions: ListenChatActions,
    artwork: ContentArtwork,
    avatar: @Composable (ChatMessageUi, Modifier) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = LocalShellContentPadding.current,
) = key(state.sessionKey) {
    ListenChatContent(state, actions, artwork, avatar, modifier, contentPadding)
}

@Composable
private fun ListenChatContent(
    state: ListenChatUi, actions: ListenChatActions, artwork: ContentArtwork,
    avatar: @Composable (ChatMessageUi, Modifier) -> Unit, modifier: Modifier, contentPadding: PaddingValues,
) {
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val currentState by rememberUpdatedState(state)
    val currentActions by rememberUpdatedState(actions)
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var reactionId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val atBottom by remember { derivedStateOf {
        list.layoutInfo.totalItemsCount == 0 ||
            (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= list.layoutInfo.totalItemsCount - 2
    } }
    DisposableEffect(state.sessionKey) {
        actions.visible(true)
        onDispose { currentActions.visible(false) }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { currentState.messages.size }.first { it > 0 }
        withFrameNanos { }
        list.scrollToItem(currentState.messages.size)
    }
    LaunchedEffect(state.messages.lastOrNull()?.id) {
        if (atBottom && state.messages.isNotEmpty()) list.scrollToItem(state.messages.size)
    }
    LaunchedEffect(state.messages.lastOrNull()?.id, state.connected) {
        if (state.connected && state.inRoom) actions.markRead(state.messages.lastOrNull()?.id)
    }
    fun jump(id: String) {
        val index = state.messages.indexOfFirst { it.id == id }
        if (index >= 0) scope.launch { list.scrollToItem(index + 1) }
    }
    val canSend = state.supported && state.connected && state.inRoom && !state.composer.busy
    fun send() {
        if (canSend && state.composer.text.isNotBlank()) actions.send(ChatSendUi(
            state.composer.text.trim(), state.composer.replyTo, state.composer.editMessageId))
    }
    Column(modifier.fillMaxSize().imePadding().padding(contentPadding)) {
        ManagementHeader(stringResource(Res.string.m4_chat_title), actions.back)
        val pinned = state.messages.filter { it.pinned && !it.deleted }
        if (pinned.isNotEmpty()) LazyRow {
            items(pinned, key = { it.id }) { message ->
                AssistChip({ jump(message.id) }, label = { Text(message.text, maxLines = 1) })
            }
        }
        LazyColumn(Modifier.weight(1f), state = list) {
            // Keep a stable one-item prefix so restoration, pin jumps and latest-message indices agree.
            item("older") {
                if (state.loadingOlder) BusyLinearProgress(Modifier.fillMaxWidth())
                if (state.hasOlder || state.olderFailed) TextButton(actions.loadOlder, enabled = !state.loadingOlder) {
                    Text(stringResource(if (state.olderFailed) Res.string.m4_retry else Res.string.m4_chat_older))
                }
            }
            itemsIndexed(state.messages, key = { _, message -> message.id }) { index, message ->
                if (message.restored && (index == 0 || !state.messages[index - 1].restored)) {
                    Text(stringResource(Res.string.m4_chat_restored), Modifier.padding(16.dp), style = MaterialTheme.typography.labelSmall)
                }
                ChatMessageRow(message, artwork, avatar, currentActions.openLink,
                    openActions = { selectedId = message.id },
                    reply = if (canSend && message.permissions.reply && !message.deleted) ({ currentActions.reply(message.id) }) else null,
                    react = if (canSend && message.permissions.react && !message.deleted) ({ currentActions.react(message.id, it) }) else null,
                    play = if (state.connected && state.inRoom) ({ currentActions.playShared(it) }) else null,
                    jumpReply = { message.reply?.messageId?.let(::jump) })
            }
        }
        if (!atBottom) TextButton({ scope.launch { list.scrollToItem(state.messages.size) } }) {
            Text(stringResource(Res.string.m4_chat_jump_latest))
        }
        if (state.typingNames.isNotEmpty() && state.supported) Text(stringResource(Res.string.m4_chat_typing,
            state.typingNames.joinToString(", ")), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
        if (!state.supported) Text(stringResource(Res.string.m4_chat_unsupported), Modifier.padding(16.dp))
        else {
            if (!state.connected || !state.inRoom) Text(stringResource(Res.string.m4_chat_unavailable), Modifier.padding(16.dp))
            if (state.composer.editMessageId != null || state.composer.replyTo != null) {
                Row(Modifier.padding(horizontal = 16.dp)) {
                    Text(if (state.composer.editMessageId != null) stringResource(Res.string.m4_chat_edit)
                        else state.composer.replyTo?.let { "${it.author}: ${it.text}" }.orEmpty(),
                        modifier = Modifier.weight(1f), maxLines = 2)
                    TextButton(actions.cancelCompose, enabled = !state.composer.busy) { Text(stringResource(Res.string.m4_cancel)) }
                }
            }
            if (state.composer.failed) Text(stringResource(Res.string.m4_load_failed), Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.error)
            OutlinedTextField(state.composer.text, actions.text, enabled = canSend,
                label = { Text(stringResource(Res.string.m4_chat_message)) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send() }))
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(actions.pickSong, enabled = canSend) { Text(stringResource(Res.string.m4_chat_pick_song)) }
                Button({ send() }, enabled = canSend && state.composer.text.isNotBlank()) {
                    Text(stringResource(if (state.composer.busy) Res.string.m4_loading else Res.string.m4_chat_send))
                }
            }
        }
    }
    val selected = state.messages.firstOrNull { it.id == selectedId }
    if (selected != null) AlertDialog(onDismissRequest = { selectedId = null },
        title = { Text(selected.authorName) },
        text = { Column {
            if (!selected.deleted) TextButton({ selectedId = null; actions.copyText(selected.text) }) { Text(stringResource(Res.string.m4_chat_copy)) }
            if (canSend && !selected.deleted) {
                if (selected.permissions.reply) TextButton({ selectedId = null; actions.reply(selected.id) }) { Text(stringResource(Res.string.m4_chat_reply)) }
                if (selected.permissions.edit) TextButton({ selectedId = null; actions.edit(selected.id) }) { Text(stringResource(Res.string.m4_chat_edit)) }
                if (selected.permissions.pin) TextButton({ selectedId = null; actions.pin(selected.id, !selected.pinned) }) {
                    Text(stringResource(if (selected.pinned) Res.string.m4_chat_unpin else Res.string.m4_chat_pin))
                }
                if (selected.permissions.react) TextButton({ selectedId = null; reactionId = selected.id }) { Text(stringResource(Res.string.m4_chat_reactions)) }
                if (selected.permissions.deleteEveryone) TextButton({ selectedId = null; deleteTarget = selected.id to true }) {
                    Text(stringResource(Res.string.m4_chat_delete_all))
                }
            }
            if (selected.permissions.deleteSelf) TextButton({ selectedId = null; deleteTarget = selected.id to false }) {
                Text(stringResource(Res.string.m4_chat_delete_self))
            }
        } }, confirmButton = { TextButton({ selectedId = null }) { Text(stringResource(Res.string.m4_close)) } })
    deleteTarget?.let { target ->
        val message = state.messages.firstOrNull { it.id == target.first }
        val permitted = message != null && if (target.second) canSend && message.permissions.deleteEveryone else message.permissions.deleteSelf
        AlertDialog(onDismissRequest = { deleteTarget = null },
            text = { Text(stringResource(Res.string.m4_chat_delete_confirm)) },
            confirmButton = { TextButton({ deleteTarget = null; actions.delete(target.first, target.second) }, enabled = permitted) {
                Text(stringResource(if (target.second) Res.string.m4_chat_delete_all else Res.string.m4_chat_delete_self))
            } }, dismissButton = { TextButton({ deleteTarget = null }) { Text(stringResource(Res.string.m4_cancel)) } })
    }
    reactionId?.let { id ->
        val valid = canSend && state.messages.any { it.id == id && !it.deleted && it.permissions.react }
        ChatEmojiPicker(enabled = valid, dismiss = { reactionId = null }, choose = { emoji ->
            if (valid) { reactionId = null; actions.react(id, emoji) }
        })
    }
}

@Composable
private fun ChatMessageRow(
    message: ChatMessageUi, artwork: ContentArtwork, avatar: @Composable (ChatMessageUi, Modifier) -> Unit,
    openLink: (String) -> Unit, openActions: () -> Unit, reply: (() -> Unit)?,
    react: ((String) -> Unit)?, play: ((ContentItem) -> Unit)?, jumpReply: () -> Unit,
) {
    val threshold = with(LocalDensity.current) { 72.dp.toPx() }
    val replyNow by rememberUpdatedState(reply)
    var distance by remember(message.id) { mutableFloatStateOf(0f) }
    val swipe = if (reply == null) Modifier else Modifier.pointerInput(message.id) {
        detectHorizontalDragGestures(onDragStart = { distance = 0f }, onDragCancel = { distance = 0f },
            onDragEnd = { if (abs(distance) >= threshold) replyNow?.invoke(); distance = 0f }) { change, amount ->
            distance += amount
            if (abs(distance) >= threshold) change.consume()
        }
    }
    Surface(modifier = Modifier.fillMaxWidth().padding(
        start = if (message.own) 48.dp else 8.dp, end = if (message.own) 8.dp else 48.dp, top = 4.dp, bottom = 4.dp)
        .then(swipe).combinedClickable(onClick = openActions, onLongClick = openActions),
        color = if (message.own) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                avatar(message, Modifier.size(32.dp))
                Text(message.authorName, style = MaterialTheme.typography.labelLarge)
                Text(message.time, style = MaterialTheme.typography.labelSmall)
            }
            if (message.deleted) Text(stringResource(Res.string.m4_chat_deleted))
            else {
                message.reply?.let { quoted ->
                    TextButton(jumpReply, enabled = quoted.messageId != null) { Text("${quoted.author}: ${quoted.text}", maxLines = 2) }
                }
                SelectionContainer {
                    Text(buildAnnotatedString {
                        var offset = 0
                        Regex("https?://[^\\s]+").findAll(message.text).forEach { match ->
                            append(message.text.substring(offset, match.range.first))
                            withLink(LinkAnnotation.Clickable(match.value, linkInteractionListener = { openLink(match.value) })) { append(match.value) }
                            offset = match.range.last + 1
                        }
                        append(message.text.substring(offset))
                    })
                }
                message.track?.let { track ->
                    Row {
                        artwork(track, Modifier.size(56.dp))
                        Column(Modifier.weight(1f)) { Text(track.title); Text(track.subtitle) }
                        play?.let { start -> TextButton({ start(track) }) { Text(stringResource(Res.string.m4_play)) } }
                    }
                }
                if (message.edited) Text(stringResource(Res.string.m4_chat_edited), style = MaterialTheme.typography.labelSmall)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(message.reactions, key = { it.emoji }) { reaction ->
                        FilterChip(reaction.mine, { react?.invoke(reaction.emoji) }, enabled = react != null,
                            label = { Text("${reaction.emoji} ${reaction.count}") })
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatEmojiPicker(enabled: Boolean, dismiss: () -> Unit, choose: (String) -> Unit) {
    var categoryIndex by rememberSaveable { mutableStateOf(0) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(Res.string.m4_emojis)) },
        text = { Column {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(EmojiCatalog, key = { _, group -> group.first.name }) { index, group ->
                    FilterChip(categoryIndex == index, { categoryIndex = index }, label = {
                        Text(stringResource(when (group.first) {
                            ChatEmojiCategory.SMILEYS -> Res.string.m4_emoji_smileys
                            ChatEmojiCategory.PEOPLE -> Res.string.m4_emoji_people
                            ChatEmojiCategory.ANIMALS -> Res.string.m4_emoji_animals
                            ChatEmojiCategory.FOOD -> Res.string.m4_emoji_food
                            ChatEmojiCategory.TRAVEL -> Res.string.m4_emoji_travel
                            ChatEmojiCategory.ACTIVITIES -> Res.string.m4_emoji_activities
                            ChatEmojiCategory.OBJECTS -> Res.string.m4_emoji_objects
                            ChatEmojiCategory.SYMBOLS -> Res.string.m4_emoji_symbols
                            ChatEmojiCategory.FLAGS -> Res.string.m4_emoji_flags
                        }))
                    })
                }
            }
            key(categoryIndex) {
                LazyVerticalGrid(GridCells.Adaptive(48.dp), Modifier.heightIn(max = 360.dp)) {
                    gridItemsIndexed(EmojiCatalog[categoryIndex].second) { _, emoji ->
                        TextButton({ choose(emoji) }, enabled = enabled) { Text(emoji) }
                    }
                }
            }
        } }, confirmButton = { TextButton(dismiss) { Text(stringResource(Res.string.m4_close)) } })
}
