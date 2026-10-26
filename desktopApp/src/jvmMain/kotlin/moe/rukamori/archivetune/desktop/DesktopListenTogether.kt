package moe.rukamori.archivetune.desktop

import java.text.SimpleDateFormat
import java.util.Date
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.desktop.integration.DesktopListenTogetherGzip
import moe.rukamori.archivetune.desktop.integration.DesktopListenTogetherPreferences
import moe.rukamori.archivetune.desktop.integration.DesktopListenTogetherSocketFactory
import moe.rukamori.archivetune.desktop.integration.DesktopListenTogetherStore
import moe.rukamori.archivetune.desktop.integration.DesktopStrings
import moe.rukamori.archivetune.shared.data.listentogether.JoinRequestPayload
import moe.rukamori.archivetune.shared.data.listentogether.ListenTogetherClient
import moe.rukamori.archivetune.shared.data.listentogether.ListenTogetherConnectionState
import moe.rukamori.archivetune.shared.data.listentogether.ListenTogetherEvent
import moe.rukamori.archivetune.shared.data.listentogether.ListenTogetherFailure
import moe.rukamori.archivetune.shared.data.listentogether.ListenTogetherLogEntry
import moe.rukamori.archivetune.shared.data.listentogether.ListenTogetherLogLevel
import moe.rukamori.archivetune.shared.data.listentogether.ListenTogetherMessageCodec
import moe.rukamori.archivetune.shared.data.listentogether.ListenTogetherNotice
import moe.rukamori.archivetune.shared.data.listentogether.ListenTogetherRole
import moe.rukamori.archivetune.shared.data.listentogether.ListenTogetherServers
import moe.rukamori.archivetune.shared.data.listentogether.PlaybackActionPayload
import moe.rukamori.archivetune.shared.data.listentogether.PlaybackActions
import moe.rukamori.archivetune.shared.data.listentogether.RoomState
import moe.rukamori.archivetune.shared.data.listentogether.SuggestionReceivedPayload
import moe.rukamori.archivetune.shared.data.listentogether.SyncStatePayload
import moe.rukamori.archivetune.shared.data.listentogether.TrackInfo
import moe.rukamori.archivetune.shared.domain.playback.MusicPlayerController
import moe.rukamori.archivetune.shared.domain.playback.PlaybackSnapshot
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
import moe.rukamori.archivetune.shared.domain.settings.ListenTogetherSyncVolumeKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.SettingsStore
import moe.rukamori.archivetune.shared.domain.settings.commitListenTogetherUsername
import moe.rukamori.archivetune.shared.domain.settings.listenTogetherUsername
import moe.rukamori.archivetune.shared.ui.screens.ListenRoomUi
import moe.rukamori.archivetune.shared.ui.screens.ListenTogetherUi
import moe.rukamori.archivetune.shared.ui.screens.RoomConnectionUi
import moe.rukamori.archivetune.shared.ui.screens.RoomJoinRequestUi
import moe.rukamori.archivetune.shared.ui.screens.RoomMemberUi
import moe.rukamori.archivetune.shared.ui.screens.RoomQueueRowUi
import moe.rukamori.archivetune.shared.ui.screens.RoomSuggestionUi

/**
 * The one Listen Together owner of the desktop process: the shared client over the desktop socket, codec, preference
 * and vault-store adapters, and the driver that keeps the one player in step with the room.
 *
 * As host, a track change sends the track and the upcoming queue, play and pause send the position, a seek sends the
 * new position, a heartbeat repeats the position while playing, and a member who joins receives the current track. As
 * guest, a host track is loaded paused, reported ready once the engine has it, and started when the server says every
 * member is ready; play, pause and seek follow the host within the baseline tolerances. A sync state bypasses the
 * buffer wait, as it does on Android. The room queue is shown from the room state; only the host's current track and
 * its upcoming tracks replace the guest's local queue.
 *
 * Only YouTube songs travel between members, because a room track is a YouTube video id on every client.
 */
