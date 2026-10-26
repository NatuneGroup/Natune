/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.data.listentogether

/**
 * Where the Listen Together connection is. [RECONNECTING] means a retry is already scheduled. [ERROR] means a
 * bounded wait or the retry budget ran out, and the client now waits for the user to act.
 */
enum class ListenTogetherConnectionState { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING, ERROR }

/** The local user's part in the current room. */
enum class ListenTogetherRole { HOST, GUEST, NONE }

/**
 * A room request that waits for the socket to open. It is sent as soon as the connection is ready, and it is
 * dropped if the connection gives up first.
 */
sealed interface ListenTogetherPendingAction {
    data class CreateRoom(val username: String) : ListenTogetherPendingAction

    data class JoinRoom(val roomCode: String, val username: String) : ListenTogetherPendingAction
}

/**
 * Why a connection or room request failed. Common code carries the reason, never a finished sentence: the host
 * maps each case to its own localized text. [detail] is diagnostic and may come from the transport engine.
 */
sealed interface ListenTogetherFailure {
    /** The socket did not open within the connection deadline. */
    data object ConnectionTimeout : ListenTogetherFailure

    /** The server did not answer a create or join within the room-request deadline. */
    data object RoomRequestTimeout : ListenTogetherFailure

    /** The server answered a create or join with a body the client could not use. */
    data object InvalidRoomResponse : ListenTogetherFailure

    /** The connection dropped and no session was kept, so there is nothing to resume. */
    data class ConnectionFailed(val detail: String?) : ListenTogetherFailure

    /** The retry budget ran out, but the session is kept so the user can reconnect by hand. */
    data class ReconnectExhausted(val attempts: Int, val detail: String?) : ListenTogetherFailure
}

/** Non-error notices the host turns into its own feedback, such as a toast. */
enum class ListenTogetherNotice {
    /** A create or join was refused because another room request is still waiting for an answer. */
    ROOM_REQUEST_BUSY,

    /** Chat was requested on a protobuf-only server, which has no chat relay. */
    CHAT_UNSUPPORTED_BY_SERVER,

    /** A guest's suggestion was accepted for sending to the host. */
    SUGGESTION_SENT,
}

enum class ListenTogetherLogLevel { DEBUG, INFO, WARNING, ERROR }

/** One line of the bounded connection log that a debug screen can show. */
data class ListenTogetherLogEntry(
    val timestampMs: Long,
    val level: ListenTogetherLogLevel,
    val message: String,
    val details: String? = null,
)

/**
 * Everything the client tells its host. State that a screen renders is read from the client's state flows; events
 * are one-shot reactions such as a notification, a toast, or applying a playback action to the local player.
 */
sealed interface ListenTogetherEvent {
    data object Disconnected : ListenTogetherEvent

    data class ConnectionError(val failure: ListenTogetherFailure) : ListenTogetherEvent

    data class Reconnecting(val attempt: Int, val maxAttempts: Int) : ListenTogetherEvent

    data class RoomCreated(val roomCode: String, val userId: String) : ListenTogetherEvent

    /** The local user left the room on purpose, so the host can stop room-bound work such as chat notifications. */
    data object RoomLeft : ListenTogetherEvent

    /**
     * A user asked to join. [awaitingHostDecision] is true when the local user is host and auto-approval is off,
     * which is when the host should show an actionable notification.
     */
    data class JoinRequestReceived(
        val request: JoinRequestPayload,
        val awaitingHostDecision: Boolean,
    ) : ListenTogetherEvent

    data class JoinApproved(val roomCode: String, val userId: String, val state: RoomState) : ListenTogetherEvent

    data class JoinRejected(val reason: String) : ListenTogetherEvent

    data class UserJoined(val userId: String, val username: String) : ListenTogetherEvent

    data class UserLeft(val userId: String, val username: String) : ListenTogetherEvent

    data class HostChanged(val newHostId: String, val newHostName: String) : ListenTogetherEvent

    data class Kicked(val reason: String) : ListenTogetherEvent

    data class Reconnected(
        val roomCode: String,
        val userId: String,
        val state: RoomState,
        val isHost: Boolean,
    ) : ListenTogetherEvent

    data class UserReconnected(val userId: String, val username: String) : ListenTogetherEvent

    data class UserDisconnected(val userId: String, val username: String) : ListenTogetherEvent

