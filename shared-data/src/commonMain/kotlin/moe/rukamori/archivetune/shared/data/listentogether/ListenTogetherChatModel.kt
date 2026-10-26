/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.data.listentogether

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Chat model for the Listen Together room chat: the timing rules, the typing entry, the history store port and the
// persisted document. The chat owner (ListenTogetherChatOwner) applies these rules on its scope. The helpers in
// ListenTogetherChatHistory hold no state, so the restore and merge rules can be read on their own.

/** Chat timing, matching the Android chat owner. */
object ListenTogetherChatTiming {
    /** Newest chat messages considered for the persisted history, counted before solo messages are dropped. */
    const val MAX_PERSISTED_MESSAGES = 150

    /** A typing indicator stays visible this long after the last typing frame from that member. */
    const val TYPING_TTL_MS = 4_500L

    /** Minimum gap between typing frames this client sends, so a fast typist does not flood the room. */
    const val TYPING_THROTTLE_MS = 2_500L

    /** How often the expiry sweep runs while any typing indicator is visible. */
    const val TYPING_TICK_MS = 1_000L

    /** Quiet period before the chat list is written, so a burst of messages costs one write. */
    const val PERSIST_DEBOUNCE_MS = 600L
}

/**
 * The stable target of one live chat message. The owner issues it when the message enters the live list and never
 * reuses or moves it, so prepending restored history or reordering the list cannot retarget an action. A handle is
 * valid only for the owner and the room session that issued it, and compares equal only to the same handle.
 */
data class ListenTogetherChatMessageHandle internal constructor(
    internal val ownerToken: Long,
    internal val sessionId: Long,
    internal val sequence: Long,
)

/** A live chat message with the handle that targets it. [message] is the state when the live list was last published. */
data class ListenTogetherChatEntry(
    val handle: ListenTogetherChatMessageHandle,
    val message: ChatMessagePayload,
)

/** A room member whose typing indicator stays visible until [expiresAtMs]. */
data class ListenTogetherTypingUser(
    val userId: String,
    val username: String,
    val expiresAtMs: Long,
)

/**
 * Storage for the chat history document. The owner builds the JSON itself, so every host stores the same text and a
 * document written by one host reads on another. Methods may suspend. The host adapter moves blocking I/O to its own
 * IO context, because the owner calls these on its single-thread scope. `write(null)` removes the stored history.
 */
interface ListenTogetherChatHistoryStore {
    suspend fun read(): String?
    suspend fun write(document: String?)
}

/**
 * The persisted chat document, keyed by the username that produced it. [version] gates the persistence scheme, so a
 * history written by an older scheme (which may hold messages the current rules would never store) is discarded.
 */
@Serializable
internal data class PersistedChatHistory(
    val version: Int = CURRENT_VERSION,
    val username: String,
    val messages: List<ChatMessagePayload>,
) {
    companion object {
        /** 1: unfiltered history. 2: solo messages are never persisted. */
        const val CURRENT_VERSION = 2
    }
}

/** Pure rules for the persisted chat document and for the restore merge. */
internal object ListenTogetherChatHistory {
    // Same JSON settings as the Android history, so a default-valued field is omitted and a missing one reads back as
    // its default. Unknown keys are ignored, so a newer host's extra fields do not break an older read.
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The document for [messages], or null when nothing worth keeping remains. Only the newest
     * [ListenTogetherChatTiming.MAX_PERSISTED_MESSAGES] are considered. Solo messages are dropped after that cut, so
     * the window's size does not depend on how many solo messages it held.
     */
    fun encode(username: String, messages: List<ChatMessagePayload>): String? {
        val kept = messages.takeLast(ListenTogetherChatTiming.MAX_PERSISTED_MESSAGES).filterNot { it.solo }
        if (kept.isEmpty()) return null
        val document = PersistedChatHistory(username = username, messages = kept)
        return json.encodeToString(PersistedChatHistory.serializer(), document)
    }

    /** The parsed document, or null when the text is not a readable chat history. */
    fun decode(raw: String): PersistedChatHistory? =
        runCatching { json.decodeFromString(PersistedChatHistory.serializer(), raw) }.getOrNull()

    /**
     * The stored messages that belong in this room: the local user's own, plus those from the members present now.
     * History with members who are not present stays hidden until they join. The user ids are remapped to the current
     * session, so own-message alignment, avatars, reactions, pins, edits and deletes work on restored messages exactly
     * as on live ones. Every returned message carries [ChatMessagePayload.restored].
     */
    fun restorable(
        stored: PersistedChatHistory,
        username: String,
        myUserId: String?,
        otherMembers: List<UserInfo>,
    ): List<ChatMessagePayload> {
        val memberIdByName = otherMembers.associate { it.username to it.userId }
        return stored.messages
            .filter { it.username == username || it.username in memberIdByName }
            .map { message ->
                val currentId = if (message.username == username) myUserId else memberIdByName[message.username]
                if (currentId != null && currentId != message.userId) {
                    message.copy(userId = currentId, restored = true)
                } else {
                    message.copy(restored = true)
                }
            }
    }

    /**
     * The restored messages that are not already in [existing], merged by timestamp. A mid-session restore (a member
     * joins after the local user already chatted) lands in its true position, not on top. Null when nothing is new.
     * Messages match by username and timestamp, as the Android merge does.
     */
    fun mergeFresh(existing: List<ChatMessagePayload>, restored: List<ChatMessagePayload>): List<ChatMessagePayload>? {
        val existingKeys = existing.mapTo(HashSet()) { it.username to it.timestamp }
        val fresh = restored.filterNot { (it.username to it.timestamp) in existingKeys }
        if (fresh.isEmpty()) return null
        return (fresh + existing).sortedBy { it.timestamp }
    }
}
