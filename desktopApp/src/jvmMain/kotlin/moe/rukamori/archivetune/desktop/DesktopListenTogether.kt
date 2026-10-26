package moe.rukamori.archivetune.desktop

import java.text.SimpleDateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
import moe.rukamori.archivetune.shared.data.listentogether.ListenTogetherRoomDriver
import moe.rukamori.archivetune.shared.data.listentogether.ListenTogetherServers
import moe.rukamori.archivetune.shared.data.listentogether.RoomState
import moe.rukamori.archivetune.shared.data.listentogether.SuggestionReceivedPayload
import moe.rukamori.archivetune.shared.data.listentogether.TrackInfo
import moe.rukamori.archivetune.shared.domain.playback.MusicPlayerController
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
import moe.rukamori.archivetune.shared.domain.settings.ListenTogetherSmartResyncKey
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

    /** The connection log since the last clear. The client keeps its own bounded log, so a clear hides older lines here. */
    val logs: StateFlow<List<ListenTogetherSettingsLogLine>> = combine(client.logs, logsClearedAtMs) { entries, clearedAt ->
        entries.filter { it.timestampMs > clearedAt }.map { it.toLine() }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    // The engine keeps the player in step with the room; this owner handles the user-facing state around it.
    private val driver = ListenTogetherRoomDriver(
        scope = scope,
        client = client,
        player = player,
        queue = queue,
        setVolume = setVolume,
        smartResync = { settings.snapshots.value?.get(ListenTogetherSmartResyncKey) != false },
        syncVolume = { settings.snapshots.value?.get(ListenTogetherSyncVolumeKey) == true },
        report = report,
        nowMs = System::currentTimeMillis,
    )

    fun start() {
        driver.start()
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

    fun cancelJoin() {
        local.update { it.copy(creating = false, waitingForApproval = false) }
        client.leaveRoom()
    }

    fun leave() {
        client.leaveRoom()
        driver.reset()
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
            is ListenTogetherEvent.RoomCreated -> local.update { it.copy(creating = false, message = null) }
            is ListenTogetherEvent.JoinApproved -> local.update { it.copy(waitingForApproval = false, message = null) }
            is ListenTogetherEvent.JoinRejected -> local.update { it.copy(waitingForApproval = false, message = event.reason) }
            is ListenTogetherEvent.Kicked -> local.update { it.copy(message = event.reason) }
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
            ListenTogetherEvent.RoomLeft, ListenTogetherEvent.Disconnected ->
                local.update { it.copy(creating = false, waitingForApproval = false) }
            else -> Unit
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
        const val HOST_NOT_ALLOWED = "host_not_allowed"
    }
}

/** A room track as a queue row occurrence. The id is stable per row so the list can diff it. */
private fun TrackInfo.toOccurrence(occurrenceId: String): ContentOccurrence =
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
