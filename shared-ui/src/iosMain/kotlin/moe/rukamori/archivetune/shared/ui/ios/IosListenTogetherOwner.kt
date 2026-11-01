package moe.rukamori.archivetune.shared.ui.ios

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.data.listentogether.ChatSongPickerOwner
import moe.rukamori.archivetune.shared.data.listentogether.JoinRequestPayload
import moe.rukamori.archivetune.shared.data.listentogether.ListenTogetherConnectionState
import moe.rukamori.archivetune.shared.data.listentogether.ListenTogetherEvent
import moe.rukamori.archivetune.shared.data.listentogether.ListenTogetherFailure
import moe.rukamori.archivetune.shared.data.listentogether.ListenTogetherLogEntry
import moe.rukamori.archivetune.shared.data.listentogether.ListenTogetherLogLevel
import moe.rukamori.archivetune.shared.data.listentogether.ListenTogetherNotice
import moe.rukamori.archivetune.shared.data.listentogether.ListenTogetherProtocol
import moe.rukamori.archivetune.shared.data.listentogether.ListenTogetherRole
import moe.rukamori.archivetune.shared.data.listentogether.ListenTogetherServers
import moe.rukamori.archivetune.shared.data.listentogether.RoomState
import moe.rukamori.archivetune.shared.data.listentogether.SuggestionReceivedPayload
import moe.rukamori.archivetune.shared.data.listentogether.TrackInfo
import moe.rukamori.archivetune.shared.data.platform.ios.IosListenTogether
import moe.rukamori.archivetune.shared.data.platform.ios.iosNowMillis
import moe.rukamori.archivetune.shared.data.repository.ContentRepositoryRouter
import moe.rukamori.archivetune.shared.domain.queue.UserQueuedKind
import moe.rukamori.archivetune.shared.domain.repository.ContentCredit
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.QueueController
import moe.rukamori.archivetune.shared.domain.settings.ListenTogetherServerOption
import moe.rukamori.archivetune.shared.domain.settings.ListenTogetherSettingsLogLevel
import moe.rukamori.archivetune.shared.domain.settings.ListenTogetherSettingsLogLine
import moe.rukamori.archivetune.shared.domain.settings.ListenTogetherSettingsRole
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.SettingsStore
import moe.rukamori.archivetune.shared.domain.settings.commitListenTogetherUsername
import moe.rukamori.archivetune.shared.domain.settings.listenTogetherServerUrl
import moe.rukamori.archivetune.shared.domain.settings.listenTogetherUsername
import moe.rukamori.archivetune.shared.ui.resources.*
import moe.rukamori.archivetune.shared.ui.screens.ListenRoomUi
import moe.rukamori.archivetune.shared.ui.screens.ListenTogetherUi
import moe.rukamori.archivetune.shared.ui.screens.RoomConnectionUi
import moe.rukamori.archivetune.shared.ui.screens.RoomJoinRequestUi
import moe.rukamori.archivetune.shared.ui.screens.RoomMemberUi
import moe.rukamori.archivetune.shared.ui.screens.RoomQueueRowUi
import moe.rukamori.archivetune.shared.ui.screens.RoomSuggestionUi
import moe.rukamori.archivetune.shared.ui.screens.commenttogether.CommentTogetherNativePorts
import moe.rukamori.archivetune.shared.ui.screens.commenttogether.CommentTogetherOwnerBinding
import org.jetbrains.compose.resources.getString
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.NSUUID

/**
 * The iOS Listen Together screens over the one [IosListenTogether] engine. The engine's driver keeps the player in step
 * with the room and its chat owner keeps the history. This owner turns the client's state and events into what the
 * room, settings and chat screens show, and runs the room actions. The presentation rules are the desktop owner's: the
 * username draft, the create and join waits, the hosting restriction, the connection messages and the room queue rows.
 *
 * Its flows run on [scope], the session scope, so the screens collect them like any other state. Messages are resolved
 * from the platform strings when they are shown.
 */