internal class DesktopListenTogether(
    parentScope: CoroutineScope,
    private val settings: SettingsStore,
    private val player: MusicPlayerController,
    private val queue: QueueController,
    private val setVolume: (Float) -> Unit,
    private val report: (String) -> Unit,
) {
    private val job = SupervisorJob(parentScope.coroutineContext[Job])

    // The client and this driver share one thread, so the sync fields below are never read half-written.
    private val scope = CoroutineScope(parentScope.coroutineContext + job + Dispatchers.Default.limitedParallelism(1))
    private val preferences = DesktopListenTogetherPreferences(settings)

    val client = ListenTogetherClient(
        scope = scope,
        sockets = DesktopListenTogetherSocketFactory(),
        codec = ListenTogetherMessageCodec(gzip = DesktopListenTogetherGzip()),
        preferences = preferences,
        store = DesktopListenTogetherStore(settings, report),
        // The JVM publishes no network events; a dropped socket still reaches the client's retry rules.
        network = MutableStateFlow(true),
        nowMs = System::currentTimeMillis,
    )

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

    val ui: StateFlow<ListenTogetherUi> = combine(live, local, settings.snapshots, client.username) { live, local, snapshot, joined ->
        uiOf(live, local, snapshot, joined)
    }.stateIn(scope, SharingStarted.Eagerly, uiOf(Live(client.connectionState.value, null, emptyList(), emptyList(), null), Local(), settings.snapshots.value, null))

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

    /** The local player's current song in room form, or null when it is not a YouTube song the room could play. */
    val localCurrentTrack: StateFlow<TrackInfo?> =
        combine(player.snapshot, queue.state) { snapshot, state ->
            snapshot?.occurrenceId
                ?.let { id -> state.entries.firstOrNull { it.occurrenceId == id }?.item?.toTrackInfo(snapshot.durationMs) }
        }.stateIn(scope, SharingStarted.Eagerly, null)

    /** The connection log since the last clear. The client keeps its own bounded log, so a clear hides older lines here. */
    val logs: StateFlow<List<ListenTogetherSettingsLogLine>> = combine(client.logs, logsClearedAtMs) { entries, clearedAt ->
        entries.filter { it.timestampMs > clearedAt }.map { it.toLine() }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    // Host-side observation of the player.
    private var hostOccurrenceId: String? = null
    private var hostPlayWhenReady: Boolean? = null
    private var hostDiscontinuity: Long? = null

    // Guest-side state: the room track loaded into the local queue and the host state that waits for the buffer.
    private var guestTrackId: String? = null
    private var guestOccurrenceId: String? = null
    private var pendingTrackId: String? = null
    private var pendingPlaying = false
    private var pendingPositionMs = 0L
    private var pendingReceivedAtMs = 0L

    fun start() {
        scope.launch { client.events.collect { event -> guarded("event") { onEvent(event) } } }
        scope.launch { player.snapshot.collect { snapshot -> guarded("host observation") { observeAsHost(snapshot) } } }
        scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                guarded("heartbeat") {
                    val snapshot = player.snapshot.value
                    if (isHostInRoom() && snapshot?.playWhenReady == true && hostOccurrenceId == snapshot.occurrenceId) {
                        sendAction(PlaybackActions.PLAY, snapshot)
                    }
                }
            }
        }
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

    fun cancelJoin() {
        local.update { it.copy(creating = false, waitingForApproval = false) }
        client.leaveRoom()
    }

    fun leave() {
        client.leaveRoom()
        scope.launch { resetSync() }
    }

    /**
     * Starts [track] in the room the way the Android app does: the host queues it for immediate play, a guest offers
     * it to the host as a suggestion. Outside a room nothing is sent and the caller is told so.
     */
    fun playSharedTrack(track: TrackInfo): Boolean {
        if (isHostInRoom()) {
            scope.launch { queueSuggestion(track, playImmediately = true) }
            return true
        }
        if (isGuestInRoom()) {
            client.suggestTrack(track)
            return true
        }
        return false
    }

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
        logsClearedAtMs.value = client.logs.value.maxOfOrNull { it.timestampMs } ?: System.currentTimeMillis()
    }

    /** Shows a host message on the room page, such as a feature this host does not connect. */
    fun showMessage(message: String) = local.update { it.copy(message = message) }

    /** The web invite for the current room on the configured server, or null outside a room. */
    fun inviteLink(): String? {
        val code = client.roomCode.value ?: return null
        return "https://${ListenTogetherServers.inviteHost(preferences.serverUrl())}/listen?code=$code"
    }

    fun close() {
        job.cancel()
    }

    private fun saveUsername(username: String) {
        scope.launch {
            try {
                settings.edit { commitListenTogetherUsername(username) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                report("Listen Together name was not saved: ${failure.javaClass.simpleName}")
            }
        }
    }

    private suspend fun onEvent(event: ListenTogetherEvent) {
        when (event) {
            is ListenTogetherEvent.RoomCreated -> {
                local.update { it.copy(creating = false, message = null) }
                resetSync()
                announceCurrentTrack()
            }
            is ListenTogetherEvent.JoinApproved -> {
                local.update { it.copy(waitingForApproval = false, message = null) }
                resetSync()
                applySyncState(event.state.toSyncState())
            }
            is ListenTogetherEvent.JoinRejected -> local.update { it.copy(waitingForApproval = false, message = event.reason) }
            is ListenTogetherEvent.Kicked -> {
                local.update { it.copy(message = event.reason) }
                resetSync()
            }
            is ListenTogetherEvent.Reconnected -> {
                resetSync()
                if (event.isHost) announceCurrentTrack() else applySyncState(event.state.toSyncState())
            }
            is ListenTogetherEvent.UserJoined -> if (isHostInRoom()) announceCurrentTrack()
            is ListenTogetherEvent.HostChanged -> {
                resetSync()
                if (isHostInRoom()) announceCurrentTrack() else client.requestSync()
            }
            is ListenTogetherEvent.PlaybackSync -> if (isGuestInRoom()) applyAction(event.action)
            is ListenTogetherEvent.BufferComplete -> if (isGuestInRoom() && event.trackId == pendingTrackId) applyPending()
            is ListenTogetherEvent.SyncStateReceived -> if (isGuestInRoom()) applySyncState(event.state)
            is ListenTogetherEvent.LocalSuggestionApproved -> queueSuggestion(event.suggestion.trackInfo, event.playImmediately)
            is ListenTogetherEvent.ServerError -> local.update {
                if (event.code == HOST_NOT_ALLOWED) {
                    it.copy(
                        creating = false,
                        waitingForApproval = false,
                        hostingAllowed = false,
                        hostingRestriction = DesktopStrings["listen_together_host_not_allowed"],
                    )
                } else {
                    it.copy(creating = false, waitingForApproval = false, message = event.message.ifBlank { null })
                }
            }
            is ListenTogetherEvent.ConnectionError -> local.update {
                it.copy(creating = false, waitingForApproval = false, message = failureText(event.failure))
            }
            is ListenTogetherEvent.Notice -> when (event.notice) {
                ListenTogetherNotice.ROOM_REQUEST_BUSY ->
                    local.update { it.copy(message = DesktopStrings["listen_together_request_in_progress"]) }
                ListenTogetherNotice.CHAT_UNSUPPORTED_BY_SERVER ->
                    local.update { it.copy(message = DesktopStrings["listen_together_chat_unsupported_server"]) }
                ListenTogetherNotice.SUGGESTION_SENT ->
                    local.update { it.copy(message = DesktopStrings["listen_together_suggestion_sent"]) }
            }
            ListenTogetherEvent.RoomLeft, ListenTogetherEvent.Disconnected -> {
                local.update { it.copy(creating = false, waitingForApproval = false) }
                resetSync()
            }
            else -> Unit
        }
    }

    private fun resetSync() {
        hostOccurrenceId = null
        hostPlayWhenReady = null
        hostDiscontinuity = null
        guestTrackId = null
        guestOccurrenceId = null
        pendingTrackId = null
    }

    private fun isHostInRoom(): Boolean = client.role.value == ListenTogetherRole.HOST && client.roomState.value != null

    private fun isGuestInRoom(): Boolean = client.role.value == ListenTogetherRole.GUEST && client.roomState.value != null

    // Host side

    private fun observeAsHost(snapshot: PlaybackSnapshot?) {
        if (snapshot == null || !isHostInRoom()) return
        when {
            snapshot.occurrenceId != hostOccurrenceId -> announce(snapshot)
            snapshot.playWhenReady != hostPlayWhenReady -> {
                hostPlayWhenReady = snapshot.playWhenReady
                hostDiscontinuity = snapshot.discontinuityGeneration
                sendAction(if (snapshot.playWhenReady) PlaybackActions.PLAY else PlaybackActions.PAUSE, snapshot)
            }
            snapshot.discontinuityGeneration != hostDiscontinuity -> {
                hostDiscontinuity = snapshot.discontinuityGeneration
                sendAction(PlaybackActions.SEEK, snapshot)
            }
        }
    }

    private fun announceCurrentTrack() {
        if (!isHostInRoom()) return
        player.snapshot.value?.let(::announce)
    }

    private fun announce(snapshot: PlaybackSnapshot) {
        hostOccurrenceId = snapshot.occurrenceId
        hostPlayWhenReady = snapshot.playWhenReady
        hostDiscontinuity = snapshot.discontinuityGeneration
        val state = queue.state.value
        val current = state.entries.firstOrNull { it.occurrenceId == snapshot.occurrenceId } ?: return
        val track = current.item.toTrackInfo(snapshot.durationMs) ?: run {
            report("Listen Together: the current track is not a YouTube song, so the room keeps its previous track")
            return
        }
        val order = state.playOrder
        val upcoming = order.drop(order.indexOf(current.occurrenceId) + 1)
            .mapNotNull { id -> state.entries.firstOrNull { it.occurrenceId == id }?.item?.toTrackInfo(null) }
            .take(MAX_SHARED_QUEUE)
        val position = player.positionMs() ?: snapshot.positionMs
        client.sendPlaybackAction(
            PlaybackActionPayload(
                action = PlaybackActions.CHANGE_TRACK,
                trackId = track.id,
                position = position,
                trackInfo = track,
                queue = upcoming,
            ),
        )
        if (snapshot.playWhenReady) sendAction(PlaybackActions.PLAY, snapshot)
    }

    private fun sendAction(action: String, snapshot: PlaybackSnapshot) {
        val item = queue.state.value.entries.firstOrNull { it.occurrenceId == snapshot.occurrenceId }?.item ?: return
        if (item.ref.provider != ContentProvider.YOUTUBE.id) return
        client.sendPlaybackAction(
            PlaybackActionPayload(action = action, trackId = item.ref.id, position = player.positionMs() ?: snapshot.positionMs),
        )
    }

    private suspend fun queueSuggestion(track: TrackInfo, playImmediately: Boolean) {
        val occurrence = track.toOccurrence()
        if (playImmediately) {
            queue.insert(listOf(occurrence), UserQueuedKind.PLAY_NEXT)
            queue.select(occurrence.occurrenceId)
        } else {
            queue.insert(listOf(occurrence), UserQueuedKind.ADD_TO_QUEUE)
        }
    }

    // Guest side

    private suspend fun applyAction(action: PlaybackActionPayload) {
        val now = System.currentTimeMillis()
        when (action.action) {
            PlaybackActions.CHANGE_TRACK -> {
                val track = action.trackInfo ?: return
                loadRoomTrack(track, action.queue.orEmpty())
                pendingTrackId = track.id
                pendingPlaying = false
                pendingPositionMs = action.position ?: 0L
                pendingReceivedAtMs = now
                reportReadyWhenLoaded(track.id)
            }
            PlaybackActions.PLAY -> {
                val base = action.position ?: return
                val target = base + (action.serverTime?.let { max(0L, now - it) } ?: 0L)
                if (action.trackId != null && action.trackId != guestTrackId) {
                    client.requestSync()
                    return
                }
                if (pendingTrackId != null) {
                    pendingPlaying = true
                    pendingPositionMs = target
                    pendingReceivedAtMs = now
                    return
                }
                alignPosition(target, PLAYBACK_TOLERANCE_MS)
                if (player.snapshot.value?.playWhenReady != true) player.play()
            }
            PlaybackActions.PAUSE -> {
                if (pendingTrackId != null) {
                    pendingPlaying = false
                    action.position?.let { pendingPositionMs = it; pendingReceivedAtMs = now }
                    return
                }
                player.pause()
                action.position?.let { alignPosition(it, POSITION_TOLERANCE_MS) }
            }
            PlaybackActions.SEEK -> {
                val position = action.position ?: return
                if (pendingTrackId != null) {
                    pendingPositionMs = position
                    pendingReceivedAtMs = now
                } else {
                    player.seekTo(position)
                }
            }
            PlaybackActions.SET_VOLUME -> {
                val volume = action.volume ?: return
                if (settings.snapshots.value?.get(ListenTogetherSyncVolumeKey) == true) setVolume(volume)
            }
            // Skips arrive as a track change, and the room queue is shown from the room state.
            else -> Unit
        }
    }

    private suspend fun applySyncState(state: SyncStatePayload) {
        val track = state.currentTrack ?: return
        val now = System.currentTimeMillis()
        if (track.id != guestTrackId) {
            loadRoomTrack(track, state.queue.orEmpty())
            awaitLoaded()
        }
        pendingTrackId = null
        val position = state.position + if (state.isPlaying) max(0L, now - state.lastUpdate) else 0L
        player.seekTo(position.coerceAtLeast(0L))
        if (state.isPlaying) player.play() else player.pause()
    }

    private fun applyPending() {
        val position = pendingPositionMs + if (pendingPlaying) max(0L, System.currentTimeMillis() - pendingReceivedAtMs) else 0L
        pendingTrackId = null
        player.seekTo(position.coerceAtLeast(0L))
        if (pendingPlaying) player.play() else player.pause()
    }

    private suspend fun loadRoomTrack(track: TrackInfo, upcoming: List<TrackInfo>) {
        val current = track.toOccurrence()
        val entries = listOf(current) + upcoming.filter { it.id != track.id }.map { it.toOccurrence() }
        guestTrackId = track.id
        guestOccurrenceId = current.occurrenceId
        queue.replace(entries, current.occurrenceId, playWhenReady = false)
    }

    private suspend fun awaitLoaded(): Boolean {
        val occurrenceId = guestOccurrenceId ?: return false
        return withTimeoutOrNull(BUFFER_WAIT_MS) {
            player.snapshot.first { it != null && it.occurrenceId == occurrenceId && !it.isBuffering }
        } != null
    }

    /** Tells the server this member has the track. A load that never settles still reports, so the room is not held. */
    private fun reportReadyWhenLoaded(trackId: String) {
        scope.launch {
            guarded("buffer wait") {
                if (!awaitLoaded()) report("Listen Together: the room track did not load in time")
                if (pendingTrackId == trackId) client.sendBufferReady(trackId)
            }
        }
    }

    private fun alignPosition(target: Long, tolerance: Long) {
        val current = player.positionMs()
        if (current == null || abs(current - target) > tolerance) player.seekTo(target.coerceAtLeast(0L))
    }

    private suspend fun guarded(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            report("Listen Together $what failed: ${failure.javaClass.simpleName}")
        }
    }

    private fun uiOf(live: Live, local: Local, snapshot: PreferenceSnapshot?, joined: String?): ListenTogetherUi {
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
            room = room?.let {
                ListenRoomUi(
                    code = it.roomCode,
                    isHost = it.hostId == selfId,
                    members = it.users.map { user ->
                        RoomMemberUi(user.userId, user.username, user.isHost, user.userId == selfId, user.isConnected)
                    },
                    requests = live.requests.map { request -> RoomJoinRequestUi(request.userId, request.username, busy = false) },
                    suggestions = live.suggestions.map { suggestion ->
                        RoomSuggestionUi(
                            id = suggestion.suggestionId,
                            author = suggestion.fromUsername,
                            track = suggestion.trackInfo.toOccurrence("listen-suggestion:${suggestion.suggestionId}"),
                            busy = false,
                        )
                    },
                    queue = roomQueue(it),
                    // The room chat is not connected on this host, so nothing is ever unread.
                    unread = 0,
                )
            },
            hostingAllowed = local.hostingAllowed,
            hostingRestriction = local.hostingRestriction,
            creating = local.creating && room == null,
            waitingForApproval = local.waitingForApproval && room == null,
            error = local.message,
        )
    }

    private fun roomQueue(room: RoomState): List<RoomQueueRowUi> {
        val current = room.currentTrack
        val tracks = listOfNotNull(current) + room.queue.filter { it.id != current?.id }
        return tracks.mapIndexed { index, track ->
            RoomQueueRowUi(track.toOccurrence("listen-room:$index:${track.id}"), current = index == 0 && current != null)
        }
    }

    private fun failureText(failure: ListenTogetherFailure): String? = when (failure) {
        ListenTogetherFailure.ConnectionTimeout -> DesktopStrings["listen_together_connection_timeout"]
        ListenTogetherFailure.RoomRequestTimeout -> DesktopStrings["listen_together_room_request_timeout"]
        ListenTogetherFailure.InvalidRoomResponse -> DesktopStrings["listen_together_invalid_room_response"]
        // The connection state already shows a failed or exhausted connection with its retry action.
        is ListenTogetherFailure.ConnectionFailed, is ListenTogetherFailure.ReconnectExhausted -> null
    }

    private companion object {
        const val HEARTBEAT_INTERVAL_MS = 10_000L
        const val POSITION_TOLERANCE_MS = 2_000L
        const val PLAYBACK_TOLERANCE_MS = 3_000L
        const val BUFFER_WAIT_MS = 20_000L
        const val MAX_SHARED_QUEUE = 100
        const val HOST_NOT_ALLOWED = "host_not_allowed"
    }
}

