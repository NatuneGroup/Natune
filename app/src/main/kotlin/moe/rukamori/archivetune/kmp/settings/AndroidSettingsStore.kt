/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.kmp.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.ui.settings.requireCredentialFreePersistence

/** Host passes the existing application DataStore, never creates a second file or Hilt graph. */
class AndroidSettingsStore(
    private val dataStore: DataStore<Preferences>,
    scope: CoroutineScope,
) : SettingsStore {
    private val state = MutableStateFlow<PreferenceSnapshot?>(null)
    override val snapshots = state.asStateFlow()

    init {
        scope.launch { dataStore.data.collect { state.value = it.sharedSnapshot() } }
    }

    override suspend fun edit(change: PreferenceEditor.() -> Unit): SettingsChange {
        var committed: SettingsChange? = null
        dataStore.updateData { native ->
            val before = native.sharedSnapshot()
            val after = before.toEditor().apply(change).snapshot()
            requireCredentialFreePersistence(after)
            val result = SettingsChange(before, after)
            native.toMutablePreferences().apply {
                result.changedKeys.forEach { key ->
                    @Suppress("UNCHECKED_CAST")
                    val nativeKey = key.nativeKey() as Preferences.Key<Any>
                    val value = after[key]
                    if (value == null) remove(nativeKey) else this[nativeKey] = value
                }
                committed = result
            }
        }
        return checkNotNull(committed)
    }
}

private fun Preferences.sharedSnapshot(): PreferenceSnapshot = PreferenceSnapshot.of(
    *asMap().map { (key, value) ->
        val shared = when (value) {
            is Boolean -> BooleanPreferenceKey(key.name)
            is Int -> IntPreferenceKey(key.name)
            is Long -> LongPreferenceKey(key.name)
            is Float -> FloatPreferenceKey(key.name)
            is Double -> DoublePreferenceKey(key.name)
            is String -> StringPreferenceKey(key.name)
            is Set<*> -> StringSetPreferenceKey(key.name)
            else -> error("Unsupported preference kind")
        }
        shared to value
    }.toTypedArray(),
)

private fun PreferenceKey<*>.nativeKey(): Preferences.Key<*> = when (kind) {
    PreferenceKind.BOOLEAN -> booleanPreferencesKey(name)
    PreferenceKind.STRING -> stringPreferencesKey(name)
    PreferenceKind.FLOAT -> floatPreferencesKey(name)
    PreferenceKind.INT -> intPreferencesKey(name)
    PreferenceKind.LONG -> longPreferencesKey(name)
    PreferenceKind.DOUBLE -> doublePreferencesKey(name)
    PreferenceKind.STRING_SET -> stringSetPreferencesKey(name)
}
