package moe.rukamori.archivetune.shared.ui.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.rukamori.archivetune.shared.domain.settings.*

data class SettingsRestartState(val visible: Boolean = false, val reasons: Set<RestartReason> = emptySet())

/** One controller per settings host; search, page rows and the one restart modal share it. */
class SettingsController(
    val store: SettingsStore,
    runningPreferences: PreferenceSnapshot,
    private val bitPerfectSupported: Boolean,
    private val bitPerfectRestartRequired: Boolean = bitPerfectSupported,
) {
    private val lock = Mutex()
    private val running = restartSettingValues(runningPreferences, bitPerfectSupported && bitPerfectRestartRequired)
    private val prompt = RestartPromptPolicy()
    private val state = MutableStateFlow(SettingsRestartState())
    val restart = state.asStateFlow()

    suspend fun edit(change: PreferenceEditor.() -> Unit): SettingsChange = lock.withLock {
        val committed = store.edit(change)
        prompt.committed(
            restartSettingValues(committed.before, bitPerfectSupported && bitPerfectRestartRequired),
            restartSettingValues(committed.after, bitPerfectSupported && bitPerfectRestartRequired), running,
        )
        publish()
        committed
    }

    suspend fun interfaceStyle(style: InterfaceStyle): SettingsChange = edit { applyInterfaceStyle(style) }
    suspend fun <T : Any> set(key: PreferenceKey<T>, value: T?): SettingsChange = edit { this[key] = value }

    suspend fun later() = lock.withLock { prompt.later(); publish() }

    /** Consumed before invoking a real host restart; duplicate confirms cannot relaunch twice. */
    suspend fun restartNow(busy: Boolean, restartHost: suspend () -> Unit): Boolean {
        val consumed = lock.withLock { prompt.consumeRestart(busy).also { publish() } }
        if (consumed) restartHost()
        return consumed
    }

    suspend fun storageCommitted(token: String) = lock.withLock { prompt.storageCommitted(token); publish() }
    private fun publish() { state.value = SettingsRestartState(prompt.visible, prompt.reasons) }
}
