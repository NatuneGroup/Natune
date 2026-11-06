package moe.rukamori.archivetune.utils

import androidx.compose.runtime.MutableState
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import moe.rukamori.archivetune.constants.AppFontPreference
import moe.rukamori.archivetune.constants.PlayerBackgroundStyle
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.domain.settings.AppFontPreference as SharedFont
import moe.rukamori.archivetune.shared.domain.settings.setFontPreference as setSharedFont
import moe.rukamori.archivetune.shared.domain.settings.setPlayerBackgroundStyle as setSharedPlayerBackground
import moe.rukamori.archivetune.shared.domain.settings.setCacheCapacity as setSharedCacheCapacity
import moe.rukamori.archivetune.shared.domain.settings.setCustomThemeColor as setSharedThemeColor
import moe.rukamori.archivetune.shared.domain.settings.applyStartupRandomTheme as applySharedStartupRandomTheme

/** Direct rows and search use the same atomic owner, evaluated against the latest stored values. */
internal fun MutablePreferences.setConflictAwareBoolean(key: Preferences.Key<Boolean>, enabled: Boolean) {
    applySharedPreferenceEdit {
        val shared = BooleanPreferenceKey(key.name)
        when (shared) {
            DynamicThemeKey -> setDynamicTheme(enabled)
            RandomThemeOnStartupKey -> setRandomThemeOnStartup(enabled)
            RejectSuspectedUpscaledAudioKey -> setAuthenticityRejection(enabled)
            in audioProcessingPreferenceKeys -> setAudioProcessingPreference(enabled)
            SkipSilenceKey, AudioNormalizationKey -> setPcmProcessingPreference(shared, enabled)
            SmartTrimmerKey -> setSmartTrimmer(enabled)
            else -> this[shared] = enabled
        }
    }
}

internal fun PreferenceSnapshot.conflictAwareState(key: Preferences.Key<Boolean>, defaultValue: Boolean): MutableState<Boolean> =
    SnapshotPreferenceState(
        read = { this[key, defaultValue] },
        write = { value -> PreferenceStore.launchEdit(dataStore) { setConflictAwareBoolean(key, value) } },
    )

internal fun MutablePreferences.setFontPreference(font: AppFontPreference) {
    applySharedPreferenceEdit { setSharedFont(SharedFont.valueOf(font.name)) }
}

internal fun MutablePreferences.setPlayerBackgroundStyle(style: PlayerBackgroundStyle) {
    applySharedPreferenceEdit { setSharedPlayerBackground(style.name) }
}

internal fun MutablePreferences.setCacheCapacity(key: Preferences.Key<Int>, capacity: Int) {
    applySharedPreferenceEdit { setSharedCacheCapacity(IntPreferenceKey(key.name), capacity) }
}

internal fun PreferenceSnapshot.cacheCapacityState(key: Preferences.Key<Int>, defaultValue: Int): MutableState<Int> =
    SnapshotPreferenceState(
        read = { this[key, defaultValue] },
        write = { value -> PreferenceStore.launchEdit(dataStore) { setCacheCapacity(key, value) } },
    )

internal fun MutablePreferences.setCustomThemeColor(value: String) {
    applySharedPreferenceEdit { setSharedThemeColor(value) }
}

internal fun PreferenceSnapshot.customThemeColorState(defaultValue: String): MutableState<String> =
    SnapshotPreferenceState(
        read = { this[moe.rukamori.archivetune.constants.CustomThemeColorKey, defaultValue] },
        write = { value -> PreferenceStore.launchEdit(dataStore) { setCustomThemeColor(value) } },
    )

internal fun MutablePreferences.applyStartupRandomTheme(expectedColor: String?, generatedColor: String) {
    applySharedPreferenceEdit { applySharedStartupRandomTheme(expectedColor, generatedColor) }
}
