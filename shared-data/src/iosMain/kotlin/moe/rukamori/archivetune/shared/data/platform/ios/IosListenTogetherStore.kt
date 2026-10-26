package moe.rukamori.archivetune.shared.data.platform.ios

import kotlinx.coroutines.CancellationException
import moe.rukamori.archivetune.shared.data.listentogether.ListenTogetherPreferences
import moe.rukamori.archivetune.shared.data.listentogether.ListenTogetherServers
import moe.rukamori.archivetune.shared.data.listentogether.ListenTogetherSession
import moe.rukamori.archivetune.shared.data.listentogether.ListenTogetherStore
import moe.rukamori.archivetune.shared.domain.settings.ListenTogetherAutoApprovalKey
import moe.rukamori.archivetune.shared.domain.settings.ListenTogetherBlockedUsersKey
import moe.rukamori.archivetune.shared.domain.settings.ListenTogetherIsHostKey
import moe.rukamori.archivetune.shared.domain.settings.ListenTogetherRoomCodeKey
import moe.rukamori.archivetune.shared.domain.settings.ListenTogetherSessionTimestampKey
import moe.rukamori.archivetune.shared.domain.settings.ListenTogetherSessionTokenKey
import moe.rukamori.archivetune.shared.domain.settings.ListenTogetherSettingsDefaults
import moe.rukamori.archivetune.shared.domain.settings.ListenTogetherSuggestionAutoApproveKey
import moe.rukamori.archivetune.shared.domain.settings.ListenTogetherUserIdKey
import moe.rukamori.archivetune.shared.domain.settings.SettingsStore
import moe.rukamori.archivetune.shared.domain.settings.encodeListenTogetherBlockedUsers
import moe.rukamori.archivetune.shared.domain.settings.listenTogetherAvatarIndex
import moe.rukamori.archivetune.shared.domain.settings.listenTogetherBlockedUsers
import moe.rukamori.archivetune.shared.domain.settings.listenTogetherServerUrl

/**
 * The Listen Together preference values the shared client reads synchronously on iOS, sourced from the vault-backed
 * settings owner like every other host. Until a snapshot exists each value answers with its baseline default, so an
 * early read cannot invent a different policy. The client filters the saved server through its own allow-list.
 */
class IosListenTogetherPreferences(
    private val settings: SettingsStore,
) : ListenTogetherPreferences {

    override fun serverUrl(): String =
        settings.snapshots.value?.listenTogetherServerUrl(ListenTogetherServers.defaultServerUrl)
            ?: ListenTogetherServers.defaultServerUrl

    override fun autoApproveJoinRequests(): Boolean =
        settings.snapshots.value?.get(
            ListenTogetherAutoApprovalKey,
            ListenTogetherSettingsDefaults.AUTO_APPROVAL,
        ) ?: ListenTogetherSettingsDefaults.AUTO_APPROVAL

    override fun autoApproveSuggestions(): Boolean =
        settings.snapshots.value?.get(
            ListenTogetherSuggestionAutoApproveKey,
            ListenTogetherSettingsDefaults.SUGGESTION_AUTO_APPROVE,
        ) ?: ListenTogetherSettingsDefaults.SUGGESTION_AUTO_APPROVE

    override fun avatarIndex(): Int =
        settings.snapshots.value?.listenTogetherAvatarIndex() ?: ListenTogetherSettingsDefaults.AVATAR_INDEX
}

/**
 * The Listen Together reconnect session and blocked-user list on iOS. The session token is a vault-routed
 * credential, so [settings] must be the vault-backed logical store; a refused write is logged and dropped rather
 * than retried into a plaintext location. Mirrors the retained Android store: an incomplete record reads as nothing
 * stored and a failed write never reaches the client, which keeps its in-memory room.
 */
class IosListenTogetherStore(
    private val settings: SettingsStore,
    private val log: (String) -> Unit,
) : ListenTogetherStore {

    override suspend fun loadSession(): ListenTogetherSession? {
        val snapshot = settings.snapshots.value ?: run {
            log("Listen Together session could not be read: settings snapshot not open")
            return null
        }
        val token = snapshot[ListenTogetherSessionTokenKey].orEmpty()
        val roomCode = snapshot[ListenTogetherRoomCodeKey].orEmpty()
        val userId = snapshot[ListenTogetherUserIdKey].orEmpty()
        if (token.isBlank() || roomCode.isBlank() || userId.isBlank()) return null
        return ListenTogetherSession(
            sessionToken = token,
            roomCode = roomCode,
            userId = userId,
            isHost = snapshot[ListenTogetherIsHostKey] == true,
            savedAtMs = snapshot[ListenTogetherSessionTimestampKey] ?: 0L,
        )
    }

    override suspend fun saveSession(session: ListenTogetherSession) {
        guardedWrite("Listen Together session could not be saved") {
            settings.edit {
                this[ListenTogetherSessionTokenKey] = session.sessionToken
                this[ListenTogetherRoomCodeKey] = session.roomCode
                this[ListenTogetherUserIdKey] = session.userId
                this[ListenTogetherIsHostKey] = session.isHost
                this[ListenTogetherSessionTimestampKey] = session.savedAtMs
            }
        }
    }

    override suspend fun clearSession() {
        guardedWrite("Listen Together session could not be cleared") {
            settings.edit {
                this[ListenTogetherSessionTokenKey] = null
                this[ListenTogetherRoomCodeKey] = null
                this[ListenTogetherUserIdKey] = null
                this[ListenTogetherIsHostKey] = null
                this[ListenTogetherSessionTimestampKey] = null
            }
        }
    }

    override suspend fun loadBlockedUsernames(): Set<String> =
        settings.snapshots.value?.listenTogetherBlockedUsers().orEmpty().toSet()

    override suspend fun saveBlockedUsernames(usernames: Set<String>) {
        guardedWrite("Listen Together blocked list could not be saved") {
            settings.edit {
                this[ListenTogetherBlockedUsersKey] = encodeListenTogetherBlockedUsers(usernames)
            }
        }
    }

    private suspend fun guardedWrite(notice: String, action: suspend () -> Unit) {
        try {
            action()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failed: Throwable) {
            log("$notice: ${failed.message ?: failed::class.simpleName}")
        }
    }
}
