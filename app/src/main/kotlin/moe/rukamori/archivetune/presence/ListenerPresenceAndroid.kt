package moe.rukamori.archivetune.presence

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.preferencesDataStore
import androidx.datastore.preferences.core.stringPreferencesKey
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import moe.rukamori.archivetune.BuildConfig
import moe.rukamori.archivetune.shared.data.presence.KtorListenerPresenceTransport
import moe.rukamori.archivetune.shared.domain.presence.ListenerPresenceController
import moe.rukamori.archivetune.shared.domain.presence.ListenerPresenceKeys
import moe.rukamori.archivetune.shared.domain.presence.ListenerPresencePlatform
import moe.rukamori.archivetune.shared.domain.presence.ListenerPresencePolicy
import moe.rukamori.archivetune.shared.domain.presence.ListenerPresenceState
import moe.rukamori.archivetune.shared.domain.presence.ListenerPresenceStore

/**
 * A separate DataStore file rather than the main settings store. The backup rules exclude this file, so the random
 * listener ID never reaches Android backup or device transfer.
 */
private val Context.listenerPresenceDataStore: DataStore<Preferences> by preferencesDataStore(name = "listener_presence")

private val enabledKey = booleanPreferencesKey(ListenerPresenceKeys.ENABLED)
private val listenerIdKey = stringPreferencesKey(ListenerPresenceKeys.LISTENER_ID)
private val consentVersionKey = intPreferencesKey(ListenerPresenceKeys.CONSENT_VERSION)

internal class DataStoreListenerPresenceStore(
    private val dataStore: DataStore<Preferences>,
    private val newListenerId: () -> String,
) : ListenerPresenceStore {
    override val state: Flow<ListenerPresenceState> =
        dataStore.data.map { prefs -> prefs.toState() }.distinctUntilChanged()

    override suspend fun enable() {
        dataStore.edit { prefs -> prefs.write(ListenerPresencePolicy.enable(prefs.toState(), newListenerId)) }
    }

    override suspend fun disable() {
        dataStore.edit { prefs -> prefs.write(ListenerPresencePolicy.disable()) }
    }

    override suspend fun resetId() {
        dataStore.edit { prefs -> prefs.write(ListenerPresencePolicy.resetId(prefs.toState(), newListenerId)) }
    }

    private fun Preferences.toState(): ListenerPresenceState =
        ListenerPresenceState(
            enabled = this[enabledKey] ?: false,
            listenerId = this[listenerIdKey],
            consentVersion = this[consentVersionKey] ?: 0,
        )

    private fun MutablePreferences.write(state: ListenerPresenceState) {
        this[enabledKey] = state.enabled
        val id = state.listenerId
        if (id == null) {
            remove(listenerIdKey)
        } else {
            this[listenerIdKey] = id
        }
        this[consentVersionKey] = state.consentVersion
    }
}

/**
 * The Android host for listener presence. [start] runs one playback-driven heartbeat loop for the process, and the
 * playback service reports play state through [onPlayingChanged]. Nothing is sent unless the user has opted in and the
 * build carries an HTTPS endpoint. The loop only runs while music plays.
 */
object ListenerPresenceAndroid {
    private val playing = MutableStateFlow(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val secureRandom = SecureRandom()
    private val httpClient: HttpClient by lazy { HttpClient(OkHttp) }
    private var storeInstance: ListenerPresenceStore? = null
    private var controller: ListenerPresenceController? = null

    /** The single opt-in store for this process. */
    fun store(context: Context): ListenerPresenceStore =
        synchronized(this) {
            storeInstance
                ?: DataStoreListenerPresenceStore(
                    dataStore = context.applicationContext.listenerPresenceDataStore,
                    newListenerId = {
                        ListenerPresencePolicy.newListenerId { count -> ByteArray(count).also { secureRandom.nextBytes(it) } }
                    },
                ).also { storeInstance = it }
        }

    /** Starts the heartbeat loop once. Later calls do nothing. */
    fun start(context: Context) {
        synchronized(this) {
            if (controller != null) return
            val transport = KtorListenerPresenceTransport(httpClient, BuildConfig.ARCHIVETUNE_LISTENER_PRESENCE_URL)
            controller =
                ListenerPresenceController(
                    scope = scope,
                    store = store(context),
                    transport = transport,
                    playing = playing,
                    platform = ListenerPresencePlatform.ANDROID,
                    appVersion = ListenerPresencePolicy.sanitizedAppVersion(BuildConfig.VERSION_NAME),
                ).also { it.start() }
        }
    }

    /** Called from the playback service whenever the player's playing state changes. */
    fun onPlayingChanged(isPlaying: Boolean) {
        playing.value = isPlaying
    }
}
