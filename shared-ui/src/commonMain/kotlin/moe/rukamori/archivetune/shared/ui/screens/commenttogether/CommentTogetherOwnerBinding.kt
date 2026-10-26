/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.ui.screens.commenttogether

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.shared.data.listentogether.ChatMessagePayload
import moe.rukamori.archivetune.shared.data.listentogether.ChatSongPickerOwner
import moe.rukamori.archivetune.shared.data.listentogether.ListenTogetherChatEntry
import moe.rukamori.archivetune.shared.data.listentogether.ListenTogetherChatMessageHandle
import moe.rukamori.archivetune.shared.data.listentogether.ListenTogetherChatOwner
import moe.rukamori.archivetune.shared.data.listentogether.ListenTogetherClient
import moe.rukamori.archivetune.shared.data.listentogether.ListenTogetherConnectionState
import moe.rukamori.archivetune.shared.data.listentogether.ListenTogetherRole
import moe.rukamori.archivetune.shared.data.listentogether.ListenTogetherTypingUser
import moe.rukamori.archivetune.shared.data.listentogether.MessageFormat
import moe.rukamori.archivetune.shared.data.listentogether.RepliedMessage
import moe.rukamori.archivetune.shared.data.listentogether.RoomState
import moe.rukamori.archivetune.shared.data.listentogether.TrackInfo
import moe.rukamori.archivetune.shared.data.listentogether.toChatTrack
import moe.rukamori.archivetune.shared.ui.navigation.LocalShellContentPadding

/*
 * Real-owner binding for the comment-together screen. The host constructs one [CommentTogetherOwnerBinding] over the
 * owners it already built (the room client, the chat owner and the song picker owner) and mounts
 * [CommentTogetherBoundScreen]. The binding holds no client, queue, protocol, persistence or root graph of its own. It
 * projects the owners' state, keeps only the composer (draft, reply and edit) and the transient notice, and maps each
 * action onto an owner call or a native port.
 *
 * Message identity. The owner issues each live message a stable handle, ListenTogetherChatMessageHandle, and publishes
 * it with the message in chatEntries. The screen id is that handle in text form, and every action goes through the
 * owner's handle overloads. A handle keeps its message for the message's whole life, so prepending or reordering rows
 * cannot retarget an open popup. A handle that is no longer live resolves to nothing, so a stale action does nothing.
 *
 * Outcomes. The owner's send and control calls are fire-and-forget: they return nothing and refuse silently outside a
 * room or on a protobuf server. A submitted message counts as accepted only when the room echoes it into the live list,
 * and an edit only when the list shows the new text. Until then the composer keeps the draft. While the room link is
 * down, a send or edit is refused up front. A submission whose link dropped, or that the room never echoed within the
 * echo wait, counts as stale, so an identical resend goes out again. No delivery is reported that did not happen.
 */

/**
 * Native effects and device state the host supplies to [CommentTogetherOwnerBinding]. Effects that can fail return
 * whether the platform accepted them, so the binding raises a notice only after a real outcome.
 */
interface CommentTogetherNativePorts {
    /** The local player's current song. It is the share fallback when the room has no current track. Null when nothing is playable. */
    val localCurrentTrack: StateFlow<TrackInfo?>

    /** True while the app is in the foreground. Chat notifications stop only when this is false or the chat is not on screen. */
    val appForeground: StateFlow<Boolean>

    /** Local clock text for a message timestamp, for example "14:05". */
    fun formatTime(timestampMs: Long): String

    /** Writes [text] to the system clipboard. Returns true only when the platform accepted the write. */
    fun copyText(text: String): Boolean

    /** Starts [track] in the room through the host's playback owner. Returns true only when playback was accepted. */
    fun playShared(track: TrackInfo): Boolean

    /** Opens [url] in the app or with the system handler. The screen never opens a link itself. */
    fun openLink(url: String)

    /** Leaves the chat screen. */
    fun back()
}

/**
 * The screen id of a live message: its owner-issued handle in text form. The text names the owner, the session and the
 * sequence. The owner never reissues a sequence, so two live rows never share an id, and a retired id never matches a
 * later message.
 */