internal class IosListenTogetherOwner(
    private val scope: CoroutineScope,
    private val settings: SettingsStore,
    private val queue: QueueController,
    /** The typed content router the chat song picker searches, the same one the queue resolves through. */
    private val content: ContentRepositoryRouter,
    private val report: (String) -> Unit,
    private val engine: IosListenTogether,
) {
    /** The room client the screens read and drive. The engine's driver and chat use the same client. */
    val client = engine.client

    /** The room chat the chat screen binds. The engine owns it, so the history keeps recording while the chat is closed. */
    val chat = engine.chat

    private data class Local(
        val usernameDraft: String? = null,
        val roomCodeDraft: String = "",
        val creating: Boolean = false,
        val waitingForApproval: Boolean = false,
        val message: String? = null,
        val hostingAllowed: Boolean = true,
        val hostingRestriction: String? = null,
    )

    private data class Live(
        val connection: ListenTogetherConnectionState,
        val room: RoomState?,
        val requests: List<JoinRequestPayload>,
        val suggestions: List<SuggestionReceivedPayload>,
        val userId: String?,
    )

    private val local = MutableStateFlow(Local())
    private val logsClearedAtMs = MutableStateFlow(0L)

    private val live = combine(
        client.connectionState,
        client.roomState,
        client.pendingJoinRequests,
        client.pendingSuggestions,
        client.userId,
    ) { connection, room, requests, suggestions, userId -> Live(connection, room, requests, suggestions, userId) }

    /** The room screen's state. */
    val ui: StateFlow<ListenTogetherUi> = combine(live, local, settings.snapshots, client.username, chat.unreadCount) { liveState, localState, snapshot, joined, unread ->
        uiOf(liveState, localState, snapshot, joined, unread)
    }.stateIn(
        scope,
        SharingStarted.Eagerly,
        uiOf(Live(client.connectionState.value, null, emptyList(), emptyList(), null), Local(), settings.snapshots.value, null, chat.unreadCount.value),
    )

    /** The approved servers, as the settings page lists them. */
    val servers: List<ListenTogetherServerOption> =
        ListenTogetherServers.servers.map { ListenTogetherServerOption(it.name, it.url, it.location, it.operator) }

    val settingsRole: StateFlow<ListenTogetherSettingsRole> = combine(client.role, client.roomState) { role, room ->
        when {
            room == null -> ListenTogetherSettingsRole.NONE
            role == ListenTogetherRole.HOST -> ListenTogetherSettingsRole.HOST
            role == ListenTogetherRole.GUEST -> ListenTogetherSettingsRole.GUEST
            else -> ListenTogetherSettingsRole.NONE
        }
    }.stateIn(scope, SharingStarted.Eagerly, ListenTogetherSettingsRole.NONE)

    /** The connection log since the last clear. The client keeps its own bounded log, so a clear hides older lines here. */
    val logs: StateFlow<List<ListenTogetherSettingsLogLine>> = combine(client.logs, logsClearedAtMs) { entries, clearedAt ->
        entries.filter { it.timestampMs > clearedAt }.map { it.toLine() }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Whether the selected server documents a chat relay. The chat binding also ANDs the client's live wire protocol. */
    val serverChatSupported: StateFlow<Boolean> = settings.snapshots
        .map { snapshot -> chatSupportedBy(snapshot) }
        .stateIn(scope, SharingStarted.Eagerly, chatSupportedBy(settings.snapshots.value))

    /**
     * One chat-screen mount per composition, on the caller's UI scope, with its own song picker on that scope. The
     * picker's KDoc asks for the host's UI dispatcher, and the graph scope is a background one, so the picker's state
     * and searches stay on the thread the screen calls them from. The mount is closed when the composable leaves. The
     * chat history stays with the engine, so it keeps recording while the chat is closed. The client and chat accept
     * calls from any thread: they post the work to their own dispatcher.
     */
    fun commentTogether(native: CommentTogetherNativePorts, uiScope: CoroutineScope): IosCommentTogetherMount {
        val songPicker = ChatSongPickerOwner(content, uiScope, onShare = { track -> chat.shareTrack(track) })
        val binding = CommentTogetherOwnerBinding(uiScope, client, chat, songPicker, serverChatSupported, native)
        return IosCommentTogetherMount(binding, songPicker)
    }

    /**
     * The room's suggest-a-song picker, on the caller's UI scope like the chat picker. Only a guest suggests, as on
     * Android: the host plays a song directly. A pick sent while not a guest in a room is dropped, and [onSent] is told
     * whether the suggestion went out.
     */
    fun suggestionPicker(uiScope: CoroutineScope, onSent: (Boolean) -> Unit): ChatSongPickerOwner =
        ChatSongPickerOwner(content, uiScope, onShare = { track ->
            val guest = isGuestInRoom()
            if (guest) client.suggestTrack(track)
            onSent(guest)
        })

    init {
        scope.launch { client.events.collect { event -> guarded("event") { onEvent(event) } } }
    }

    fun connect() {
        local.update { it.copy(message = null, hostingAllowed = true, hostingRestriction = null) }
        client.connect()
    }

    fun disconnect() {
        local.update { it.copy(creating = false, waitingForApproval = false) }
        client.disconnect()
    }

    fun reconnect() {
        local.update { it.copy(message = null) }
        client.forceReconnect()
    }

    fun setUsername(value: String) = local.update { it.copy(usernameDraft = value) }

    fun setRoomCode(value: String) = local.update { it.copy(roomCodeDraft = value.uppercase()) }

    fun create(username: String) {
        if (username.isBlank()) return
        saveUsername(username)
        local.update { it.copy(creating = true, message = null) }
        client.createRoom(username)
    }

    fun join(roomCode: String, username: String) {
        if (username.isBlank() || roomCode.isBlank()) return
        saveUsername(username)
        local.update { it.copy(waitingForApproval = true, message = null) }
        client.joinRoom(roomCode.uppercase(), username)
    }

    /**
     * Joins the room an `archivetune://listen` or https invite link carries, with the stored username or the "Guest"
     * default the Android host uses for the same link — the desktop owner's joinFromLink path on this host.
     */
    fun joinFromLink(roomCode: String) {
        val username = settings.snapshots.value?.listenTogetherUsername().orEmpty().ifBlank { "Guest" }
        join(roomCode, username)
    }

    fun cancelJoin() {
        local.update { it.copy(creating = false, waitingForApproval = false) }
        client.leaveRoom()
    }

    /** Leaves the room. The driver resets its own sync state when the client reports that the room was left. */
    fun leave() = client.leaveRoom()

    fun approve(userId: String) = client.approveJoin(userId)

    fun reject(userId: String) = client.rejectJoin(userId)

    fun approveSuggestion(suggestionId: String) = client.approveSuggestion(suggestionId)

    fun rejectSuggestion(suggestionId: String) = client.rejectSuggestion(suggestionId)

    fun kick(member: RoomMemberUi, block: Boolean) {
        client.kickUser(member.id)
        if (block) client.blockUser(member.name)
    }

    fun transfer(member: RoomMemberUi) = client.transferHost(member.id)

    fun clearLogs() {
        logsClearedAtMs.value = client.logs.value.maxOfOrNull { it.timestampMs } ?: iosNowMillis()
    }

    /** Shows a message on the room page, such as a feature this host does not connect. */
    fun showMessage(message: String) = local.update { it.copy(message = message) }

    /** The web invite for the current room on the configured server, or null outside a room. */
    fun inviteLink(): String? = engine.inviteLink()

    fun start() {
        engine.start()
    }

    fun close() {
        engine.close()
    }

    /**
     * Plays a track shared in chat. A host queues it right after the current song and starts it. A guest sends it through
     * the suggestion flow every shared song takes, where a host approves it by default. False outside a room.
     */
    fun playSharedTrack(track: TrackInfo): Boolean = when {
        isHostInRoom() -> {
            scope.launch { guarded("shared track") { playHostTrack(track) } }
            true
        }
        isGuestInRoom() -> {
            client.suggestTrack(track)
            true
        }
        else -> false
    }

    private suspend fun playHostTrack(track: TrackInfo) {
        val occurrence = track.toOccurrence()
        queue.insert(listOf(occurrence), UserQueuedKind.PLAY_NEXT)
        queue.select(occurrence.occurrenceId)
    }

    private fun saveUsername(username: String) {
        scope.launch {
            try {
                settings.edit { commitListenTogetherUsername(username) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                report("Listen Together name was not saved: ${failure::class.simpleName}")
            }
        }
    }

    /** Only the messages and waits the screens show. The playback side of each event is the engine driver's. */
    private suspend fun onEvent(event: ListenTogetherEvent) {
        when (event) {
            is ListenTogetherEvent.RoomCreated -> local.update { it.copy(creating = false, message = null) }
            is ListenTogetherEvent.JoinApproved -> local.update { it.copy(waitingForApproval = false, message = null) }
            is ListenTogetherEvent.JoinRejected -> local.update { it.copy(waitingForApproval = false, message = event.reason) }
            is ListenTogetherEvent.Kicked -> local.update { it.copy(message = event.reason) }
            is ListenTogetherEvent.ServerError -> if (event.code == HOST_NOT_ALLOWED) {
                val restriction = getString(Res.string.ios_listen_together_host_not_allowed)
                local.update {
                    it.copy(creating = false, waitingForApproval = false, hostingAllowed = false, hostingRestriction = restriction)
                }
            } else {
                local.update { it.copy(creating = false, waitingForApproval = false, message = event.message.ifBlank { null }) }
            }
            is ListenTogetherEvent.ConnectionError -> {
                val text = failureText(event.failure)
                local.update { it.copy(creating = false, waitingForApproval = false, message = text) }
            }
            is ListenTogetherEvent.Notice -> {
                val text = when (event.notice) {
                    ListenTogetherNotice.ROOM_REQUEST_BUSY -> getString(Res.string.ios_listen_together_request_in_progress)
                    ListenTogetherNotice.CHAT_UNSUPPORTED_BY_SERVER -> getString(Res.string.m4c_unsupported_server)
                    ListenTogetherNotice.SUGGESTION_SENT -> getString(Res.string.ios_listen_together_suggestion_sent)
                }
                local.update { it.copy(message = text) }
            }
            ListenTogetherEvent.RoomLeft, ListenTogetherEvent.Disconnected ->
                local.update { it.copy(creating = false, waitingForApproval = false) }
            else -> Unit
        }
    }

    private suspend fun failureText(failure: ListenTogetherFailure): String? = when (failure) {
        ListenTogetherFailure.ConnectionTimeout -> getString(Res.string.ios_listen_together_connection_timeout)
        ListenTogetherFailure.RoomRequestTimeout -> getString(Res.string.ios_listen_together_room_request_timeout)
        ListenTogetherFailure.InvalidRoomResponse -> getString(Res.string.ios_listen_together_invalid_room_response)
        // The connection state already shows a failed or exhausted connection with its retry action.
        is ListenTogetherFailure.ConnectionFailed, is ListenTogetherFailure.ReconnectExhausted -> null
    }

    private fun chatSupportedBy(snapshot: PreferenceSnapshot?): Boolean {
        val url = snapshot?.listenTogetherServerUrl(ListenTogetherServers.defaultServerUrl)
            ?: ListenTogetherServers.defaultServerUrl
        return ListenTogetherServers.findByUrl(url)?.protocol != ListenTogetherProtocol.PROTOBUF
    }

    private fun isHostInRoom(): Boolean = client.role.value == ListenTogetherRole.HOST && client.roomState.value != null

    private fun isGuestInRoom(): Boolean = client.role.value == ListenTogetherRole.GUEST && client.roomState.value != null

    private suspend fun guarded(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            report("Listen Together $what failed: ${failure::class.simpleName}")
        }
    }

    private fun uiOf(live: Live, local: Local, snapshot: PreferenceSnapshot?, joined: String?, unread: Int): ListenTogetherUi {
        val room = live.room
        val selfId = live.userId
        return ListenTogetherUi(
            connection = when (live.connection) {
                ListenTogetherConnectionState.DISCONNECTED -> RoomConnectionUi.DISCONNECTED
                ListenTogetherConnectionState.CONNECTING, ListenTogetherConnectionState.RECONNECTING -> RoomConnectionUi.CONNECTING
                ListenTogetherConnectionState.CONNECTED -> RoomConnectionUi.CONNECTED
                ListenTogetherConnectionState.ERROR -> RoomConnectionUi.ERROR
            },
            username = local.usernameDraft ?: joined ?: snapshot?.listenTogetherUsername().orEmpty(),
            roomCode = local.roomCodeDraft,
            room = room?.let { state ->
                ListenRoomUi(
                    code = state.roomCode,
                    isHost = state.hostId == selfId,
                    members = state.users.map { user ->
                        RoomMemberUi(user.userId, user.username, user.isHost, user.userId == selfId, user.isConnected)
                    },
                    requests = live.requests.map { request ->
                        RoomJoinRequestUi(request.userId, request.username, busy = false)
                    },
                    suggestions = live.suggestions.filter { it.trackInfo.id.isNotBlank() }.map { suggestion ->
                        RoomSuggestionUi(
                            id = suggestion.suggestionId,
                            author = suggestion.fromUsername,
                            track = suggestion.trackInfo.toOccurrence("listen-suggestion:${suggestion.suggestionId}"),
                            busy = false,
                        )
                    },
                    queue = roomQueue(state),
                    unread = unread,
                )
            },
            hostingAllowed = local.hostingAllowed,
            hostingRestriction = local.hostingRestriction,
            creating = local.creating && room == null,
            waitingForApproval = local.waitingForApproval && room == null,
            error = local.message,
        )
    }

    /** A track without an id cannot name a content reference, so the server's malformed entries are left out of the rows. */
    private fun roomQueue(room: RoomState): List<RoomQueueRowUi> {
        val current = room.currentTrack?.takeIf { it.id.isNotBlank() }
        val tracks = listOfNotNull(current) + room.queue.filter { it.id.isNotBlank() && it.id != current?.id }
        return tracks.mapIndexed { index, track ->
            RoomQueueRowUi(track.toOccurrence("listen-room:$index:${track.id}"), current = index == 0 && current != null)
        }
    }

    private companion object {
        const val HOST_NOT_ALLOWED = "host_not_allowed"
    }
}

/**
 * One chat mount: its binding and the song picker the binding searches with. [close] stops the binding first, so no
 * projection publishes after the picker shuts down. The engine's chat owner is not closed here; the engine owns it.
 */
internal class IosCommentTogetherMount(
    val binding: CommentTogetherOwnerBinding,
    private val songPicker: ChatSongPickerOwner,
) {
    fun close() {
        binding.close()
        songPicker.close()
    }
}

/** A room track as a fresh queue occurrence. The queue refuses an id it already holds, so each load gets its own. */
private fun TrackInfo.toOccurrence(occurrenceId: String = "listen:${NSUUID().UUIDString}"): ContentOccurrence =
    ContentOccurrence(
        occurrenceId = occurrenceId,
        item = ContentItem(
            ref = ContentRef(ContentProvider.YOUTUBE.id, ContentKind.SONG, id),
            title = title.ifBlank { id },
            subtitle = artist,
            artworkUri = thumbnail?.takeIf { it.isNotBlank() },
            durationMs = duration.takeIf { it > 0L },
            album = album?.takeIf { it.isNotBlank() },
            artists = artist.takeIf { it.isNotBlank() }?.let { listOf(ContentCredit(it)) }.orEmpty(),
        ),
    )

/** Local wall-clock time for the connection log. NSDateFormatter is safe to share across threads. */
private val listenLogTimeFormat = NSDateFormatter().apply { dateFormat = "HH:mm:ss" }

private fun ListenTogetherLogEntry.toLine(): ListenTogetherSettingsLogLine =
    ListenTogetherSettingsLogLine(
        timeText = listenLogTimeFormat.stringFromDate(NSDate.dateWithTimeIntervalSince1970(timestampMs / 1000.0)),
        level = when (level) {
            ListenTogetherLogLevel.DEBUG -> ListenTogetherSettingsLogLevel.DEBUG
            ListenTogetherLogLevel.INFO -> ListenTogetherSettingsLogLevel.INFO
            ListenTogetherLogLevel.WARNING -> ListenTogetherSettingsLogLevel.WARNING
            ListenTogetherLogLevel.ERROR -> ListenTogetherSettingsLogLevel.ERROR
        },
        message = message,
        details = details,
    )