private fun RoomState.toSyncState(): SyncStatePayload =
    SyncStatePayload(currentTrack, isPlaying, position, lastUpdate, queue, volume)

/** The room form of a queue item, or null when the item is not a YouTube song another member could play. */
private fun ContentItem.toTrackInfo(fallbackDurationMs: Long?): TrackInfo? {
    if (ref.provider != ContentProvider.YOUTUBE.id || ref.kind != ContentKind.SONG) return null
    return TrackInfo(
        id = ref.id,
        title = title,
        artist = artists.joinToString(", ") { it.name }.ifBlank { subtitle },
        album = album,
        duration = durationMs ?: fallbackDurationMs ?: 0L,
        thumbnail = artworkUri,
    )
}

/** A room track as a fresh queue occurrence; the queue refuses an id it already holds, so each load gets its own. */
private fun TrackInfo.toOccurrence(occurrenceId: String = "listen:${UUID.randomUUID()}"): ContentOccurrence =
    ContentOccurrence(
        occurrenceId,
        ContentItem(
            ref = ContentRef(ContentProvider.YOUTUBE.id, ContentKind.SONG, id),
            title = title.ifBlank { id },
            subtitle = artist,
            artworkUri = thumbnail?.takeIf { it.isNotBlank() },
            durationMs = duration.takeIf { it > 0L },
            album = album?.takeIf { it.isNotBlank() },
            artists = artist.takeIf { it.isNotBlank() }?.let { listOf(ContentCredit(it)) }.orEmpty(),
        ),
    )

private fun ListenTogetherLogEntry.toLine(): ListenTogetherSettingsLogLine =
    ListenTogetherSettingsLogLine(
        timeText = SimpleDateFormat("HH:mm:ss").format(Date(timestampMs)),
        level = when (level) {
            ListenTogetherLogLevel.DEBUG -> ListenTogetherSettingsLogLevel.DEBUG
            ListenTogetherLogLevel.INFO -> ListenTogetherSettingsLogLevel.INFO
            ListenTogetherLogLevel.WARNING -> ListenTogetherSettingsLogLevel.WARNING
            ListenTogetherLogLevel.ERROR -> ListenTogetherSettingsLogLevel.ERROR
        },
        message = message,
        details = details,
    )
