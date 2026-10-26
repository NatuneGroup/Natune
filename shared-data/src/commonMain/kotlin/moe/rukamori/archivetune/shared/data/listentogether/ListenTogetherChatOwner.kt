package moe.rukamori.archivetune.shared.data.listentogether

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random

/** Buffered accepted messages for a notification collector that is briefly busy. Delivery is best effort beyond this. */
private const val ACCEPTED_MESSAGE_BUFFER = 64

/**
 * The room chat on top of [ListenTogetherClient]: messages, typing indicators, the unread count, reactions, edits,
 * deletes, pins, the custom-picture broadcast and the saved history. It speaks only the JSON chat relay. The client
 * refuses chat on protobuf servers, and this owner sends no chat frame there either.
 *
 * Envelopes (reply, shared song, control, custom picture) travel in the chat text. Inbound frames are decoded here in
 * the Android order: picture, control, then reply and shared song on the text that remains. A control frame never
 * enters the list. The sender applies its own change at once and ignores the echo, which is safe because every control
 * is idempotent.
 *
 * Every live message has a [ListenTogetherChatMessageHandle] in [chatEntries]. A handle names the message itself, not
 * its position or its sender and timestamp pair, so prepended history and reordering never move an action to another
 * message. An action taken through a handle reads the message's current state when it runs. A handle that is no longer
 * live (deleted for this user, the room was left, or it came from an earlier session) does nothing.
 *
 * All state changes run on [scope]. Pass the same single-thread dispatcher the client uses. Construct the owner before
 * [ListenTogetherClient.connect], because the client's event flow does not replay. Call [close] when the owner is
 * discarded.
 */