internal fun commentMessageHandle(handle: ListenTogetherChatMessageHandle): String = handle.toString()

private fun emptyComposer(): CommentComposerUi = CommentComposerUi(draft = "", replyTo = null, editing = null)

/**
 * Longest wait for one song search. The owner keeps loading set when its page job is cancelled without a publish, and
 * without this bound that search would hold the search gate for every later search.
 */
private const val SONG_SEARCH_WAIT_MS = 30_000L

/**
 * Longest wait for the room's echo of one send or edit while the link stays up. A room can refuse a message without
 * echoing it, and without this bound an identical resend would wait until the text or the session changed.
 */
private const val ECHO_WAIT_MS = 15_000L

/** True when the local user wrote [message]: matched by user id, with the username as the fallback for restored history. */
private fun isOwn(message: ChatMessagePayload, state: BindingState): Boolean {
    val me = state.userId
    if (me != null && message.userId == me) return true
    val name = state.username
    return message.restored && name != null && message.username == name
}

/** Everything the projection reads. Owner flows write their own field; the composer and notice are written by actions. */
private data class BindingState(
    /** Increases on each real room session change (join, create, resume, leave), never on a transient drop. */
    val generation: Long = 0L,
    val session: String? = null,
    val roomCode: String? = null,
    val userId: String? = null,
    val username: String? = null,
    val room: RoomState? = null,
    val role: ListenTogetherRole = ListenTogetherRole.NONE,
    val entries: List<ListenTogetherChatEntry> = emptyList(),
    val typing: List<ListenTogetherTypingUser> = emptyList(),
    val supported: Boolean = false,
    val localTrack: TrackInfo? = null,
    val composer: CommentComposerUi = emptyComposer(),
    val notice: CommentNotice? = null,
    val pendingSend: PendingSend? = null,
    val pendingEdit: PendingEdit? = null,
    /** True while the room link is up. A send or edit made while it is down would never reach the room. */
    val connected: Boolean = false,
    /** The last submission sequence issued. Each send or edit takes the next one, so a timer settles only its own. */
    val pendingSeq: Long = 0L,
)

/**
 * A submission handed to the room that the live list has not echoed yet. [baseline] holds the handles present before it.
 * [stale] is set when the link drops or the echo wait runs out. The room may then never echo it, so the draft stays
 * and an identical resend is admitted again.
 */
private data class PendingSend(
    val seq: Long,
    val text: String,
    val replyTo: RepliedMessage?,
    val baseline: Set<String>,
    val stale: Boolean = false,
)

/** An edit handed to the room that the live list does not show yet. [stale] works as it does for [PendingSend]. */
private data class PendingEdit(val seq: Long, val handle: String, val text: String, val stale: Boolean = false)

private data class ClientSnapshot(
    val roomCode: String?,
    val userId: String?,
    val username: String?,
    val room: RoomState?,
    val role: ListenTogetherRole,
)

/**
 * Binds the real chat owners to [CommentTogetherScreen].
 *
 * Host wiring (one per root): build it after the owners, on the same single-thread dispatcher the owners use, and mount
 * [CommentTogetherBoundScreen] on the chat route. [serverChatSupported] comes from the active server selection, and
 * the binding ANDs it with the client's live wire protocol. There is no default for it. Call [close] when the root
 * tears the chat down. Owners and ports belong to the host and are not closed here.
 */
