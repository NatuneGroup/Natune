/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.settings

import kotlinx.serialization.json.Json

/** Defaults the Listen Together settings page and its host must agree on. */
object ListenTogetherSettingsDefaults {
    const val AVATAR_INDEX = 0
    const val AUTO_APPROVAL = false
    const val SUGGESTION_AUTO_APPROVE = true
    const val CHAT_NOTIFICATIONS = true
    const val SYNC_HOST_VOLUME = false
    const val SMART_RESYNC = true

    /** Preset avatar slots, indexes 0 until this value. */
    const val PRESET_AVATAR_COUNT = 14

    /**
     * Slot for a picture the host saved on this device. Picture bytes never enter preferences; the host writes this
     * index only after the file is stored.
     */
    const val CUSTOM_AVATAR_INDEX = PRESET_AVATAR_COUNT
}

/** A server from the approved transport list, as the host reports it. The protocol stays with the transport owner. */
data class ListenTogetherServerOption(
    val name: String,
    val url: String,
    val location: String,
    val operator: String,
)

/** This device's role in the active room. NONE means no room is active. */
enum class ListenTogetherSettingsRole { NONE, HOST, GUEST }

enum class ListenTogetherSettingsLogLevel { DEBUG, INFO, WARNING, ERROR }

/** One connection-log line for the debug list. The host formats [timeText] and maps its own log levels. */
data class ListenTogetherSettingsLogLine(
    val timeText: String,
    val level: ListenTogetherSettingsLogLevel,
    val message: String,
    val details: String? = null,
)

fun PreferenceSnapshot.listenTogetherUsername(): String = this[ListenTogetherUsernameKey, ""].trim()

fun PreferenceSnapshot.listenTogetherAvatarIndex(): Int =
    this[ListenTogetherAvatarIndexKey, ListenTogetherSettingsDefaults.AVATAR_INDEX]
        .takeIf { it in 0..ListenTogetherSettingsDefaults.CUSTOM_AVATAR_INDEX }
        ?: ListenTogetherSettingsDefaults.AVATAR_INDEX

/** The stored server, or [defaultUrl] (the first approved server) when nothing usable is stored. */
fun PreferenceSnapshot.listenTogetherServerUrl(defaultUrl: String): String =
    this[ListenTogetherServerUrlKey, defaultUrl].trim().ifEmpty { defaultUrl }

fun PreferenceSnapshot.listenTogetherBlockedUsers(): List<String> =
    decodeListenTogetherBlockedUsers(this[ListenTogetherBlockedUsersKey, ""])

/** Blocked usernames are a JSON string array, the same wire form the baseline store wrote. */
fun decodeListenTogetherBlockedUsers(raw: String): List<String> {
    if (raw.isBlank()) return emptyList()
    return runCatching { Json.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList()).distinct()
}

fun encodeListenTogetherBlockedUsers(users: Collection<String>): String = Json.encodeToString(users.distinct())

/** Name and avatar are fixed while this device is in a room, as in the baseline page. */
fun listenTogetherIdentityEditable(role: ListenTogetherSettingsRole): Boolean =
    role == ListenTogetherSettingsRole.NONE

/** Guests cannot change approval policy inside a room they did not create. */
fun listenTogetherApprovalEditable(role: ListenTogetherSettingsRole): Boolean =
    role != ListenTogetherSettingsRole.GUEST

fun listenTogetherServerLabel(url: String, servers: List<ListenTogetherServerOption>): String =
    servers.firstOrNull { it.url == url }?.let { "${it.name} - ${it.location}" } ?: url

fun PreferenceEditor.commitListenTogetherUsername(name: String) {
    this[ListenTogetherUsernameKey] = name.trim()
}

fun PreferenceEditor.commitListenTogetherServerUrl(url: String) {
    this[ListenTogetherServerUrlKey] = url.trim()
}

fun PreferenceEditor.commitListenTogetherAvatar(index: Int) {
    require(index in 0..ListenTogetherSettingsDefaults.CUSTOM_AVATAR_INDEX)
    this[ListenTogetherAvatarIndexKey] = index
}
