/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.ui.component

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import moe.rukamori.archivetune.constants.*
import moe.rukamori.archivetune.playback.BitPerfectUsbOutput
import moe.rukamori.archivetune.utils.PreferenceSnapshot
import moe.rukamori.archivetune.utils.RestartScopedSettings
import moe.rukamori.archivetune.utils.SnapshotPreferenceState
import moe.rukamori.archivetune.utils.applySharedPreferenceEdit
import moe.rukamori.archivetune.utils.setFontPreference
import moe.rukamori.archivetune.shared.domain.settings.BooleanPreferenceKey
import moe.rukamori.archivetune.shared.domain.settings.applyNavigationTreatment
import moe.rukamori.archivetune.shared.domain.settings.setPlaybackOutputPreference
import moe.rukamori.archivetune.shared.domain.settings.SettingsChange
import moe.rukamori.archivetune.shared.domain.settings.needsBitPerfectSinkRelease
import moe.rukamori.archivetune.kmp.settings.sharedSnapshot
import moe.rukamori.archivetune.viewmodels.RestartPromptViewModel

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
        applySharedPreferenceEdit { setPlaybackOutputPreference(BooleanPreferenceKey(key.name), enabled) }
    }
}
