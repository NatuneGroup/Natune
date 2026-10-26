/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.data.listentogether

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * The Listen Together connection and room client shared by every host.
 *
 * Every state change runs on [scope], which must dispatch on one thread, for example
 * `Dispatchers.Default.limitedParallelism(1)`. Public calls and socket callbacks only queue work, so they are safe
 * from any thread. Handlers never suspend in the middle of a change: store writes go through an ordered queue.
 * That keeps a bounded wait, a socket event and a host call from seeing a half-applied snapshot, and it lets a
 * callback from a replaced socket be recognised and dropped.
 */
class ListenTogetherClient(
    private val scope: CoroutineScope,
    private val sockets: ListenTogetherSocketFactory,
    private val codec: ListenTogetherMessageCodec,
    private val preferences: ListenTogetherPreferences,
    private val store: ListenTogetherStore,
    private val network: StateFlow<Boolean>,
    private val nowMs: () -> Long,
    private val random: () -> Double = { Random.nextDouble() },
) {
    /** Host calls and internal triggers, queued so they run in arrival order on the owner's thread. */
    private sealed interface Command {
        data object Connect : Command
        data object Disconnect : Command
        data object ForceReconnect : Command
        data class CreateRoom(val username: String) : Command
        data class JoinRoom(val roomCode: String, val username: String) : Command
        data object LeaveRoom : Command
        data class ApproveJoin(val userId: String) : Command
        data class RejectJoin(val userId: String, val reason: String?) : Command
        data class KickUser(val userId: String, val reason: String?) : Command
        data class TransferHost(val newHostId: String) : Command
        data class PlaybackAction(val payload: PlaybackActionPayload) : Command
        data class SendChat(val payload: ChatPayload) : Command
        data class BufferReady(val trackId: String) : Command
        data class SuggestTrack(val trackInfo: TrackInfo) : Command
        data class ApproveSuggestion(val suggestionId: String, val playImmediately: Boolean) : Command
        data class RejectSuggestion(val suggestionId: String, val reason: String?) : Command
        data object RequestSync : Command
        data class SetBlocked(val username: String, val blocked: Boolean) : Command
        data class Restore(val session: ListenTogetherSession?, val blocked: Set<String>) : Command
        data class NetworkChanged(val available: Boolean) : Command
        data class Socket(val attempt: Long, val event: SocketInbound) : Command
    }

    /** What a socket reported. The host's buffers are copied before they are queued, so they are never shared. */
    private sealed interface SocketInbound {
        data object Opened : SocketInbound
        class Frame(val bytes: ByteArray) : SocketInbound
        data class Closing(val code: Int, val reason: String) : SocketInbound
        data class Closed(val code: Int, val reason: String) : SocketInbound
        data class Failed(val detail: String?) : SocketInbound
    }

    /** One inbound frame after the codec has split its message type from its payload bytes. */
    private class InboundFrame(val type: String, val payload: ByteArray, val format: MessageFormat)

    /** A create or join that was sent. It is kept so a protocol-upgrade retry can tell whether it is still relevant. */
    private class SentRoomAction(
        val action: ListenTogetherPendingAction,
        val format: MessageFormat,
        val sentAtMs: Long,
        val connectionAttempt: Long,
        val roomRequestAttempt: Long,
        val socket: ListenTogetherSocket?,
    )

    private val inbox = Channel<Command>(Channel.UNLIMITED)
    private val persistence = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    // Both deadlines are confined to [scope]; see ListenTogetherRequestDeadline for why no lock is needed.
    private val connectionDeadline = ListenTogetherRequestDeadline(scope)
    private val roomActionDeadline = ListenTogetherRequestDeadline(scope)

    private val _connectionState = MutableStateFlow(ListenTogetherConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ListenTogetherConnectionState> = _connectionState.asStateFlow()

    private val _protocol = MutableStateFlow(MessageFormat.JSON)

    /** The wire format in use. A server that answers in protobuf upgrades the client at once. */
    val protocol: StateFlow<MessageFormat> = _protocol.asStateFlow()

    private val _roomState = MutableStateFlow<RoomState?>(null)
    val roomState: StateFlow<RoomState?> = _roomState.asStateFlow()

    private val _role = MutableStateFlow(ListenTogetherRole.NONE)
    val role: StateFlow<ListenTogetherRole> = _role.asStateFlow()

    private val _userId = MutableStateFlow<String?>(null)
    val userId: StateFlow<String?> = _userId.asStateFlow()

    private val _roomCode = MutableStateFlow<String?>(null)

    /** The room this client is bound to. It is kept while a session can still be resumed after a drop. */
    val roomCode: StateFlow<String?> = _roomCode.asStateFlow()

    private val _username = MutableStateFlow<String?>(null)

    /** The name this client created or joined with. Chat uses it to label the local user's own messages. */
    val username: StateFlow<String?> = _username.asStateFlow()

    private val _pendingJoinRequests = MutableStateFlow<List<JoinRequestPayload>>(emptyList())
    val pendingJoinRequests: StateFlow<List<JoinRequestPayload>> = _pendingJoinRequests.asStateFlow()

    private val _bufferingUsers = MutableStateFlow<List<String>>(emptyList())
    val bufferingUsers: StateFlow<List<String>> = _bufferingUsers.asStateFlow()

    private val _pendingSuggestions = MutableStateFlow<List<SuggestionReceivedPayload>>(emptyList())
    val pendingSuggestions: StateFlow<List<SuggestionReceivedPayload>> = _pendingSuggestions.asStateFlow()

    private val _blockedUsernames = MutableStateFlow<Set<String>>(emptySet())
    val blockedUsernames: StateFlow<Set<String>> = _blockedUsernames.asStateFlow()

    private val _logs = MutableStateFlow<List<ListenTogetherLogEntry>>(emptyList())
    val logs: StateFlow<List<ListenTogetherLogEntry>> = _logs.asStateFlow()

    private val _events = MutableSharedFlow<ListenTogetherEvent>(extraBufferCapacity = EVENT_BUFFER_CAPACITY)

    /**
     * One-shot reactions for the host. Events are not replayed, so a host subscribes before it connects. The state
     * flows above are the authoritative snapshot, and a dropped event never leaves them stale.
     */
    val events: SharedFlow<ListenTogetherEvent> = _events.asSharedFlow()

    private var socket: ListenTogetherSocket? = null
    private var pingJob: Job? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0
    private var sessionToken: String? = null
    private var wasHost = false
    private var pendingAction: ListenTogetherPendingAction? = null
    private var lastRoomAction: SentRoomAction? = null
    private var networkUp: Boolean = network.value

    init {
        scope.launch { for (command in inbox) runCommand(command) }
        scope.launch { for (write in persistence) runWrite(write) }
        scope.launch { network.collect { available -> post(Command.NetworkChanged(available)) } }
        scope.launch { restoreStoredState() }
    }

    /** True while a room is bound, including a room whose session is waiting to be resumed. */
    val isInRoom: Boolean
        get() = _roomState.value != null

    val isHost: Boolean
        get() = _role.value == ListenTogetherRole.HOST

    fun isUserBlocked(username: String): Boolean = username in _blockedUsernames.value

    // Public API. Each call queues work and returns at once, so it is safe to call from any thread.

    /** Opens the socket and resumes a saved session if there is one. Does nothing while already connected. */
    fun connect() = post(Command.Connect)

    /** Closes the socket on purpose and forgets the session. A later connect starts clean. */
    fun disconnect() = post(Command.Disconnect)

    /** Restarts the connection with a fresh retry budget, for a manual recovery after an error. */
    fun forceReconnect() = post(Command.ForceReconnect)

    /** Creates a room. The request is refused with [ListenTogetherNotice.ROOM_REQUEST_BUSY] while another is pending. */
    fun createRoom(username: String) = post(Command.CreateRoom(username))

    /** Joins a room by code. Same busy rule as [createRoom]. */
    fun joinRoom(roomCode: String, username: String) = post(Command.JoinRoom(roomCode, username))

    fun leaveRoom() = post(Command.LeaveRoom)

    /** Host only. A caller that is not the host is logged and ignored. */
    fun approveJoin(userId: String) = post(Command.ApproveJoin(userId))

    /** Host only. */
    fun rejectJoin(userId: String, reason: String? = null) = post(Command.RejectJoin(userId, reason))

    /** Host only. */
    fun kickUser(userId: String, reason: String? = null) = post(Command.KickUser(userId, reason))

    /** Host only. */
    fun transferHost(newHostId: String) = post(Command.TransferHost(newHostId))

    /** Host only. The local room snapshot is updated when the event returns, not when the call is made. */
    fun sendPlaybackAction(payload: PlaybackActionPayload) = post(Command.PlaybackAction(payload))

    /**
     * Sends a chat frame built by the chat owner. Chat is refused on protobuf-only servers, because they have no
     * chat relay. Chat envelopes are built by the chat owner, not here.
     */
    fun sendChat(payload: ChatPayload) = post(Command.SendChat(payload))

    fun sendBufferReady(trackId: String) = post(Command.BufferReady(trackId))

    /** Guest only. The host is never a suggester. */
    fun suggestTrack(trackInfo: TrackInfo) = post(Command.SuggestTrack(trackInfo))

    /** Host only. [playImmediately] is passed back to the host in [ListenTogetherEvent.LocalSuggestionApproved]. */
    fun approveSuggestion(suggestionId: String, playImmediately: Boolean = false) =
        post(Command.ApproveSuggestion(suggestionId, playImmediately))

    /** Host only. */
    fun rejectSuggestion(suggestionId: String, reason: String? = null) =
        post(Command.RejectSuggestion(suggestionId, reason))

    /** Asks the server for the current playback state, for a guest that has fallen out of step. */
    fun requestSync() = post(Command.RequestSync)

    /** Blocks a user locally. Their join requests and suggestions are dropped, and the list is saved. */
    fun blockUser(username: String) = post(Command.SetBlocked(username, blocked = true))

    fun unblockUser(username: String) = post(Command.SetBlocked(username, blocked = false))

    private fun post(command: Command) {
        inbox.trySend(command)
    }

    /**
     * Runs one queued command. A failure in one handler is logged and the loop keeps going, so one bad host call or
     * frame cannot silence the owner for the rest of the process. Cancellation still ends the loop.
     */
    private fun runCommand(command: Command) {
        try {
            dispatch(command)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(ListenTogetherLogLevel.ERROR, "Listen Together command failed", e.message)
        }
    }

    private fun dispatch(command: Command) {
        when (command) {
            Command.Connect -> doConnect()
            Command.Disconnect -> doDisconnect()
            Command.ForceReconnect -> doForceReconnect()
            is Command.CreateRoom -> doRoomRequest(ListenTogetherPendingAction.CreateRoom(command.username))
            is Command.JoinRoom ->
                doRoomRequest(ListenTogetherPendingAction.JoinRoom(command.roomCode, command.username))
            Command.LeaveRoom -> doLeaveRoom()
            is Command.ApproveJoin -> doApproveJoin(command.userId)
            is Command.RejectJoin -> doRejectJoin(command.userId, command.reason)
            is Command.KickUser -> doKickUser(command.userId, command.reason)
            is Command.TransferHost -> doTransferHost(command.newHostId)
            is Command.PlaybackAction -> doPlaybackAction(command.payload)
            is Command.SendChat -> doSendChat(command.payload)
            is Command.BufferReady -> sendMessage(MessageTypes.BUFFER_READY, BufferReadyPayload(command.trackId))
            is Command.SuggestTrack -> doSuggestTrack(command.trackInfo)
            is Command.ApproveSuggestion -> doApproveSuggestion(command.suggestionId, command.playImmediately)
            is Command.RejectSuggestion -> doRejectSuggestion(command.suggestionId, command.reason)
            Command.RequestSync -> doRequestSync()
            is Command.SetBlocked -> doSetBlocked(command.username, command.blocked)
            is Command.Restore -> doRestore(command.session, command.blocked)
            is Command.NetworkChanged -> doNetworkChanged(command.available)
            is Command.Socket -> doSocket(command.attempt, command.event)
        }
    }

    // Connection lifecycle

    private fun doConnect() {
        val current = _connectionState.value
        if (current == ListenTogetherConnectionState.CONNECTED ||
            current == ListenTogetherConnectionState.CONNECTING
        ) {
            log(ListenTogetherLogLevel.WARNING, "Already connected or connecting")
            return
        }
        reconnectJob?.cancel()
        reconnectJob = null
        val attempt = connectionDeadline.start(ListenTogetherTiming.CONNECTION_DEADLINE_MS) {
            failPendingConnection(ListenTogetherFailure.ConnectionTimeout)
        }
        cancelSocket()
        _connectionState.value = ListenTogetherConnectionState.CONNECTING
        val serverUrl = resolveServerUrl()
        log(ListenTogetherLogLevel.INFO, "Connecting to server", serverUrl)

        // The Meowery is protobuf-only and answers JSON frames with invalid_message. Starting in the server's own
        // protocol avoids a rejected first frame, instead of relying on the reactive upgrade below.
        val serverProtocol = ListenTogetherServers.findByUrl(serverUrl)?.protocol ?: ListenTogetherProtocol.JSON
        val protobuf = serverProtocol == ListenTogetherProtocol.PROTOBUF
        applyFormat(if (protobuf) MessageFormat.PROTOBUF else MessageFormat.JSON, compression = protobuf)
        log(ListenTogetherLogLevel.INFO, "Codec configured", "${codec.format.name}, compression=${codec.compressionEnabled}")

        // A throw from open is a failed attempt, not a crash. An invalid URL then follows the normal retry rules.
        socket = try {
            sockets.open(serverUrl, AttemptListener(attempt))
        } catch (e: Exception) {
            connectionDeadline.cancel()
            log(ListenTogetherLogLevel.ERROR, "Connection failure", e.message)
            handleConnectionFailure(e.message)
            null
        }
    }

    private fun doDisconnect() {
        connectionDeadline.cancel()
        roomActionDeadline.cancel()
        reconnectJob?.cancel()
        reconnectJob = null
        log(ListenTogetherLogLevel.INFO, "Disconnecting from server")
        pingJob?.cancel()
        pingJob = null
        closeSocket(NORMAL_CLOSE, "User disconnected")
        _connectionState.value = ListenTogetherConnectionState.DISCONNECTED

        sessionToken = null
        _roomCode.value = null
        _username.value = null
        pendingAction = null
        lastRoomAction = null
        _roomState.value = null
        _role.value = ListenTogetherRole.NONE
        _userId.value = null
        _pendingJoinRequests.value = emptyList()
        _bufferingUsers.value = emptyList()
        clearPersistedSession()
        reconnectAttempts = 0
        emit(ListenTogetherEvent.Disconnected)
    }

    private fun doForceReconnect() {
        connectionDeadline.cancel()
        roomActionDeadline.cancel()
        pendingAction = null
        lastRoomAction = null
        reconnectJob?.cancel()
        reconnectJob = null
        log(ListenTogetherLogLevel.INFO, "Forcing reconnection to server")
        reconnectAttempts = 0
        closeSocket(NORMAL_CLOSE, "Forcing reconnection")
        _connectionState.value = ListenTogetherConnectionState.DISCONNECTED
        reconnectJob = scope.launch {
            delay(ListenTogetherTiming.FORCED_RECONNECT_DELAY_MS)
            post(Command.Connect)
        }
    }

    /** Reports a bounded failure and tears the socket down; the session is left for the user to resume or drop. */
    private fun failPendingConnection(failure: ListenTogetherFailure) {
        connectionDeadline.cancel()
        roomActionDeadline.cancel()
        reconnectJob?.cancel()
        reconnectJob = null
        pingJob?.cancel()
        pingJob = null
        pendingAction = null
        lastRoomAction = null
        cancelSocket()
        _connectionState.value = ListenTogetherConnectionState.ERROR
        emit(ListenTogetherEvent.ConnectionError(failure))
    }

    private fun handleDisconnect() {
        pingJob?.cancel()
        pingJob = null
        // The room snapshot is kept on purpose: a drop may be followed by a reconnect that resumes the same room.
        _connectionState.value = ListenTogetherConnectionState.DISCONNECTED
        _pendingJoinRequests.value = emptyList()
        _bufferingUsers.value = emptyList()
        if (sessionToken != null && _roomState.value != null) {
            log(ListenTogetherLogLevel.INFO, "Connection lost, will attempt to reconnect")
            handleConnectionFailure("Connection lost")
        } else {
            emit(ListenTogetherEvent.Disconnected)
        }
    }

    /**
     * Schedules a retry while the budget and the network allow one, or settles into [ListenTogetherConnectionState.ERROR].
     * A session that exists is kept for a manual reconnect, so the user does not lose the room to a bad network.
     */
    private fun handleConnectionFailure(detail: String?) {
        cancelSocket()
        pingJob?.cancel()
        pingJob = null
        val shouldReconnect = sessionToken != null || _roomState.value != null || pendingAction != null

        if (!networkUp) {
            roomActionDeadline.cancel()
            pendingAction = null
            lastRoomAction = null
            log(ListenTogetherLogLevel.WARNING, "Connection failure, waiting for network", detail)
            _connectionState.value = ListenTogetherConnectionState.DISCONNECTED
            return
        }

        val maxAttempts = ListenTogetherTiming.MAX_RECONNECT_ATTEMPTS
        if (reconnectAttempts < maxAttempts && shouldReconnect) {
            reconnectAttempts++
            _connectionState.value = ListenTogetherConnectionState.RECONNECTING
            val attemptNumber = reconnectAttempts
            val delayMs = backoffDelayMs(attemptNumber)
            val delaySeconds = delayMs / 1000
            log(
                ListenTogetherLogLevel.INFO,
                "Attempting reconnect",
                "Attempt $attemptNumber/$maxAttempts, waiting ${delaySeconds}s, reason: ${detail ?: "unknown"}",
            )
            reconnectJob?.cancel()
            reconnectJob = scope.launch {
                emit(ListenTogetherEvent.Reconnecting(attemptNumber, maxAttempts))
                delay(delayMs)
                // A user action may have moved the state on while the timer ran; only a retry still owed runs.
                val state = _connectionState.value
                if (state == ListenTogetherConnectionState.RECONNECTING ||
                    state == ListenTogetherConnectionState.DISCONNECTED
                ) {
                    log(ListenTogetherLogLevel.INFO, "Reconnecting after backoff", "Delay was ${delaySeconds}s")
                    doConnect()
                }
            }
        } else {
            roomActionDeadline.cancel()
            pendingAction = null
            lastRoomAction = null
            _connectionState.value = ListenTogetherConnectionState.ERROR
            if (sessionToken != null) {
                log(
                    ListenTogetherLogLevel.ERROR,
                    "Reconnection failed",
                    "Max attempts reached, but session preserved for manual reconnect",
                )
                emit(
                    ListenTogetherEvent.ConnectionError(
                        ListenTogetherFailure.ReconnectExhausted(reconnectAttempts, detail),
                    ),
                )
            } else {
                _roomCode.value = null
                _username.value = null
                _roomState.value = null
                _role.value = ListenTogetherRole.NONE
                clearPersistedSession()
                emit(ListenTogetherEvent.ConnectionError(ListenTogetherFailure.ConnectionFailed(detail)))
            }
        }
    }

    /** Exponential backoff from one second, capped at two minutes, with up to 20 percent jitter against a thundering herd. */
    private fun backoffDelayMs(attempt: Int): Long {
        val exponential = ListenTogetherTiming.INITIAL_RECONNECT_DELAY_MS *
            (2 shl minOf(attempt - 1, RECONNECT_BACKOFF_MAX_SHIFT))
        val capped = minOf(exponential, ListenTogetherTiming.MAX_RECONNECT_DELAY_MS)
        val jitter = (capped * JITTER_FRACTION * random()).toLong()
        return capped + jitter
    }

    private fun doNetworkChanged(available: Boolean) {
        val previous = networkUp
        networkUp = available
        if (available && !previous) {
            log(ListenTogetherLogLevel.INFO, "Network restored, checking if reconnection needed")
            val state = _connectionState.value
            val hasWork = sessionToken != null || _roomState.value != null || pendingAction != null
            if ((state == ListenTogetherConnectionState.ERROR || state == ListenTogetherConnectionState.DISCONNECTED) &&
                hasWork
            ) {
                log(ListenTogetherLogLevel.INFO, "Network restored, triggering reconnection")
                reconnectAttempts = 0
                doConnect()
            }
        } else if (!available && previous) {
            log(ListenTogetherLogLevel.WARNING, "Network lost")
        }
    }

    private fun doSocket(attempt: Long, event: SocketInbound) {
        // A callback from a socket that has been replaced or cancelled arrives late. It must not alter the new one.
        if (!connectionDeadline.isCurrent(attempt)) return
        when (event) {
            SocketInbound.Opened -> onSocketOpened()
            is SocketInbound.Frame -> handleMessage(event.bytes)
            is SocketInbound.Closing -> {
                log(ListenTogetherLogLevel.INFO, "Server closing connection", "Code: ${event.code}, Reason: ${event.reason}")
                closeSocket(NORMAL_CLOSE, "")
            }
            is SocketInbound.Closed -> {
                connectionDeadline.cancel()
                socket = null
                log(ListenTogetherLogLevel.INFO, "Connection closed", "Code: ${event.code}, Reason: ${event.reason}")
                handleDisconnect()
            }
            is SocketInbound.Failed -> {
                connectionDeadline.cancel()
                log(ListenTogetherLogLevel.ERROR, "Connection failure", event.detail)
                handleConnectionFailure(event.detail)
            }
        }
    }

    private fun onSocketOpened() {
        connectionDeadline.complete()
        log(ListenTogetherLogLevel.INFO, "Connected to server")
        _connectionState.value = ListenTogetherConnectionState.CONNECTED
        reconnectAttempts = 0
        startPingJob()

        val token = sessionToken
        val room = _roomCode.value
        if (token != null && room != null) {
            log(ListenTogetherLogLevel.INFO, "Attempting to reconnect to previous session", "Room: $room")
            sendMessage(MessageTypes.RECONNECT, ReconnectPayload(token))
        } else {
            executePendingAction()
        }
    }

    /** Heartbeats keep NAT and proxy idle timers from closing a quiet room socket. */
    private fun startPingJob() {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (true) {
                delay(ListenTogetherTiming.PING_INTERVAL_MS)
                sendMessage(MessageTypes.PING, null)
            }
        }
    }

    private fun resolveServerUrl(): String {
        val saved = preferences.serverUrl()
        return if (ListenTogetherServers.findByUrl(saved) != null) saved else ListenTogetherServers.defaultServerUrl
    }

    private fun applyFormat(format: MessageFormat, compression: Boolean) {
        codec.format = format
        codec.compressionEnabled = compression
        _protocol.value = format
    }

    private fun cancelSocket() {
        val current = socket ?: return
        socket = null
        try {
            current.cancel()
        } catch (e: Exception) {
            log(ListenTogetherLogLevel.DEBUG, "Error cancelling socket", e.message)
        }
    }

    private fun closeSocket(code: Int, reason: String) {
        val current = socket ?: return
        socket = null
        try {
            current.close(code, reason)
        } catch (e: Exception) {
            log(ListenTogetherLogLevel.DEBUG, "Error closing socket", e.message)
        }
    }

    /**
     * The listener for one connection attempt. It only queues the callback. The owner drops it if the attempt is no
     * longer current, so the host engine can call from any thread without touching owner state.
     */
    private inner class AttemptListener(private val attempt: Long) : ListenTogetherSocketListener {
        override fun onOpen() = post(Command.Socket(attempt, SocketInbound.Opened))

        override fun onMessage(frame: ByteArray) =
            post(Command.Socket(attempt, SocketInbound.Frame(frame.copyOf())))

        override fun onClosing(code: Int, reason: String) =
            post(Command.Socket(attempt, SocketInbound.Closing(code, reason)))

        override fun onClosed(code: Int, reason: String) =
            post(Command.Socket(attempt, SocketInbound.Closed(code, reason)))

        override fun onFailure(cause: Throwable?) =
            post(Command.Socket(attempt, SocketInbound.Failed(cause?.message)))
    }

    // Room requests

    private fun doRoomRequest(action: ListenTogetherPendingAction) {
        val request = roomActionDeadline.startIfIdle(ListenTogetherTiming.ROOM_ACTION_DEADLINE_MS) {
            failPendingConnection(ListenTogetherFailure.RoomRequestTimeout)
        }
        if (request == null) {
            log(ListenTogetherLogLevel.WARNING, "Room request ignored: another room request is in progress")
            emit(ListenTogetherEvent.Notice(ListenTogetherNotice.ROOM_REQUEST_BUSY))
            return
        }
        // A new create or join replaces any saved session on purpose, so the server cannot resume the old room.
        clearPersistedSession()
        sessionToken = null
        _roomCode.value = null
        wasHost = false
        _username.value = when (action) {
            is ListenTogetherPendingAction.CreateRoom -> action.username
            is ListenTogetherPendingAction.JoinRoom -> action.username
        }

        if (_connectionState.value == ListenTogetherConnectionState.CONNECTED) {
            executeRoomAction(action)
        } else {
            log(ListenTogetherLogLevel.INFO, "Not connected, queueing room action")
            pendingAction = action
            val state = _connectionState.value
            if (state == ListenTogetherConnectionState.DISCONNECTED || state == ListenTogetherConnectionState.ERROR) {
                doConnect()
            }
        }
    }

    private fun executePendingAction() {
        val action = pendingAction ?: return
        pendingAction = null
        executeRoomAction(action)
    }

    private fun executeRoomAction(action: ListenTogetherPendingAction) {
        val avatarIndex = preferences.avatarIndex()
        lastRoomAction = SentRoomAction(
            action = action,
            format = codec.format,
            sentAtMs = nowMs(),
            connectionAttempt = connectionDeadline.currentAttempt(),
            roomRequestAttempt = roomActionDeadline.currentAttempt(),
            socket = socket,
        )
        when (action) {
            is ListenTogetherPendingAction.CreateRoom -> {
                log(ListenTogetherLogLevel.INFO, "Executing pending create room", action.username)
                sendMessage(MessageTypes.CREATE_ROOM, CreateRoomPayload(action.username, avatarIndex))
            }
            is ListenTogetherPendingAction.JoinRoom -> {
                log(ListenTogetherLogLevel.INFO, "Executing pending join room", "${action.roomCode} as ${action.username}")
                sendMessage(
                    MessageTypes.JOIN_ROOM,
                    JoinRoomPayload(action.roomCode.uppercase(), action.username, avatarIndex),
                )
            }
        }
    }

    /**
     * Covers a server whose protocol was misconfigured: a create or join sent as JSON, then answered in protobuf.
     * The reactive upgrade in [handleMessage] has already switched the codec, so the same action is sent once more in
     * protobuf. The retry is bounded to the same socket, the same attempt, and a request that still has no answer.
     */
    private fun maybeRetryRoomActionAfterProtocolUpgrade() {
        val sent = lastRoomAction ?: return
        lastRoomAction = null

        val sentAsJson = sent.format == MessageFormat.JSON
        val nowUpgraded = codec.format == MessageFormat.PROTOBUF
        val recent = nowMs() - sent.sentAtMs < ListenTogetherTiming.ROOM_ACTION_RETRY_WINDOW_MS
        if (!sentAsJson || !nowUpgraded || !recent) return

        val sentSocket = sent.socket ?: return
        log(ListenTogetherLogLevel.WARNING, "Create/join was rejected as JSON after a protobuf upgrade", "Retrying in protobuf")
        scope.launch {
            delay(ListenTogetherTiming.ROOM_ACTION_RETRY_DELAY_MS)
            connectionDeadline.runIfCurrent(sent.connectionAttempt) {
                roomActionDeadline.runIfCurrent(sent.roomRequestAttempt, requirePending = true) {
                    if (socket === sentSocket &&
                        _connectionState.value == ListenTogetherConnectionState.CONNECTED &&
                        codec.format == MessageFormat.PROTOBUF
                    ) {
                        executeRoomAction(sent.action)
                    }
                }
            }
        }
    }

    private fun doLeaveRoom() {
        roomActionDeadline.cancel()
        sendMessage(MessageTypes.LEAVE_ROOM, null)
        sessionToken = null
        _roomCode.value = null
        _username.value = null
        pendingAction = null
        _roomState.value = null
        _role.value = ListenTogetherRole.NONE
        _userId.value = null
        _pendingJoinRequests.value = emptyList()
        _bufferingUsers.value = emptyList()
        clearPersistedSession()
        emit(ListenTogetherEvent.RoomLeft)
    }

    // Host actions. Each checks the role at the moment it runs, not when the host called it.

    private fun doApproveJoin(userId: String) {
        if (_role.value != ListenTogetherRole.HOST) {
            log(ListenTogetherLogLevel.ERROR, "Cannot approve join", "Not host")
            return
        }
        sendMessage(MessageTypes.APPROVE_JOIN, ApproveJoinPayload(userId))
    }

    private fun doRejectJoin(userId: String, reason: String?) {
        if (_role.value != ListenTogetherRole.HOST) {
            log(ListenTogetherLogLevel.ERROR, "Cannot reject join", "Not host")
            return
        }
        sendMessage(MessageTypes.REJECT_JOIN, RejectJoinPayload(userId, reason))
        _pendingJoinRequests.value = _pendingJoinRequests.value.filter { it.userId != userId }
    }

    private fun doKickUser(userId: String, reason: String?) {
        if (_role.value != ListenTogetherRole.HOST) {
            log(ListenTogetherLogLevel.ERROR, "Cannot kick user", "Not host")
            return
        }
        sendMessage(MessageTypes.KICK_USER, KickUserPayload(userId, reason))
    }

    private fun doTransferHost(newHostId: String) {
        if (_role.value != ListenTogetherRole.HOST) {
            log(ListenTogetherLogLevel.ERROR, "Cannot transfer host", "Not host")
            return
        }
        sendMessage(MessageTypes.TRANSFER_HOST, TransferHostPayload(newHostId))
    }

    private fun doPlaybackAction(payload: PlaybackActionPayload) {
        if (_role.value != ListenTogetherRole.HOST) {
            log(ListenTogetherLogLevel.ERROR, "Cannot control playback", "Not host")
            return
        }
        sendMessage(MessageTypes.PLAYBACK_ACTION, payload)
    }

    private fun doSendChat(payload: ChatPayload) {
        if (_roomState.value == null) {
            log(ListenTogetherLogLevel.ERROR, "Cannot send chat message", "Not in room")
            return
        }
        if (codec.format == MessageFormat.PROTOBUF) {
            log(ListenTogetherLogLevel.WARNING, "Chat is not supported by this server")
            emit(ListenTogetherEvent.Notice(ListenTogetherNotice.CHAT_UNSUPPORTED_BY_SERVER))
            return
        }
        sendMessage(MessageTypes.CHAT, payload)
    }

    private fun doSuggestTrack(trackInfo: TrackInfo) {
        if (_roomState.value == null) {
            log(ListenTogetherLogLevel.ERROR, "Cannot suggest track", "Not in room")
            return
        }
        if (_role.value == ListenTogetherRole.HOST) {
            log(ListenTogetherLogLevel.WARNING, "Host should not suggest tracks")
            return
        }
        sendMessage(MessageTypes.SUGGEST_TRACK, SuggestTrackPayload(trackInfo))
        emit(ListenTogetherEvent.Notice(ListenTogetherNotice.SUGGESTION_SENT))
    }

    private fun doApproveSuggestion(suggestionId: String, playImmediately: Boolean) {
        if (_role.value != ListenTogetherRole.HOST) {
            log(ListenTogetherLogLevel.ERROR, "Cannot approve suggestion", "Not host")
            return
        }
        // The suggestion is read before the approval leaves, so the local player still has its full track.
        val suggestion = _pendingSuggestions.value.find { it.suggestionId == suggestionId }
        sendMessage(MessageTypes.APPROVE_SUGGESTION, ApproveSuggestionPayload(suggestionId))
        if (suggestion != null) {
            emit(ListenTogetherEvent.LocalSuggestionApproved(suggestion, playImmediately))
        }
        _pendingSuggestions.value = _pendingSuggestions.value.filter { it.suggestionId != suggestionId }
    }

    private fun doRejectSuggestion(suggestionId: String, reason: String?) {
        if (_role.value != ListenTogetherRole.HOST) {
            log(ListenTogetherLogLevel.ERROR, "Cannot reject suggestion", "Not host")
            return
        }
        sendMessage(MessageTypes.REJECT_SUGGESTION, RejectSuggestionPayload(suggestionId, reason))
        _pendingSuggestions.value = _pendingSuggestions.value.filter { it.suggestionId != suggestionId }
    }

    private fun doRequestSync() {
        if (_roomState.value == null) {
            log(ListenTogetherLogLevel.ERROR, "Cannot request sync", "Not in room")
            return
        }
        log(ListenTogetherLogLevel.INFO, "Requesting sync state from server")
        sendMessage(MessageTypes.REQUEST_SYNC, null)
    }

    private fun doSetBlocked(username: String, blocked: Boolean) {
        val updated = if (blocked) _blockedUsernames.value + username else _blockedUsernames.value - username
        _blockedUsernames.value = updated
        if (blocked) {
            // A user blocked while waiting is dropped at once, so no stale request can still be approved.
            _pendingJoinRequests.value = _pendingJoinRequests.value.filter { it.username !in updated }
            _pendingSuggestions.value = _pendingSuggestions.value.filter { it.fromUsername !in updated }
        }
        enqueueWrite { store.saveBlockedUsernames(updated) }
        log(ListenTogetherLogLevel.INFO, if (blocked) "User blocked" else "User unblocked", username)
    }

    // Inbound messages

    private fun handleMessage(frame: ByteArray) {
        log(ListenTogetherLogLevel.DEBUG, "Received message", "${frame.size} bytes")
        try {
            val detected = ListenTogetherMessageCodec.detectMessageFormat(frame)
            if (detected == MessageFormat.PROTOBUF && codec.format == MessageFormat.JSON) {
                applyFormat(MessageFormat.PROTOBUF, compression = true)
                log(ListenTogetherLogLevel.INFO, "Upgraded to Protobuf", "with compression")
            }
            val (type, payload) = codec.decode(frame)
            val inbound = InboundFrame(type, payload, detected)
            when (type) {
                MessageTypes.ROOM_CREATED -> handleRoomCreated(inbound)
                MessageTypes.JOIN_REQUEST -> handleJoinRequest(inbound)
                MessageTypes.JOIN_APPROVED -> handleJoinApproved(inbound)
                MessageTypes.JOIN_REJECTED -> handleJoinRejected(inbound)
                MessageTypes.USER_JOINED -> handleUserJoined(inbound)
                MessageTypes.USER_LEFT -> handleUserLeft(inbound)
                MessageTypes.HOST_CHANGED -> handleHostChanged(inbound)
                MessageTypes.KICKED -> handleKicked(inbound)
                MessageTypes.SYNC_PLAYBACK -> handlePlaybackSync(inbound)
                MessageTypes.BUFFER_WAIT -> handleBufferWait(inbound)
                MessageTypes.BUFFER_COMPLETE -> handleBufferComplete(inbound)
                MessageTypes.SYNC_STATE -> handleSyncState(inbound)
                MessageTypes.SUGGESTION_RECEIVED -> handleSuggestionReceived(inbound)
                MessageTypes.SUGGESTION_APPROVED -> handleSuggestionApproved(inbound)
                MessageTypes.SUGGESTION_REJECTED -> handleSuggestionRejected(inbound)
                MessageTypes.ERROR -> handleServerError(inbound)
                MessageTypes.PONG -> log(ListenTogetherLogLevel.DEBUG, "Pong received")
                MessageTypes.RECONNECTED -> handleReconnected(inbound)
                MessageTypes.USER_RECONNECTED -> handleUserReconnected(inbound)
                MessageTypes.USER_DISCONNECTED -> handleUserDisconnected(inbound)
                MessageTypes.CHAT -> handleChat(inbound)
                else -> log(ListenTogetherLogLevel.WARNING, "Unknown message type", type)
            }
        } catch (e: Exception) {
            log(ListenTogetherLogLevel.ERROR, "Error parsing message", e.message)
        }
    }

    private inline fun <reified T> InboundFrame.decode(): T? = codec.decodePayload(type, payload, format) as? T

    private fun handleRoomCreated(frame: InboundFrame) {
        val payload = roomActionDeadline.completeWithPayload(frame.decode<RoomCreatedPayload>())
        if (payload == null) {
            failPendingConnection(ListenTogetherFailure.InvalidRoomResponse)
            return
        }
        lastRoomAction = null
        _userId.value = payload.userId
        _role.value = ListenTogetherRole.HOST
        sessionToken = payload.sessionToken
        _roomCode.value = payload.roomCode
        wasHost = true
        _roomState.value = RoomState(
            roomCode = payload.roomCode,
            hostId = payload.userId,
            users = listOf(
                UserInfo(
                    userId = payload.userId,
                    username = _username.value.orEmpty(),
                    isHost = true,
                    avatarIndex = preferences.avatarIndex(),
                ),
            ),
            isPlaying = false,
            position = 0L,
            lastUpdate = nowMs(),
            volume = 1f,
        )
        persistSession()
        log(ListenTogetherLogLevel.INFO, "Room created", "Code: ${payload.roomCode}")
        emit(ListenTogetherEvent.RoomCreated(payload.roomCode, payload.userId))
    }

    private fun handleJoinRequest(frame: InboundFrame) {
        val payload = frame.decode<JoinRequestPayload>() ?: return
        if (isUserBlocked(payload.username)) {
            log(ListenTogetherLogLevel.INFO, "Join request from blocked user ignored", "User: ${payload.username}")
            doRejectJoin(payload.userId, "You are blocked")
            return
        }
        _pendingJoinRequests.value = _pendingJoinRequests.value + payload
        log(ListenTogetherLogLevel.INFO, "Join request received", "User: ${payload.username}")
        val isHost = _role.value == ListenTogetherRole.HOST
        val autoApprove = preferences.autoApproveJoinRequests()
        if (isHost && autoApprove) {
            log(ListenTogetherLogLevel.INFO, "Auto-approving join request", "User: ${payload.username}")
            doApproveJoin(payload.userId)
        }
        emit(
            ListenTogetherEvent.JoinRequestReceived(
                request = payload,
                awaitingHostDecision = isHost && !autoApprove,
            ),
        )
    }

    private fun handleJoinApproved(frame: InboundFrame) {
        val payload = roomActionDeadline.completeWithPayload(frame.decode<JoinApprovedPayload>())
        if (payload == null) {
            failPendingConnection(ListenTogetherFailure.InvalidRoomResponse)
            return
        }
        lastRoomAction = null
        _userId.value = payload.userId
        _role.value = ListenTogetherRole.GUEST
        sessionToken = payload.sessionToken
        _roomCode.value = payload.roomCode
        wasHost = false
        _roomState.value = payload.state
        persistSession()
        log(ListenTogetherLogLevel.INFO, "Joined room", "Code: ${payload.roomCode}")
        emit(ListenTogetherEvent.JoinApproved(payload.roomCode, payload.userId, payload.state))
    }

    private fun handleJoinRejected(frame: InboundFrame) {
        val payload = roomActionDeadline.completeWithPayload(frame.decode<JoinRejectedPayload>())
        if (payload == null) {
            failPendingConnection(ListenTogetherFailure.InvalidRoomResponse)
            return
        }
        lastRoomAction = null
        log(ListenTogetherLogLevel.WARNING, "Join rejected", payload.reason)
        emit(ListenTogetherEvent.JoinRejected(payload.reason))
    }

    private fun handleUserJoined(frame: InboundFrame) {
        val payload = frame.decode<UserJoinedPayload>() ?: return
        _roomState.value = _roomState.value?.withUserJoined(payload)
        _pendingJoinRequests.value = _pendingJoinRequests.value.filter { it.userId != payload.userId }
        log(ListenTogetherLogLevel.INFO, "User joined", payload.username)
        emit(ListenTogetherEvent.UserJoined(payload.userId, payload.username))
    }

    private fun handleUserLeft(frame: InboundFrame) {
        val payload = frame.decode<UserLeftPayload>() ?: return
        _roomState.value = _roomState.value?.withoutUser(payload.userId)
        log(ListenTogetherLogLevel.INFO, "User left", payload.username)
        emit(ListenTogetherEvent.UserLeft(payload.userId, payload.username))
    }

    private fun handleHostChanged(frame: InboundFrame) {
        val payload = frame.decode<HostChangedPayload>() ?: return
        _roomState.value = _roomState.value?.withHost(payload.newHostId)
        if (payload.newHostId == _userId.value) {
            _role.value = ListenTogetherRole.HOST
        } else if (_role.value == ListenTogetherRole.HOST) {
            _role.value = ListenTogetherRole.GUEST
        }
        log(ListenTogetherLogLevel.INFO, "Host changed", "New host: ${payload.newHostName}")
        emit(ListenTogetherEvent.HostChanged(payload.newHostId, payload.newHostName))
    }

    private fun handleKicked(frame: InboundFrame) {
        val payload = frame.decode<KickedPayload>() ?: return
        log(ListenTogetherLogLevel.WARNING, "Kicked from room", payload.reason)
        // The kicked session is dropped, not just hidden. Otherwise a later restart could try to resume it.
        sessionToken = null
        _roomCode.value = null
        _roomState.value = null
        _role.value = ListenTogetherRole.NONE
        clearPersistedSession()
        emit(ListenTogetherEvent.Kicked(payload.reason))
    }

    private fun handlePlaybackSync(frame: InboundFrame) {
        val payload = frame.decode<PlaybackActionPayload>() ?: return
        log(ListenTogetherLogLevel.DEBUG, "Playback sync", "Action: ${payload.action}")
        _roomState.value = _roomState.value?.withPlayback(payload)
        emit(ListenTogetherEvent.PlaybackSync(payload))
    }

    private fun handleBufferWait(frame: InboundFrame) {
        val payload = frame.decode<BufferWaitPayload>() ?: return
        _bufferingUsers.value = payload.waitingFor
        log(ListenTogetherLogLevel.DEBUG, "Waiting for buffering", "Users: ${payload.waitingFor.size}")
        emit(ListenTogetherEvent.BufferWait(payload.trackId, payload.waitingFor))
    }

    private fun handleBufferComplete(frame: InboundFrame) {
        val payload = frame.decode<BufferCompletePayload>() ?: return
        _bufferingUsers.value = emptyList()
        log(ListenTogetherLogLevel.INFO, "All users buffered", "Track: ${payload.trackId}")
        emit(ListenTogetherEvent.BufferComplete(payload.trackId))
    }

    private fun handleSyncState(frame: InboundFrame) {
        val payload = frame.decode<SyncStatePayload>() ?: return
        log(ListenTogetherLogLevel.INFO, "Sync state received", "Playing: ${payload.isPlaying}, Position: ${payload.position}")
        emit(ListenTogetherEvent.SyncStateReceived(payload))
    }

    private fun handleSuggestionReceived(frame: InboundFrame) {
        val payload = frame.decode<SuggestionReceivedPayload>() ?: return
        // Only the host is asked to decide; a guest never receives a suggestion meant for the host.
        if (_role.value != ListenTogetherRole.HOST) return
        if (isUserBlocked(payload.fromUsername)) {
            log(ListenTogetherLogLevel.INFO, "Suggestion from blocked user ignored", "User: ${payload.fromUsername}")
            return
        }
        // An auto-approved suggestion takes effect at once, because the guest has usually already changed their own
        // track. A manually approved one waits in the list for the host to time it.
        if (preferences.autoApproveSuggestions()) {
            log(ListenTogetherLogLevel.INFO, "Auto-approving suggestion", "${payload.fromUsername}: ${payload.trackInfo.title}")
            sendMessage(MessageTypes.APPROVE_SUGGESTION, ApproveSuggestionPayload(payload.suggestionId))
            emit(ListenTogetherEvent.LocalSuggestionApproved(payload, playImmediately = true))
            return
        }
        _pendingSuggestions.value = _pendingSuggestions.value + payload
        log(ListenTogetherLogLevel.INFO, "Suggestion received", "${payload.fromUsername}: ${payload.trackInfo.title}")
        emit(ListenTogetherEvent.SuggestionReceived(payload))
    }

    private fun handleSuggestionApproved(frame: InboundFrame) {
        val payload = frame.decode<SuggestionApprovedPayload>() ?: return
        log(ListenTogetherLogLevel.INFO, "Suggestion approved", payload.trackInfo?.title ?: payload.suggestionId)
        emit(ListenTogetherEvent.SuggestionApproved(payload))
    }

    private fun handleSuggestionRejected(frame: InboundFrame) {
        val payload = frame.decode<SuggestionRejectedPayload>() ?: return
        log(ListenTogetherLogLevel.WARNING, "Suggestion rejected", payload.reason ?: "")
        emit(ListenTogetherEvent.SuggestionRejected(payload.suggestionId, payload.reason))
    }

    private fun handleServerError(frame: InboundFrame) {
        val payload = frame.decode<ErrorPayload>() ?: return
        if (payload.code != INVALID_MESSAGE) {
            roomActionDeadline.complete()
            pendingAction = null
            lastRoomAction = null
        }
        log(ListenTogetherLogLevel.ERROR, "Server error", "${payload.code}: ${payload.message}")
        when (payload.code) {
            INVALID_MESSAGE -> maybeRetryRoomActionAfterProtocolUpgrade()
            SESSION_NOT_FOUND -> handleSessionNotFound()
        }
        emit(ListenTogetherEvent.ServerError(payload.code, payload.message))
    }

    /**
     * The server no longer knows the saved session. A guest rejoins the same room once, because the room may still
     * exist. A host cannot rejoin as host, so the saved session is dropped for a fresh create.
     */
    private fun handleSessionNotFound() {
        val room = _roomCode.value
        val name = _username.value
        if (room != null && name != null && !wasHost) {
            log(ListenTogetherLogLevel.WARNING, "Session expired on server", "Attempting automatic rejoin to room: $room")
            scope.launch {
                delay(ListenTogetherTiming.SESSION_NOT_FOUND_REJOIN_DELAY_MS)
                joinRoom(room, name)
            }
        } else {
            if (room != null && name != null) {
                log(ListenTogetherLogLevel.WARNING, "Host session expired", "Room: $room - manual intervention may be needed")
            }
            clearPersistedSession()
            sessionToken = null
        }
    }

    private fun handleReconnected(frame: InboundFrame) {
        val payload = frame.decode<ReconnectedPayload>() ?: return
        _userId.value = payload.userId
        _role.value = if (payload.isHost) ListenTogetherRole.HOST else ListenTogetherRole.GUEST
        _roomCode.value = payload.roomCode
        _roomState.value = payload.state
        wasHost = payload.isHost
        persistSession()
        reconnectAttempts = 0
        log(
            ListenTogetherLogLevel.INFO,
            "Successfully reconnected to room",
            "Code: ${payload.roomCode}, isHost: ${payload.isHost}",
        )
        emit(ListenTogetherEvent.Reconnected(payload.roomCode, payload.userId, payload.state, payload.isHost))
    }

    private fun handleUserReconnected(frame: InboundFrame) {
        val payload = frame.decode<UserReconnectedPayload>() ?: return
        _roomState.value = _roomState.value?.withConnection(payload.userId, connected = true)
        log(ListenTogetherLogLevel.INFO, "User reconnected", payload.username)
        emit(ListenTogetherEvent.UserReconnected(payload.userId, payload.username))
    }

    private fun handleUserDisconnected(frame: InboundFrame) {
        val payload = frame.decode<UserDisconnectedPayload>() ?: return
        _roomState.value = _roomState.value?.withConnection(payload.userId, connected = false)
        log(ListenTogetherLogLevel.INFO, "User temporarily disconnected", payload.username)
        emit(ListenTogetherEvent.UserDisconnected(payload.userId, payload.username))
    }

    /** Chat envelopes (avatars, control events, replies, shared tracks) are the chat owner's to decode. */
    private fun handleChat(frame: InboundFrame) {
        val payload = frame.decode<ChatMessagePayload>() ?: return
        emit(ListenTogetherEvent.ChatReceived(payload))
    }

    // Outbound frames

    /**
     * Encodes and queues one frame on the live socket. A send with no socket is logged, not thrown: the caller is
     * a timer or a host call, and the connection state already tells the user what is happening.
     */
    private fun sendMessage(type: String, payload: Any?) {
        val frame = try {
            codec.encode(type, payload)
        } catch (e: Exception) {
            log(ListenTogetherLogLevel.ERROR, "Error encoding message", "$type: ${e.message}")
            return
        }
        log(ListenTogetherLogLevel.DEBUG, "Sending message", "$type (${codec.format.name})")
        val sent = socket?.send(frame) ?: false
        if (!sent) {
            log(ListenTogetherLogLevel.ERROR, "Failed to send message", type)
        }
    }

    // Persistence: queued writes run in order and never suspend a handler.

    private fun persistSession() {
        val token = sessionToken ?: return
        val session = ListenTogetherSession(
            sessionToken = token,
            roomCode = _roomCode.value.orEmpty(),
            userId = _userId.value.orEmpty(),
            isHost = wasHost,
            savedAtMs = nowMs(),
        )
        enqueueWrite { store.saveSession(session) }
    }

    private fun clearPersistedSession() {
        enqueueWrite { store.clearSession() }
    }

    private fun enqueueWrite(write: suspend () -> Unit) {
        persistence.trySend(write)
    }

    private suspend fun runWrite(write: suspend () -> Unit) {
        try {
            write()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(ListenTogetherLogLevel.ERROR, "Failed to save Listen Together state", e.message)
        }
    }

    /** Loads the saved session and blocked list once, then hands them to the owner's queue. */
    private suspend fun restoreStoredState() {
        val session: ListenTogetherSession? = try {
            store.loadSession()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(ListenTogetherLogLevel.ERROR, "Failed to load persisted session", e.message)
            null
        }
        val blocked: Set<String> = try {
            store.loadBlockedUsernames()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(ListenTogetherLogLevel.ERROR, "Failed to load blocked usernames", e.message)
            emptySet()
        }
        post(Command.Restore(session, blocked))
    }

    private fun doRestore(session: ListenTogetherSession?, blocked: Set<String>) {
        if (blocked.isNotEmpty()) {
            _blockedUsernames.value = _blockedUsernames.value + blocked
        }
        if (session == null) return
        // A room action that started while the store was loading owns the session; the stored one is stale.
        if (sessionToken != null || _roomCode.value != null) return
        val age = nowMs() - session.savedAtMs
        if (age >= ListenTogetherTiming.SESSION_GRACE_PERIOD_MS) {
            log(ListenTogetherLogLevel.WARNING, "Session expired", "Age: ${age}ms")
            clearPersistedSession()
            return
        }
        sessionToken = session.sessionToken
        _roomCode.value = session.roomCode
        _userId.value = session.userId.ifEmpty { null }
        wasHost = session.isHost
        log(ListenTogetherLogLevel.INFO, "Loaded persisted session", "Room: ${session.roomCode}, Host: ${session.isHost}")
    }

    // Logging and one-shot events

    private fun log(level: ListenTogetherLogLevel, message: String, details: String? = null) {
        val entry = ListenTogetherLogEntry(nowMs(), level, message, details)
        _logs.value = (_logs.value + entry).takeLast(ListenTogetherTiming.MAX_LOG_ENTRIES)
    }

    private fun emit(event: ListenTogetherEvent) {
        _events.tryEmit(event)
    }

    private companion object {
        const val NORMAL_CLOSE = 1000
        const val EVENT_BUFFER_CAPACITY = 256
        const val RECONNECT_BACKOFF_MAX_SHIFT = 4
        const val JITTER_FRACTION = 0.2
        const val INVALID_MESSAGE = "invalid_message"
        const val SESSION_NOT_FOUND = "session_not_found"
    }
}
