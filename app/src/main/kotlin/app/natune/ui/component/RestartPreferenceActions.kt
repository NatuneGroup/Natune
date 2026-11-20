/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package app.natune.ui.component

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import app.natune.constants.*
import app.natune.playback.BitPerfectUsbOutput
import app.natune.playback.usbdirect.UsbDirectNative
import app.natune.utils.PreferenceSnapshot
import app.natune.utils.RestartScopedSettings
import app.natune.utils.from
import app.natune.utils.SnapshotPreferenceState
import app.natune.utils.applySharedPreferenceEdit
import app.natune.utils.setFontPreference
import app.natune.shared.domain.settings.BooleanPreferenceKey
import app.natune.shared.domain.settings.applyNavigationTreatment
import app.natune.shared.domain.settings.setPlaybackOutputPreference
import app.natune.shared.domain.settings.SettingsChange
import app.natune.shared.domain.settings.needsBitPerfectSinkRelease
import app.natune.kmp.settings.sharedSnapshot
import app.natune.viewmodels.RestartPromptViewModel

val navigationTreatmentKeys = setOf(
    NavigationBarFrostedBlurKey, NavigationBarTintFrostedBlurKey, LiquidGlassNavBarEnabledKey,
)

internal fun MutablePreferences.applyNavigationTreatment(key: Preferences.Key<Boolean>, enabled: Boolean) {
    applySharedPreferenceEdit { applyNavigationTreatment(BooleanPreferenceKey(key.name), enabled) }
}

fun PreferenceSnapshot.canonicalFontState(controller: RestartPromptViewModel): androidx.compose.runtime.MutableState<AppFontPreference> =
    SnapshotPreferenceState(
        read = { RestartScopedSettings.from(current).fontPreference },
        write = { font -> controller.edit(this) { setFontPreference(font) } },
    )

fun setNavigationTreatment(
    controller: RestartPromptViewModel,
    preferences: PreferenceSnapshot,
    key: Preferences.Key<Boolean>,
    enabled: Boolean,
) {
    controller.edit(preferences) {
        applyNavigationTreatment(key, enabled)
    }
}

/** Shared by direct rows and search switches; no restart scope is added to the live DSP engines. */
fun setPlaybackOutputPreference(
    controller: RestartPromptViewModel,
    preferences: PreferenceSnapshot,
    context: Context,
    key: Preferences.Key<Boolean>,
    enabled: Boolean,
) {
    controller.edit(preferences, onCommitted = { result ->
        if (SettingsChange(result.before.sharedSnapshot(), result.after.sharedSnapshot()).needsBitPerfectSinkRelease() &&
            BitPerfectUsbOutput.sinkActive
        ) {
            // Preserve immediate DAC release, but never release it on a failed save.
            BitPerfectUsbOutput.setEnabled(context, false)
        }
    }) {
        applySharedPreferenceEdit {
            setPlaybackOutputPreference(BooleanPreferenceKey(key.name), enabled,
                BitPerfectUsbOutput.isPlatformSupported, UsbDirectNative.available)
        }
    }
}