    /** A host playback action. The local player applies it; the room state has already been updated. */
    data class PlaybackSync(val action: PlaybackActionPayload) : ListenTogetherEvent

    data class BufferWait(val trackId: String, val waitingFor: List<String>) : ListenTogetherEvent

    data class BufferComplete(val trackId: String) : ListenTogetherEvent

    data class SyncStateReceived(val state: SyncStatePayload) : ListenTogetherEvent

    /** A guest suggested a track and manual approval is on, so the host decides. */
    data class SuggestionReceived(val suggestion: SuggestionReceivedPayload) : ListenTogetherEvent

    data class SuggestionApproved(val payload: SuggestionApprovedPayload) : ListenTogetherEvent

    data class SuggestionRejected(val suggestionId: String, val reason: String?) : ListenTogetherEvent

    /**
     * The host approved a suggestion. The local player should play it now or queue it, as [playImmediately] says,
     * because the server does not play anything itself.
     */
    data class LocalSuggestionApproved(
        val suggestion: SuggestionReceivedPayload,
        val playImmediately: Boolean,
    ) : ListenTogetherEvent

    /** A server error. The host localizes known codes such as `host_not_allowed` and shows the rest as text. */
    data class ServerError(val code: String, val message: String) : ListenTogetherEvent

    /** A raw chat relay frame. The chat owner decodes envelopes such as shared tracks, replies and avatars. */
    data class ChatReceived(val payload: ChatMessagePayload) : ListenTogetherEvent

    data class Notice(val notice: ListenTogetherNotice) : ListenTogetherEvent
}

/**
 * The reconnect record kept across restarts: the room the user joined and the token that proves it.
 * [savedAtMs] is when the record was last written, which decides whether it is still inside the grace period.
 */
data class ListenTogetherSession(
    val sessionToken: String,
    val roomCode: String,
    val userId: String,
    val isHost: Boolean,
    val savedAtMs: Long,
)

/** Settings the client reads at the moment it needs them. The host caches its preference values. */
interface ListenTogetherPreferences {
    /** The saved server URL. A URL outside the allow-list is replaced by the default server. */
    fun serverUrl(): String

    /** Host setting: approve join requests without asking. Hosts default this to off. */
    fun autoApproveJoinRequests(): Boolean

    /** Host setting: approve guest suggestions at once and play them. Hosts default this to on. */
    fun autoApproveSuggestions(): Boolean

    /** Avatar index sent with create and join requests. */
    fun avatarIndex(): Int
}

/**
 * Persistence for the reconnect session and the blocked-user list. The host backs it with its preference store.
 * [loadSession] returns null when nothing restorable is stored, including an incomplete record.
 */
interface ListenTogetherStore {
    suspend fun loadSession(): ListenTogetherSession?

    suspend fun saveSession(session: ListenTogetherSession)

    suspend fun clearSession()

    suspend fun loadBlockedUsernames(): Set<String>

    suspend fun saveBlockedUsernames(usernames: Set<String>)
}

/** Timing and budget values shared by the client and by any host that explains them to the user. */
object ListenTogetherTiming {
    const val MAX_RECONNECT_ATTEMPTS = 15
    const val INITIAL_RECONNECT_DELAY_MS = 1_000L
    const val MAX_RECONNECT_DELAY_MS = 120_000L
    const val PING_INTERVAL_MS = 25_000L

    /** A saved session older than this is not resumed; resuming a stale room would surprise the user. */
    const val SESSION_GRACE_PERIOD_MS = 10 * 60 * 1000L

    /** A protobuf upgrade can reject a JSON create or join; a retry is only worth it this soon after the send. */
    const val ROOM_ACTION_RETRY_WINDOW_MS = 10_000L
    const val ROOM_ACTION_RETRY_DELAY_MS = 250L
    const val CONNECTION_DEADLINE_MS = 60_000L
    const val ROOM_ACTION_DEADLINE_MS = 90_000L
    const val MAX_LOG_ENTRIES = 500

    /** After the server reports a missing session, the client rejoins as a guest once this much time has passed. */
    const val SESSION_NOT_FOUND_REJOIN_DELAY_MS = 500L

    /** A forced reconnect waits this long so the old socket can finish closing. */
    const val FORCED_RECONNECT_DELAY_MS = 500L
}
