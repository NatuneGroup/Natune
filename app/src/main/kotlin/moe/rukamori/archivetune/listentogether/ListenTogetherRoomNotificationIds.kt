package moe.rukamori.archivetune.listentogether

import android.content.Context
import androidx.core.app.NotificationManagerCompat

/**
 * Notification ids for the host-only room prompts (join requests and suggestions).
 * Socket callbacks record them while Compose UI and the notification-action receiver
 * dismiss them from other threads, so every map access is serialized here.
 * [drain] ends a room without leaving ids behind for the next one.
 */
internal class ListenTogetherRoomNotificationIds {
    private val lock = Any()
    private val joinRequests = mutableMapOf<String, Int>()
    private val suggestions = mutableMapOf<String, Int>()

    fun trackJoinRequest(userId: String, notificationId: Int) {
        synchronized(lock) { joinRequests[userId] = notificationId }
    }

    fun forgetJoinRequest(userId: String): Int? = synchronized(lock) { joinRequests.remove(userId) }

    fun trackSuggestion(suggestionId: String, notificationId: Int) {
        synchronized(lock) { suggestions[suggestionId] = notificationId }
    }

    fun forgetSuggestion(suggestionId: String): Int? = synchronized(lock) { suggestions.remove(suggestionId) }

    /** Cancels every tracked prompt of the room that is closing. */
    fun drainAndCancel(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        drainAndCancel(manager::cancel)
    }

    /** Cancels every tracked prompt through [cancel]. */
    fun drainAndCancel(cancel: (Int) -> Unit) {
        drain().forEach(cancel)
    }

    /** Forgets and returns every tracked id; the caller owns their cancellation. */
    fun drain(): List<Int> =
        synchronized(lock) {
            val tracked = joinRequests.values + suggestions.values
            joinRequests.clear()
            suggestions.clear()
            tracked
        }
}