class CommentTogetherOwnerBinding(
    scope: CoroutineScope,
    private val client: ListenTogetherClient,
    private val chat: ListenTogetherChatOwner,
    private val songPicker: ChatSongPickerOwner,
    /** Whether the active server carries a chat relay. Supplied by the host from its server selection. */
    private val serverChatSupported: StateFlow<Boolean>,
    private val native: CommentTogetherNativePorts,
) {
    private val bindingJob = SupervisorJob(scope.coroutineContext[Job])
    private val bindingScope = CoroutineScope(scope.coroutineContext + bindingJob)
    private val inputs = MutableStateFlow(BindingState())
    private val screenReport = MutableStateFlow(false)

    /** Serializes song searches, so one search's wait never reads another search's terminal state. */
    private val searchGate = Mutex()

    /** The screen state, rebuilt from the owners on every change. The host collects it. */
    val state: StateFlow<CommentTogetherUi> = inputs
        .map { project(it) }
        .stateIn(bindingScope, SharingStarted.Eagerly, project(inputs.value))

    /**
     * True while the chat is visible to the user: the screen reports it on screen and the app is in the foreground.
     * Chat notifications should be gated on this, so a backgrounded app never keeps them suppressed.
     */
    val visibleToUser: StateFlow<Boolean> = combine(screenReport, native.appForeground) { onScreen, foreground ->
        onScreen && foreground
    }.stateIn(bindingScope, SharingStarted.Eagerly, false)

    /** The actions the screen calls. Each one reaches an owner or a native port, or changes the local composer. */
    val actions: CommentTogetherActions = CommentTogetherActions(
        back = { native.back() },
        screenVisible = { visible -> screenReport.value = visible },
        markRead = { chat.markRead() },
        draftChanged = { text -> inputs.update { it.copy(composer = it.composer.copy(draft = text)) } },
        notifyTyping = { chat.notifyTyping() },
        send = { request -> submit(request) },
        cancelCompose = { cancelCompose() },
        reply = { messageId -> startReply(messageId) },
        edit = { messageId -> beginEdit(messageId) },
        delete = { messageId, forEveryone -> deleteMessage(messageId, forEveryone) },
        setPinned = { messageId, pinned -> pinMessage(messageId, pinned) },
        react = { messageId, emoji -> reactTo(messageId, emoji) },
        copyText = { text -> if (native.copyText(text)) notice(CommentNotice.MessageCopied) },
        playShared = { track -> if (native.playShared(track)) notice(CommentNotice.SongPlayed) },
        shareTrack = { track -> chat.shareTrack(track) },
        shareCurrent = { shareCurrent() },
        searchSongs = { query -> runSongSearch(query) },
        openLink = { url -> native.openLink(url) },
        noticeShown = { inputs.update { it.copy(notice = null) } },
    )

    init {
        bindingScope.launch {
            combine(client.roomCode, client.userId, client.username, client.roomState, client.role) { code, user, name, room, role ->
                ClientSnapshot(roomCode = code, userId = user, username = name, room = room, role = role)
            }.collect { snapshot -> inputs.update { applyClient(it, snapshot) } }
        }
        bindingScope.launch {
            chat.chatEntries.collect { entries ->
                inputs.update { reconcile(it.copy(entries = entries)) }
            }
        }
        bindingScope.launch {
            chat.typingUsers.collect { typing -> inputs.update { it.copy(typing = typing) } }
        }
        bindingScope.launch {
            combine(serverChatSupported, client.protocol) { byServer, format ->
                byServer && format == MessageFormat.JSON
            }.collect { supported -> inputs.update { it.copy(supported = supported) } }
        }
        bindingScope.launch {
            native.localCurrentTrack.collect { track -> inputs.update { it.copy(localTrack = track) } }
        }
        bindingScope.launch {
            client.connectionState.collect { link -> inputs.update { applyLink(it, link) } }
        }
    }

    /** Stops the binding's collectors. A search still waiting on the owner is cancelled with them. */
    fun close() {
        bindingJob.cancel()
    }

    /**
     * Applies a room snapshot. A real session change (join, create, resume or leave) starts a new generation and drops
     * the composer and any in-flight submission, which belong to the old session. A transient drop keeps the room
     * snapshot in the client, so it leaves the composer alone.
     */
    private fun applyClient(current: BindingState, snapshot: ClientSnapshot): BindingState {
        val session = snapshot.room?.let { "${snapshot.roomCode.orEmpty()}|${snapshot.userId.orEmpty()}" }
        val base = if (session != current.session) {
            current.copy(
                generation = current.generation + 1,
                composer = emptyComposer(),
                pendingSend = null,
                pendingEdit = null,
            )
        } else {
            current
        }
        return base.copy(
            session = session,
            roomCode = snapshot.roomCode,
            userId = snapshot.userId,
            username = snapshot.username,
            room = snapshot.room,
            role = snapshot.role,
        )
    }

    /**
     * Tracks the room link. While it is down a send or edit cannot reach the room, so [submit] refuses it. A submission
     * already handed over turns stale, because the room may never echo it. Its draft stays, so an identical resend
     * can go out again once the link is back.
     */
    private fun applyLink(current: BindingState, link: ListenTogetherConnectionState): BindingState {
        if (link == ListenTogetherConnectionState.CONNECTED) return current.copy(connected = true)
        return current.copy(
            connected = false,
            pendingSend = current.pendingSend?.copy(stale = true),
            pendingEdit = current.pendingEdit?.copy(stale = true),
        )
    }

    /** Projects the owners' state onto the screen contract. Pure over [state] except for the host's time formatting. */
    private fun project(state: BindingState): CommentTogetherUi {
        val hostId = state.room?.hostId
        val me = state.username
        // Every live row is shown, the local user's solo rows included, as the baseline screen showed the raw list.
        // The owner's solo flag only keeps those rows out of persisted history, so it is not a display filter.
        val messages = state.entries
            .map { entry ->
                val message = entry.message
                CommentMessageUi(
                    id = commentMessageHandle(entry.handle),
                    author = CommentAuthorUi(
                        userId = message.userId,
                        username = message.username,
                        own = isOwn(message, state),
                        host = message.userId == hostId,
                    ),
                    text = message.message,
                    timeText = native.formatTime(message.timestamp),
                    timestamp = message.timestamp,
                    pinned = message.pinned,
                    pinnedAt = message.pinnedAt,
                    edited = message.edited,
                    deleted = message.deleted,
                    restored = message.restored,
                    replyTo = message.replyTo,
                    sharedTrack = message.sharedTrack,
                    // The owner stores usernames per emoji, so a count is the list size and "mine" is a name match.
                    reactions = message.reactions.map { (emoji, users) ->
                        CommentReactionUi(emoji = emoji, count = users.size, mine = me != null && me in users)
                    },
                )
            }
        val typing = state.typing
            .filterNot { it.userId == state.userId }
            .distinctBy { it.userId }
            .map { CommentAuthorUi(userId = it.userId, username = it.username, own = false, host = it.userId == hostId) }
        return CommentTogetherUi(
            sessionKey = "comment-session-${state.generation}",
            roomCode = state.roomCode,
            messages = messages,
            typing = typing,
            composer = state.composer,
            supported = state.supported,
            localHost = state.role == ListenTogetherRole.HOST,
            currentTrack = state.room?.currentTrack ?: state.localTrack,
            notice = state.notice,
        )
    }

    /**
     * Settles the pending submission and edit against the live list. It runs on every chat list change, so the composer
     * clears only when the room has echoed the message back, never when the call was made.
     */
    private fun reconcile(current: BindingState): BindingState {
        val afterSend = settleSend(current)
        val afterEdit = settleEdit(afterSend)
        return dropMissingEditTarget(afterEdit)
    }

    private fun settleSend(state: BindingState): BindingState {
        val send = state.pendingSend ?: return state
        val echoed = state.entries.any { entry ->
            // A restored row is history, never the room's echo of this send, so only a live row can settle it. A handle
            // outside the baseline was issued after the send, so it is the room's echo and not an older row.
            val message = entry.message
            !message.restored && isOwn(message, state) && commentMessageHandle(entry.handle) !in send.baseline &&
                message.message.trim() == send.text
        }
        if (!echoed) return state
        val sameDraft = state.composer.draft.trim() == send.text
        return state.copy(
            pendingSend = null,
            composer = if (sameDraft) state.composer.copy(draft = "", replyTo = null) else state.composer,
        )
    }

    private fun settleEdit(state: BindingState): BindingState {
        val edit = state.pendingEdit ?: return state
        val target = state.entries
            .firstOrNull { commentMessageHandle(it.handle) == edit.handle && !it.message.deleted }?.message
        return when {
            target == null -> state.copy(pendingEdit = null, composer = state.composer.copy(editing = null))
            target.message.trim() == edit.text -> {
                val sameDraft = state.composer.draft.trim() == edit.text
                state.copy(
                    pendingEdit = null,
                    composer = state.composer.copy(editing = null, draft = if (sameDraft) "" else state.composer.draft),
                )
            }
            else -> state
        }
    }

    /** An edit whose target was deleted or removed from the live list is no longer editable, so the edit state closes. */
    private fun dropMissingEditTarget(state: BindingState): BindingState {
        val editing = state.composer.editing ?: return state
        val live = state.entries.any { commentMessageHandle(it.handle) == editing.messageId && !it.message.deleted }
        return if (live) state else state.copy(composer = state.composer.copy(editing = null))
    }

    /** The live entry the screen id names, or null when no live message has that id. */
    private fun liveEntry(messageId: String): ListenTogetherChatEntry? =
        inputs.value.entries.firstOrNull { commentMessageHandle(it.handle) == messageId }

    private fun resolve(messageId: String): ChatMessagePayload? = liveEntry(messageId)?.message

    /** A live message the local user wrote. Only these can be edited. */
    private fun ownTarget(messageId: String): ListenTogetherChatEntry? {
        val state = inputs.value
        return state.entries.firstOrNull {
            commentMessageHandle(it.handle) == messageId && !it.message.deleted && isOwn(it.message, state)
        }
    }

    private fun submit(request: CommentSendUi) {
        val text = request.text.trim()
        if (text.isEmpty()) return
        val editId = request.editMessageId
        if (editId != null) {
            val target = ownTarget(editId) ?: return
            if (target.message.message.trim() == text) {
                // The message already reads this text, so nothing changes and no control frame is sent.
                inputs.update { it.copy(composer = it.composer.copy(draft = "", editing = null)) }
                return
            }
            val state = inputs.value
            // While the link is down the edit would be lost, so it is refused and the edit state keeps the draft.
            if (!state.connected) return
            // A repeat tap sends no second control frame while the same edit awaits the room's echo on a live link.
            val awaiting = state.pendingEdit
            if (awaiting != null && !awaiting.stale && awaiting.handle == editId && awaiting.text == text) return
            var seq = 0L
            inputs.update {
                seq = it.pendingSeq + 1
                it.copy(pendingSeq = seq, pendingEdit = PendingEdit(seq = seq, handle = editId, text = text))
            }
            armEchoWait(seq)
            chat.editMessage(target.handle, text)
            return
        }
        val state = inputs.value
        // While the link is down the message would be lost, so it is refused and the draft stays in the composer.
        if (!state.connected) return
        // A repeat tap sends no second message while the same text and reply await the room's echo on a live link.
        // A stale submission no longer blocks an identical resend, and any other text replaces the pending send.
        val awaiting = state.pendingSend
        if (awaiting != null && !awaiting.stale && awaiting.text == text && awaiting.replyTo == request.replyTo) return
        var seq = 0L
        inputs.update { current ->
            seq = current.pendingSeq + 1
            current.copy(
                pendingSeq = seq,
                pendingSend = PendingSend(
                    seq = seq,
                    text = text,
                    replyTo = request.replyTo,
                    baseline = current.entries.mapTo(HashSet<String>()) { commentMessageHandle(it.handle) },
                ),
            )
        }
        armEchoWait(seq)
        chat.sendMessage(text, request.replyTo)
    }

    /**
     * Marks submission [seq] stale when the room has not echoed it within [ECHO_WAIT_MS]. A newer submission carries
     * its own sequence, so the timer of an older one leaves it alone.
     */
    private fun armEchoWait(seq: Long) {
        bindingScope.launch {
            delay(ECHO_WAIT_MS)
            inputs.update { current ->
                val send = current.pendingSend
                val edit = current.pendingEdit
                current.copy(
                    pendingSend = if (send != null && send.seq == seq) send.copy(stale = true) else send,
                    pendingEdit = if (edit != null && edit.seq == seq) edit.copy(stale = true) else edit,
                )
            }
        }
    }

    private fun cancelCompose() {
        inputs.update { state ->
            val wasEditing = state.composer.editing != null
            state.copy(
                composer = state.composer.copy(
                    replyTo = null,
                    editing = null,
                    draft = if (wasEditing) "" else state.composer.draft,
                ),
                pendingEdit = null,
            )
        }
    }

    private fun startReply(messageId: String) {
        val target = resolve(messageId)?.takeIf { !it.deleted } ?: return
        inputs.update { state ->
            state.copy(
                composer = state.composer.copy(
                    replyTo = RepliedMessage(username = target.username, message = target.message),
                ),
            )
        }
    }

    private fun beginEdit(messageId: String) {
        val target = ownTarget(messageId)?.message ?: return
        inputs.update {
            it.copy(
                composer = CommentComposerUi(
                    draft = target.message,
                    replyTo = null,
                    editing = CommentEditTargetUi(messageId = messageId, originalText = target.message),
                ),
                pendingEdit = null,
            )
        }
    }

    private fun deleteMessage(messageId: String, forEveryone: Boolean) {
        val handle = liveEntry(messageId)?.handle ?: return
        if (forEveryone) chat.deleteMessageForEveryone(handle) else chat.deleteMessageForMe(handle)
    }

    private fun pinMessage(messageId: String, pinned: Boolean) {
        val handle = liveEntry(messageId)?.takeIf { !it.message.deleted }?.handle ?: return
        chat.setPinned(handle, pinned)
    }

    private fun reactTo(messageId: String, emoji: String) {
        val handle = liveEntry(messageId)?.takeIf { !it.message.deleted }?.handle ?: return
        chat.toggleReaction(handle, emoji)
    }

    /** Shares the room's current song, or the local one when the room has none. Raises [CommentNotice.NothingPlaying] otherwise. */
    private fun shareCurrent() {
        val state = inputs.value
        val track = state.room?.currentTrack ?: state.localTrack
        if (track == null) notice(CommentNotice.NothingPlaying) else chat.shareTrack(track)
    }

    private fun notice(value: CommentNotice) {
        inputs.update { it.copy(notice = value) }
    }

    /**
     * Runs one song search through the picker owner and returns its outcome for this query only. A blank query clears the
     * picker and is not a search, so it returns an empty list. Cancellation of the caller propagates out of the wait.
     */
    private suspend fun runSongSearch(query: String): Result<List<TrackInfo>> = searchGate.withLock {
        val outcome: Result<List<TrackInfo>> = if (query.isBlank()) {
            songPicker.changeQuery(query)
            Result.success(emptyList())
        } else {
            awaitSongSearch(query)
        }
        outcome
    }

    private suspend fun awaitSongSearch(query: String): Result<List<TrackInfo>> {
        val before = songPicker.state.value
        songPicker.changeQuery(query)
        songPicker.search()
        // changeQuery and search both set loading synchronously, so this wait ends on this search's publish, on a newer
        // query, or on a close that clears loading without publishing. The bound covers an owner page cancelled without
        // a publish, which would otherwise keep loading set and hold the search gate for every later search.
        val settled = withTimeoutOrNull(SONG_SEARCH_WAIT_MS) {
            songPicker.state.first { it.query != query || !it.loading }
        } ?: return Result.failure(IllegalStateException("Song search timed out"))
        return when {
            settled.query != query -> Result.failure(IllegalStateException("Song search was superseded"))
            // The owner keeps the previous items on a failure, so a failed search never reads back as results.
            settled.failed -> Result.failure(IllegalStateException("Song search failed"))
            // Every publish makes a new list, so an unchanged list reference means no result was published.
            settled.items !== before.items -> Result.success(settled.items.map { it.item.toChatTrack() })
            else -> Result.failure(IllegalStateException("Song search was cancelled"))
        }
    }
}

/**
 * Mounts [CommentTogetherScreen] over a bound owner. The host supplies the artwork composables. The host keeps the
 * binding alive while the chat route is reachable and calls [CommentTogetherOwnerBinding.close] when it leaves.
 */
@Composable
fun CommentTogetherBoundScreen(
    binding: CommentTogetherOwnerBinding,
    avatar: CommentAvatar,
    thumbnail: CommentThumbnail,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = LocalShellContentPadding.current,
) {
    val state by binding.state.collectAsState()
    CommentTogetherScreen(
        state = state,
        actions = binding.actions,
        avatar = avatar,
        thumbnail = thumbnail,
        modifier = modifier,
        contentPadding = contentPadding,
    )
}
