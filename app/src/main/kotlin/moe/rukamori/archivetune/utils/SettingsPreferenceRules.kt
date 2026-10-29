package moe.rukamori.archivetune.utils

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import moe.rukamori.archivetune.kmp.settings.nativeKey
import moe.rukamori.archivetune.kmp.settings.sharedSnapshot
import moe.rukamori.archivetune.shared.domain.settings.PreferenceEditor
import moe.rukamori.archivetune.shared.domain.settings.SettingsChange

/** Apply shared, pure preference rules without replacing unknown native or account settings. */
internal fun Preferences.editSharedPreferences(change: PreferenceEditor.() -> Unit): Preferences {
    return toMutablePreferences().apply { applySharedPreferenceEdit(change) }
}

internal fun MutablePreferences.applySharedPreferenceEdit(change: PreferenceEditor.() -> Unit) {
    val before = sharedSnapshot()
    val after = before.toEditor().apply(change).snapshot()
    SettingsChange(before, after).changedKeys.forEach { key ->
        @Suppress("UNCHECKED_CAST")
        val native = key.nativeKey() as Preferences.Key<Any>
        val value = after[key]
        if (value == null) remove(native) else this[native] = value
    }
}