class ListenTogetherChatOwner(
    private val client: ListenTogetherClient,
    private val scope: CoroutineScope,
    private val history: ListenTogetherChatHistoryStore,
    /** The local custom picture as JPEG bytes, or null unless this member chose one. The host reads it on IO. */
    private val localAvatarBytes: suspend () -> ByteArray?,
    private val nowMs: () -> Long,
    /** Receives short reasons for dropped data (an unreadable history, an oversized picture). Never raw payloads. */
    private val onDiagnostic: (String) -> Unit = {},
) {
    /** Marks this owner's handles, so a handle issued by another owner never resolves here. */
    private val ownerToken: Long = Random.nextLong()

    /** Counts room sessions. Each room exit or connection failure starts a new one, which retires the older handles. */
    private var sessionId: Long = 1L
    private var nextSequence: Long = 0L

    private val _chatEntries = MutableStateFlow<List<ListenTogetherChatEntry>>(emptyList())

    /**
     * The live room chat, oldest first, with each message's stable handle. A screen that acts on a message should keep
     * the handle rather than the payload, because the handle keeps the action on the same message after the list changes.
     */
    val chatEntries: StateFlow<List<ListenTogetherChatEntry>> = _chatEntries.asStateFlow()

    private val _chatMessages = MutableStateFlow<List<ChatMessagePayload>>(emptyList())

    /** The room chat, oldest first, with the local flags (solo, restored) already set. */
    val chatMessages: StateFlow<List<ChatMessagePayload>> = _chatMessages.asStateFlow()

    private val _typingUsers = MutableStateFlow<List<ListenTogetherTypingUser>>(emptyList())

    /** Members currently typing, freshest last. Each entry expires after [ListenTogetherChatTiming.TYPING_TTL_MS]. */
    val typingUsers: StateFlow<List<ListenTogetherTypingUser>> = _typingUsers.asStateFlow()

    private val _unreadCount = MutableStateFlow(0)

    /** Messages from other members received since the chat was last marked read. */
    val unreadCount: StateFlow<Int> = _unreadCount.asStateFlow()

    private val _customAvatars = MutableStateFlow<Map<String, ByteArray>>(emptyMap())

    /** Custom pictures received from members, keyed by user id. Kept after leaving a room, as on Android. */
    val customAvatars: StateFlow<Map<String, ByteArray>> = _customAvatars.asStateFlow()

    private val _acceptedMessages = MutableSharedFlow<ChatMessagePayload>(extraBufferCapacity = ACCEPTED_MESSAGE_BUFFER)

    /**
     * Each message the list accepted, including this user's own echoes. Notification code should skip its own messages
     * and must not treat this flow as the source of truth. [chatEntries] is the source of truth.
     */
    val acceptedMessages: SharedFlow<ChatMessagePayload> = _acceptedMessages.asSharedFlow()

    private var persistJob: Job? = null
    private var typingTicker: Job? = null
    private var lastTypingSentAt = 0L
    private val eventJob: Job

    /**
     * Sends a chat message, optionally as a reply and optionally carrying a song card. A blank message without a song is
     * refused. The list is not changed here: the room's echo adds the message, as on Android.
     */
    fun sendMessage(message: String, replyTo: RepliedMessage? = null, sharedTrack: TrackInfo? = null) {
        if (message.isBlank() && sharedTrack == null) return
        scope.launch {
            if (!client.isInRoom) return@launch
            // Android order: the song envelope goes first, then the reply wraps the whole text. A receiver therefore
            // reads the reply first and the song from the text that remains.
            var wire = message
            if (sharedTrack != null) wire = ListenTogetherEnvelope.encodeSharedTrack(sharedTrack) + wire
            if (replyTo != null) wire = ListenTogetherEnvelope.encodeReply(replyTo) + wire
            client.sendChat(ChatPayload(message = wire, replyTo = replyTo))
        }
    }

    /** Shares a song into the chat as a card. The caption is trimmed. Playing the song is the host's decision. */
    fun shareTrack(track: TrackInfo, caption: String = "") {
        sendMessage(caption.trim(), replyTo = null, sharedTrack = track)
    }

    /** Announces that this user is typing. At most one frame goes out per [ListenTogetherChatTiming.TYPING_THROTTLE_MS]. */
    fun notifyTyping() {
        scope.launch {
            if (!client.isInRoom) return@launch
            val now = nowMs()
            if (now - lastTypingSentAt < ListenTogetherChatTiming.TYPING_THROTTLE_MS) return@launch
            lastTypingSentAt = now
            sendControl(ChatControlEvent(action = ChatControlEvent.ACTION_TYPING))
        }
    }

    /** The current state of the message [target] names, or null when it is no longer live. Safe to call from any thread. */
    fun resolve(target: ListenTogetherChatMessageHandle): ChatMessagePayload? = entryFor(target)?.message

    /** The handle of the live message with this sender and timestamp, or null when none is live. */
    fun handleFor(message: ChatMessagePayload): ListenTogetherChatMessageHandle? = entryMatching(message)?.handle

    /** Toggles this user's emoji reaction on a message, for the whole room. */
    fun toggleReaction(message: ChatMessagePayload, emoji: String) {
        scope.launch { entryMatching(message)?.let { reactOn(it.handle, emoji) } }
    }

    /** Toggles this user's emoji reaction on the message [target] names, for the whole room. A stale target does nothing. */
    fun toggleReaction(target: ListenTogetherChatMessageHandle, emoji: String) {
        scope.launch { reactOn(target, emoji) }
    }

    /** Pins or unpins a message for the whole room. */
    fun setPinned(message: ChatMessagePayload, pinned: Boolean) {
        scope.launch { entryMatching(message)?.let { pinOn(it.handle, pinned) } }
    }

    /** Pins or unpins the message [target] names for the whole room. A stale target does nothing. */
    fun setPinned(target: ListenTogetherChatMessageHandle, pinned: Boolean) {
        scope.launch { pinOn(target, pinned) }
    }

    /** Edits this user's own message for the whole room. A blank text and another member's message are both refused. */
    fun editMessage(message: ChatMessagePayload, newText: String) {
        if (newText.isBlank()) return
        scope.launch { entryMatching(message)?.let { editOn(it.handle, newText) } }
    }

    /** Edits this user's own message [target] names for the whole room. A blank text and a stale target are refused. */
    fun editMessage(target: ListenTogetherChatMessageHandle, newText: String) {
        if (newText.isBlank()) return
        scope.launch { editOn(target, newText) }
    }

    /** Deletes a message for the whole room. Members delete their own messages. The room host may also delete anyone's. */
    fun deleteMessageForEveryone(message: ChatMessagePayload) {
        scope.launch { entryMatching(message)?.let { deleteForEveryoneOn(it.handle) } }
    }

    /** Deletes the message [target] names for the whole room, under the same rules. A stale target does nothing. */
    fun deleteMessageForEveryone(target: ListenTogetherChatMessageHandle) {
        scope.launch { deleteForEveryoneOn(target) }
    }

    /** Hides a message from this user's view only. Nothing is sent, and the saved history drops the message as well. */
    fun deleteMessageForMe(message: ChatMessagePayload) {
        scope.launch { entryMatching(message)?.let { hideForMe(it.handle) } }
    }

    /** Hides the message [target] names from this user's view only. A stale target does nothing. */
    fun deleteMessageForMe(target: ListenTogetherChatMessageHandle) {
        scope.launch { hideForMe(target) }
    }

    /** Marks the chat as read. The unread count returns to zero. */
    fun markRead() {
        scope.launch { _unreadCount.value = 0 }
    }

    /** Re-sends this user's custom picture to the room, when the member chose one. Joins send it automatically. */
    fun broadcastCustomAvatar() {
        scope.launch { announceAvatar() }
    }

    /**
     * Clears the local chat, then disconnects the client. Leaving a room needs no wrapper, because the client's
     * [ListenTogetherEvent.RoomLeft] already clears it. A plain disconnect emits no such event, so it is handled here.
     */
    fun disconnect() {
        scope.launch {
            clearLocal()
            client.disconnect()
        }
    }

    /** Stops the owner: cancels its event collection and any pending write, sweep or debounce. */
    fun close() {
        eventJob.cancel()
        persistJob?.cancel()
        typingTicker?.cancel()
    }

    private suspend fun handleEvent(event: ListenTogetherEvent) {
        when (event) {
            // A room this user created holds no other members yet, so nothing is restored here, as on Android.
            is ListenTogetherEvent.RoomCreated -> announceAvatar()
            is ListenTogetherEvent.JoinApproved -> {
                announceAvatar()
                restoreHistory(event.state.users)
            }
            is ListenTogetherEvent.UserJoined -> {
                // The newcomer is already in the room state, so the restore brings back history with them as well.
                announceAvatar()
                restoreHistory(client.roomState.value?.users.orEmpty())
            }
            is ListenTogetherEvent.Reconnected -> restoreHistory(event.state.users)
            is ListenTogetherEvent.ChatReceived -> receive(event.payload)
            // Leaving, being removed or failing the connection ends the conversation on screen. The saved history stays.
            ListenTogetherEvent.RoomLeft, is ListenTogetherEvent.Kicked, is ListenTogetherEvent.ConnectionError -> clearLocal()
            else -> Unit
        }
    }

    /**
     * Decodes one inbound chat frame. A frame that opens with a picture or control envelope never becomes a bubble, even
     * when its body does not decode. Android would show the encoded text, so this is a deliberate divergence.
     */
    private fun receive(frame: ChatMessagePayload) {
        val text = frame.message
        if (text.startsWith(ListenTogetherEnvelope.AVATAR_PREFIX)) {
            val bytes = ListenTogetherEnvelope.decodeAvatar(text)
            if (bytes != null) {
                _customAvatars.update { it + (frame.userId to bytes) }
            } else {
                onDiagnostic("Custom picture ignored")
            }
            return
        }
        if (text.startsWith(ListenTogetherEnvelope.CHAT_CONTROL_PREFIX)) {
            ListenTogetherEnvelope.decodeChatControl(text)?.let { control ->
                applyControl(frame.userId, frame.username, control)
            }
            return
        }
        var payload = frame
        ListenTogetherEnvelope.decodeReply(payload.message)?.let { (replyTo, rest) ->
            payload = payload.copy(message = rest, replyTo = replyTo)
        }
        ListenTogetherEnvelope.decodeSharedTrack(payload.message)?.let { (track, rest) ->
            payload = payload.copy(message = rest, sharedTrack = track)
        }
        accept(payload)
    }

    /** Adds a chat message unless the same user already posted that timestamp. */
    private fun accept(incoming: ChatMessagePayload) {
        val myId = client.userId.value
        // An own message that arrives while alone is self-chatter. It is flagged so it never reaches the saved history.
        val payload = if (incoming.userId == myId && !hasOtherMembers(myId)) incoming.copy(solo = true) else incoming
        val current = _chatEntries.value
        if (current.any { it.message.timestamp == payload.timestamp && it.message.userId == payload.userId }) return
        publish(current + ListenTogetherChatEntry(handle = issueHandle(), message = payload))
        if (payload.userId != myId) _unreadCount.update { it + 1 }
        scheduleChatPersist()
        _acceptedMessages.tryEmit(payload)
    }

    private fun hasOtherMembers(myId: String?): Boolean {
        if (myId == null) return false
        return client.roomState.value?.users?.any { it.userId != myId } == true
    }

    /**
     * Applies a reaction, edit, delete, pin or typing event to local state. Every change is idempotent, so the sender can
     * apply its own action and then see the echo harmlessly. Typing from this user is ignored.
     */
    private fun applyControl(fromUserId: String, fromUsername: String, control: ChatControlEvent) {
        when (control.action) {
            ChatControlEvent.ACTION_TYPING -> {
                if (fromUserId == client.userId.value) return
                val expiresAt = nowMs() + ListenTogetherChatTiming.TYPING_TTL_MS
                _typingUsers.value = _typingUsers.value.filterNot { it.userId == fromUserId } +
                    ListenTogetherTypingUser(userId = fromUserId, username = fromUsername, expiresAtMs = expiresAt)
                ensureTypingTicker()
            }

            ChatControlEvent.ACTION_REACT, ChatControlEvent.ACTION_UNREACT -> {
                val emoji = control.emoji ?: return
                val targetTimestamp = control.targetTimestamp ?: return
                val targetUserId = control.targetUserId ?: return
                val reacting = control.action == ChatControlEvent.ACTION_REACT
                updateMessage(targetUserId, targetTimestamp) { message ->
                    val current = message.reactions[emoji].orEmpty()
                    val updated = when {
                        !reacting -> current - fromUsername
                        fromUsername in current -> current
                        else -> current + fromUsername
                    }
                    val reactions =
                        if (updated.isEmpty()) message.reactions - emoji else message.reactions + (emoji to updated)
                    message.copy(reactions = reactions)
                }
            }

            ChatControlEvent.ACTION_EDIT -> {
                val targetTimestamp = control.targetTimestamp ?: return
                val targetUserId = control.targetUserId ?: return
                if (targetUserId != fromUserId) return // only one's own messages can be edited
                val newText = control.text?.trim()?.takeIf { it.isNotEmpty() } ?: return
                updateMessage(targetUserId, targetTimestamp) { message ->
                    message.copy(message = newText, edited = true)
                }
            }

            ChatControlEvent.ACTION_DELETE -> {
                val targetTimestamp = control.targetTimestamp ?: return
                val targetUserId = control.targetUserId ?: return
                // Authors delete their own messages. The room host may also delete anyone's, for moderation.
                val senderIsHost = client.roomState.value?.hostId == fromUserId
                if (targetUserId != fromUserId && !senderIsHost) return
                // A tombstone, not a removal, so everyone and the saved history still see that a message was deleted.
                updateMessage(targetUserId, targetTimestamp) { message ->
                    message.copy(deleted = true, message = "", sharedTrack = null)
                }
            }

            ChatControlEvent.ACTION_PIN, ChatControlEvent.ACTION_UNPIN -> {
                val targetTimestamp = control.targetTimestamp ?: return
                val targetUserId = control.targetUserId ?: return
                val pin = control.action == ChatControlEvent.ACTION_PIN
                updateMessage(targetUserId, targetTimestamp) { message ->
                    // The pin time orders the pinned list latest-first. It resets to zero on unpin.
                    if (pin) {
                        message.copy(pinned = true, pinnedAt = nowMs())
                    } else {
                        message.copy(pinned = false, pinnedAt = 0L)
                    }
                }
            }
        }
    }

    /** Reacts for this user on the live message [target] names. The current state is read when this runs. */
    private fun reactOn(target: ListenTogetherChatMessageHandle, emoji: String) {
        val message = entryFor(target)?.message ?: return
        val me = client.username.value ?: return
        val reacted = message.reactions[emoji]?.contains(me) == true
        val control = ChatControlEvent(
            action = if (reacted) ChatControlEvent.ACTION_UNREACT else ChatControlEvent.ACTION_REACT,
            targetTimestamp = message.timestamp,
            targetUserId = message.userId,
            emoji = emoji,
        )
        sendControl(control)
        applyControl(client.userId.value.orEmpty(), me, control)
    }

    private fun pinOn(target: ListenTogetherChatMessageHandle, pinned: Boolean) {
        val message = entryFor(target)?.message ?: return
        val control = ChatControlEvent(
            action = if (pinned) ChatControlEvent.ACTION_PIN else ChatControlEvent.ACTION_UNPIN,
            targetTimestamp = message.timestamp,
            targetUserId = message.userId,
        )
        sendControl(control)
        applyControl(client.userId.value.orEmpty(), client.username.value.orEmpty(), control)
    }

    private fun editOn(target: ListenTogetherChatMessageHandle, newText: String) {
        val message = entryFor(target)?.message ?: return
        if (message.userId != client.userId.value) return // only one's own messages can be edited
        val control = ChatControlEvent(
            action = ChatControlEvent.ACTION_EDIT,
            targetTimestamp = message.timestamp,
            targetUserId = message.userId,
            text = newText,
        )
        sendControl(control)
        applyControl(client.userId.value.orEmpty(), client.username.value.orEmpty(), control)
    }

    private fun deleteForEveryoneOn(target: ListenTogetherChatMessageHandle) {
        val message = entryFor(target)?.message ?: return
        if (message.userId != client.userId.value && !client.isHost) return
        val control = ChatControlEvent(
            action = ChatControlEvent.ACTION_DELETE,
            targetTimestamp = message.timestamp,
            targetUserId = message.userId,
        )
        sendControl(control)
        applyControl(client.userId.value.orEmpty(), client.username.value.orEmpty(), control)
    }

    /**
     * Removes the message [target] names from this user's view only. The write is not debounced: a delayed write could
     * race the leave-room wipe of the live list. When nothing is left to keep, the stored history is cleared.
     */
    private suspend fun hideForMe(target: ListenTogetherChatMessageHandle) {
        val current = _chatEntries.value
        if (current.none { it.handle == target }) return
        val remaining = current.filterNot { it.handle == target }
        publish(remaining)
        persistJob?.cancel()
        persist(remaining.map { it.message })
    }

    /** The live entry the handle names, or null for a handle from another owner or one that is no longer live. */
    private fun entryFor(target: ListenTogetherChatMessageHandle): ListenTogetherChatEntry? {
        if (target.ownerToken != ownerToken) return null
        return _chatEntries.value.firstOrNull { it.handle == target }
    }

    /** The live entry with this sender and timestamp, which is the room protocol's identity for a message. */
    private fun entryMatching(message: ChatMessagePayload): ListenTogetherChatEntry? =
        _chatEntries.value.firstOrNull { it.message.userId == message.userId && it.message.timestamp == message.timestamp }

    /** A new handle for a message entering the live list. Sequence numbers only grow, so no handle is ever reused. */
    private fun issueHandle(): ListenTogetherChatMessageHandle {
        val handle = ListenTogetherChatMessageHandle(
            ownerToken = ownerToken,
            sessionId = sessionId,
            sequence = nextSequence,
        )
        nextSequence += 1L
        return handle
    }

    /** Publishes the live list. Both views change in one call, so each handle and its message stay aligned. */
    private fun publish(entries: List<ListenTogetherChatEntry>) {
        _chatEntries.value = entries
        _chatMessages.value = entries.map { it.message }
    }

    private inline fun updateMessage(
        targetUserId: String,
        targetTimestamp: Long,
        transform: (ChatMessagePayload) -> ChatMessagePayload,
    ) {
        val entries = _chatEntries.value
        val index = entries.indexOfFirst { it.message.userId == targetUserId && it.message.timestamp == targetTimestamp }
        if (index == -1) return
        val updated = entries.toMutableList()
        // The handle stays with the entry, so a change to the message never moves the handle onto another message.
        updated[index] = updated[index].copy(message = transform(updated[index].message))
        publish(updated)
        scheduleChatPersist()
    }

    /** Runs the expiry sweep only while a typing indicator is visible, so an idle chat keeps no timer. */
    private fun ensureTypingTicker() {
        if (typingTicker?.isActive == true) return
        typingTicker = scope.launch {
            while (_typingUsers.value.isNotEmpty()) {
                delay(ListenTogetherChatTiming.TYPING_TICK_MS)
                val now = nowMs()
                val fresh = _typingUsers.value.filter { it.expiresAtMs > now }
                if (fresh.size != _typingUsers.value.size) _typingUsers.value = fresh
            }
        }
    }

    /**
     * Clears the live chat when the room is left or ends. The saved history stays and is restored on the next join. A new
     * session starts here, so every handle issued before now stops resolving, even though this owner lives on.
     */
    private fun clearLocal() {
        typingTicker?.cancel()
        typingTicker = null
        sessionId += 1L
        publish(emptyList())
        _unreadCount.value = 0
        _typingUsers.value = emptyList()
    }

    /** Sends a control event on the chat relay. Refused without a room and on protobuf servers, which have no chat relay. */
    private fun sendControl(event: ChatControlEvent) {
        if (!client.isInRoom || client.protocol.value == MessageFormat.PROTOBUF) return
        client.sendChat(ChatPayload(message = ListenTogetherEnvelope.encodeChatControl(event), replyTo = null))
    }

    /** Sends this user's custom picture to the room. A member without a chosen picture has nothing to send. */
    private suspend fun announceAvatar() {
        if (!client.isInRoom || client.protocol.value == MessageFormat.PROTOBUF) return
        val bytes = readLocalAvatar() ?: return
        // A picture above the receiving limit is dropped by every client, so it is not sent at all.
        if (bytes.isEmpty() || bytes.size > ListenTogetherEnvelope.MAX_AVATAR_BYTES) {
            onDiagnostic("Custom picture is outside the size limit")
            return
        }
        client.sendChat(ChatPayload(message = ListenTogetherEnvelope.encodeAvatar(bytes), replyTo = null))
    }

    private suspend fun readLocalAvatar(): ByteArray? =
        try {
            localAvatarBytes()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failed: Exception) {
            onDiagnostic("Custom picture could not be read")
            null
        }

    /**
     * Brings back the saved conversation with the members in the room now. Nothing restores while this user is alone. A
     * history written by an older scheme is discarded once. A history for another username is left alone.
     */
    private suspend fun restoreHistory(members: List<UserInfo>) {
        val myId = client.userId.value
        val others = members.filterNot { it.userId == myId }
        if (others.isEmpty()) return
        val username = client.username.value?.takeIf { it.isNotBlank() } ?: return
        val raw = readStored() ?: return
        val stored = ListenTogetherChatHistory.decode(raw) ?: return
        if (stored.username != username) return
        if (stored.version != PersistedChatHistory.CURRENT_VERSION) {
            onDiagnostic("Chat history from an older scheme was discarded")
            writeStored(null)
            return
        }
        val restored = ListenTogetherChatHistory.restorable(stored, username, myId, others)
        if (restored.isEmpty()) return
        // The live list is read after the store answers, so messages that arrived during the read are kept.
        val current = _chatEntries.value
        val merged = ListenTogetherChatHistory.mergeFresh(current.map { it.message }, restored) ?: return
        // A message that was already live keeps its entry and handle. Only restored messages receive new handles.
        val liveByKey = current.associateBy { it.message.username to it.message.timestamp }
        publish(
            merged.map { payload ->
                liveByKey[payload.username to payload.timestamp] ?: ListenTogetherChatEntry(issueHandle(), payload)
            },
        )
    }

    /** Debounced write of the live list. Only the latest state is written, after a short quiet period. */
    private fun scheduleChatPersist() {
        persistJob?.cancel()
        persistJob = scope.launch {
            delay(ListenTogetherChatTiming.PERSIST_DEBOUNCE_MS)
            // Leaving the room empties the live list before this write runs. An empty list must not erase a stored
            // conversation, so nothing is written in that case.
            if (_chatMessages.value.isEmpty()) return@launch
            persist(_chatMessages.value)
        }
    }

    private suspend fun persist(messages: List<ChatMessagePayload>) {
        val username = client.username.value ?: return
        writeStored(ListenTogetherChatHistory.encode(username, messages))
    }

    private suspend fun readStored(): String? =
        try {
            history.read()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failed: Exception) {
            onDiagnostic("Chat history could not be read")
            null
        }

    private suspend fun writeStored(document: String?) {
        try {
            history.write(document)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failed: Exception) {
            onDiagnostic("Chat history could not be written")
        }
    }

    init {
        eventJob = scope.launch { client.events.collect { handleEvent(it) } }
    }
}
