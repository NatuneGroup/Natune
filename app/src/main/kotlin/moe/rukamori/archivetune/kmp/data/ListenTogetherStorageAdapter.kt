/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.kmp.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import moe.rukamori.archivetune.constants.ListenTogetherAutoApprovalKey
import moe.rukamori.archivetune.constants.ListenTogetherAvatarIndexKey
import moe.rukamori.archivetune.constants.ListenTogetherBlockedUsersKey
import moe.rukamori.archivetune.constants.ListenTogetherChatHistoryKey
import moe.rukamori.archivetune.constants.ListenTogetherIsHostKey
import moe.rukamori.archivetune.constants.ListenTogetherRoomCodeKey
import moe.rukamori.archivetune.constants.ListenTogetherServerUrlKey
import moe.rukamori.archivetune.constants.ListenTogetherSessionTimestampKey
import moe.rukamori.archivetune.constants.ListenTogetherSessionTokenKey
import moe.rukamori.archivetune.constants.ListenTogetherSuggestionAutoApproveKey
import moe.rukamori.archivetune.constants.ListenTogetherUserIdKey
import moe.rukamori.archivetune.shared.data.listentogether.ListenTogetherChatHistoryStore
import moe.rukamori.archivetune.shared.data.listentogether.ListenTogetherPreferences
import moe.rukamori.archivetune.shared.data.listentogether.ListenTogetherServers
import moe.rukamori.archivetune.shared.data.listentogether.ListenTogetherSession
import moe.rukamori.archivetune.shared.data.listentogether.ListenTogetherStore
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.get
import timber.log.Timber

private const val TAG = "ListenTogetherStorage"

/**
 * The reconnect session and the blocked-user list, on the same DataStore keys the retained Android client uses.
 * A session written here restores in either client, and the blocked list keeps its JSON string-list format.
 *
 * A record missing its token, room code or user id is treated as nothing stored. The shared client applies the
 * ten-minute grace period itself from [ListenTogetherSession.savedAtMs], so this store returns an old record as is.
 * Reads and writes run on IO. Failures are logged and never thrown to the client, which keeps its in-memory state.
 */
class DataStoreListenTogetherStore(context: Context) : ListenTogetherStore {
    private val dataStore = context.applicationContext.dataStore
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun loadSession(): ListenTogetherSession? = withContext(Dispatchers.IO) {
        try {
            val prefs = dataStore.data.first()
            val token = prefs[ListenTogetherSessionTokenKey].orEmpty()
            val roomCode = prefs[ListenTogetherRoomCodeKey].orEmpty()
            val userId = prefs[ListenTogetherUserIdKey].orEmpty()
            if (token.isBlank() || roomCode.isBlank() || userId.isBlank()) return@withContext null
            ListenTogetherSession(
                sessionToken = token,
                roomCode = roomCode,
                userId = userId,
                isHost = prefs[ListenTogetherIsHostKey] == true,
                savedAtMs = prefs[ListenTogetherSessionTimestampKey] ?: 0L,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failed: Exception) {
            Timber.tag(TAG).e(failed, "Listen Together session could not be read")
            null
        }
    }

    override suspend fun saveSession(session: ListenTogetherSession) {
        guardedWrite("Listen Together session could not be saved") {
            dataStore.edit { prefs ->
                prefs[ListenTogetherSessionTokenKey] = session.sessionToken
                prefs[ListenTogetherRoomCodeKey] = session.roomCode
                prefs[ListenTogetherUserIdKey] = session.userId
                prefs[ListenTogetherIsHostKey] = session.isHost
                prefs[ListenTogetherSessionTimestampKey] = session.savedAtMs
            }
        }
    }

    override suspend fun clearSession() {
        guardedWrite("Listen Together session could not be cleared") {
            dataStore.edit { prefs ->
                prefs.remove(ListenTogetherSessionTokenKey)
                prefs.remove(ListenTogetherRoomCodeKey)
                prefs.remove(ListenTogetherUserIdKey)
                prefs.remove(ListenTogetherIsHostKey)
                prefs.remove(ListenTogetherSessionTimestampKey)
            }
        }
    }

    override suspend fun loadBlockedUsernames(): Set<String> = withContext(Dispatchers.IO) {
        try {
            val raw = dataStore.data.first()[ListenTogetherBlockedUsersKey].orEmpty()
            if (raw.isEmpty()) emptySet<String>() else json.decodeFromString<List<String>>(raw).toSet()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failed: Exception) {
            Timber.tag(TAG).e(failed, "Blocked usernames could not be read")
            emptySet<String>()
        }
    }

    override suspend fun saveBlockedUsernames(usernames: Set<String>) {
        guardedWrite("Blocked usernames could not be saved") {
            val encoded = json.encodeToString<List<String>>(usernames.toList())
            dataStore.edit { prefs -> prefs[ListenTogetherBlockedUsersKey] = encoded }
        }
    }

    private suspend fun guardedWrite(message: String, write: suspend () -> Unit) {
        try {
            withContext(Dispatchers.IO) { write() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failed: Exception) {
            Timber.tag(TAG).e(failed, message)
        }
    }
}

/**
 * The room chat history document, stored as the same DataStore string the retained Android client writes, so a
 * conversation saved by either client reads back in the other. The owner builds and parses the JSON itself, so this
 * adapter only moves text. Read and write failures reach the owner, which reports them as diagnostics.
 */
class DataStoreListenTogetherChatHistoryStore(context: Context) : ListenTogetherChatHistoryStore {
    private val dataStore = context.applicationContext.dataStore

    override suspend fun read(): String? = withContext(Dispatchers.IO) {
        dataStore.data.first()[ListenTogetherChatHistoryKey]
    }

    override suspend fun write(document: String?) {
        withContext(Dispatchers.IO) {
            dataStore.edit { prefs ->
                prefs.remove(ListenTogetherChatHistoryKey)
                if (document != null) prefs[ListenTogetherChatHistoryKey] = document
            }
        }
    }
}

/**
 * The Listen Together settings the shared client reads at the moment it needs them. The reads go through the app's
 * process-wide preference snapshot, which the settings screen writes too, so they agree with what the user sees and do
 * not wait on the disk on the client's path. The defaults match the retained Android client: the first allow-listed
 * server, host join approval off, suggestion approval on, and avatar 0.
 */
class DataStoreListenTogetherPreferences(context: Context) : ListenTogetherPreferences {
    private val appContext = context.applicationContext

    override fun serverUrl(): String =
        appContext.dataStore.get(ListenTogetherServerUrlKey, ListenTogetherServers.defaultServerUrl)

    override fun autoApproveJoinRequests(): Boolean = appContext.dataStore.get(ListenTogetherAutoApprovalKey, false)

    override fun autoApproveSuggestions(): Boolean =
        appContext.dataStore.get(ListenTogetherSuggestionAutoApproveKey, true)

    override fun avatarIndex(): Int = appContext.dataStore.get(ListenTogetherAvatarIndexKey, 0)
}
