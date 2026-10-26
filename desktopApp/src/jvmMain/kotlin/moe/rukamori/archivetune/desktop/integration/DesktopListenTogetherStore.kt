/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.desktop.integration

import kotlinx.coroutines.CancellationException
import moe.rukamori.archivetune.shared.data.listentogether.ListenTogetherSession
import moe.rukamori.archivetune.shared.data.listentogether.ListenTogetherStore
import moe.rukamori.archivetune.shared.domain.settings.ListenTogetherBlockedUsersKey
import moe.rukamori.archivetune.shared.domain.settings.ListenTogetherIsHostKey
import moe.rukamori.archivetune.shared.domain.settings.ListenTogetherRoomCodeKey
import moe.rukamori.archivetune.shared.domain.settings.ListenTogetherSessionTimestampKey
import moe.rukamori.archivetune.shared.domain.settings.ListenTogetherSessionTokenKey
import moe.rukamori.archivetune.shared.domain.settings.ListenTogetherUserIdKey
import moe.rukamori.archivetune.shared.domain.settings.SettingsStore
import moe.rukamori.archivetune.shared.domain.settings.encodeListenTogetherBlockedUsers
import moe.rukamori.archivetune.shared.domain.settings.listenTogetherBlockedUsers

/**
 * The Listen Together reconnect session and the blocked-user list, kept in the host's logical settings owner.
 *
 * The session token is a vault-routed credential, so [settings] must be the vault-backed logical store. The plain
 * desktop preference file refuses that key outright, and a refused write is logged and dropped here rather than
 * retried into a second, plaintext location. The host opens the store before it constructs the client, because a store
 * with no snapshot yet reads as nothing stored.
 *
 * The retained Android store is the behavioural reference: an incomplete record reads as nothing stored, the shared
 * client applies the ten-minute grace period from [ListenTogetherSession.savedAtMs], and a failed write never reaches
 * the client, which keeps its in-memory room.
 */
internal class DesktopListenTogetherStore(
    private val settings: SettingsStore,
    private val log: (String) -> Unit,
) : ListenTogetherStore {

    override suspend fun loadSession(): ListenTogetherSession? {
        val snapshot = settings.snapshots.value ?: run {
            log(NOT_READ)
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

    override suspend fun loadBlockedUsernames(): Set<String> {
        val snapshot = settings.snapshots.value ?: run {
            log(NOT_READ)
            return emptySet()
        }
        return snapshot.listenTogetherBlockedUsers().toSet()
    }

    override suspend fun saveBlockedUsernames(usernames: Set<String>) {
        guardedWrite("Blocked usernames could not be saved") {
            settings.edit { this[ListenTogetherBlockedUsersKey] = encodeListenTogetherBlockedUsers(usernames) }
        }
    }

    /**
     * Cancellation propagates. Any other failure is logged by exception class only, because the message of a failed
     * write can echo the session token it was carrying.
     */
    private suspend fun guardedWrite(message: String, write: suspend () -> Unit) {
        try {
            write()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failed: Exception) {
            log("$message (${failed.javaClass.simpleName})")
        }
    }

    private companion object {
        const val NOT_READ = "Listen Together settings were read before the settings owner had a snapshot"
    }
}
