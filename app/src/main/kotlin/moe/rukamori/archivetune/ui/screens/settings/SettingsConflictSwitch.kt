package moe.rukamori.archivetune.ui.screens.settings

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.*
import moe.rukamori.archivetune.playback.BitPerfectUsbOutput
import moe.rukamori.archivetune.kmp.settings.sharedSnapshot
import moe.rukamori.archivetune.shared.domain.settings.PlayerMenuStyle
import moe.rukamori.archivetune.shared.domain.settings.appleMusicMenus
import moe.rukamori.archivetune.shared.domain.settings.bitPerfectOutputSelected
import moe.rukamori.archivetune.ui.component.LocalRestartPrompt
import moe.rukamori.archivetune.ui.component.navigationTreatmentKeys
import moe.rukamori.archivetune.ui.component.rememberPlayerMenuStyleSetter
import moe.rukamori.archivetune.ui.component.setNavigationTreatment
import moe.rukamori.archivetune.ui.component.setPlaybackOutputPreference
import moe.rukamori.archivetune.utils.conflictAwareState

/** Search is not a route around page dependencies. It keeps saved values visible with the same reason. */
internal fun settingsSwitchDisabledReason(
    key: Preferences.Key<Boolean>,
    preferences: Preferences,
    bitPerfectSinkActive: Boolean,
    blurSupported: Boolean,
    blurOverridden: Boolean = false,
): Int? {
    val bitPerfect = preferences.sharedSnapshot().bitPerfectOutputSelected() || bitPerfectSinkActive
    val blurConsumer = key in navigationTreatmentKeys || key == LiquidGlassEnabledKey || key == BackdropEnabledKey
    return when {
        (blurOverridden || blurConsumer) && preferences[DisableBlurKey] == true -> R.string.blur_setting_overridden
        key in navigationTreatmentKeys && !blurSupported -> R.string.settings_material_unsupported
        key == LiquidGlassNavBarEnabledKey && preferences[LiquidGlassEnabledKey] != true -> R.string.settings_liquid_glass_required
        key in setOf(SkipSilenceKey, AudioNormalizationKey, AudioProcessingEnabledKey,
            RejectSuspectedUpscaledAudioKey) && bitPerfect -> R.string.settings_processing_bypassed_bit_perfect
        key == SkipSilenceKey && preferences[AudioOffload] == true -> R.string.settings_processing_unavailable_offload
        key == AudioOffload && preferences[RejectSuspectedUpscaledAudioKey] == true -> R.string.settings_offload_unavailable_authenticity
        key == DirectUsbDriverKey && (!bitPerfect || !BitPerfectUsbOutput.isSupported) ->
            R.string.direct_usb_driver_requires_bit_perfect
        key == CrossfadeGaplessKey && preferences[CrossfadeEnabledKey] != true -> R.string.settings_crossfade_required
        key == EnablePipModeKey && preferences[EnableVideoPlaybackKey] != true -> R.string.settings_video_required
        key == SmartTrimmerKey && preferences[MaxSongCacheSizeKey] == 0 && preferences[MaxImageCacheSizeKey] == 0 ->
            R.string.settings_caches_required
        else -> null
    }
}

@Composable
internal fun ConflictAwareSearchSwitch(key: Preferences.Key<Boolean>, defaultValue: Boolean, blurOverridden: Boolean) {
    val prefs = LocalSettingsPreferenceSnapshot.current
    val restartPrompt = LocalRestartPrompt.current
    val context = LocalContext.current
    val (storedChecked, setValue) = prefs.conflictAwareState(key, defaultValue)
    val setMenuStyle = rememberPlayerMenuStyleSetter()
    // The menus surface is the effective menu look, so the search result matches the Apple Music page row.
    val checked = if (key == AppleMusicMenusKey) prefs.current.sharedSnapshot().appleMusicMenus() else storedChecked
    val sinkStatus by BitPerfectUsbOutput.status.collectAsStateWithLifecycle()
    val reason = settingsSwitchDisabledReason(key, prefs.current, sinkStatus.let { BitPerfectUsbOutput.sinkActive },
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S, blurOverridden)
    Column(horizontalAlignment = Alignment.End) {
        Switch(checked = checked, enabled = reason == null, onCheckedChange = { enabled ->
            when (key) {
                in navigationTreatmentKeys -> setNavigationTreatment(restartPrompt, prefs, key, enabled)
                LiquidGlassEnabledKey, TabletModeEnabledKey -> restartPrompt.edit(prefs) { this[key] = enabled }
                CrossfadeEnabledKey, AutomixEnabledKey, AudioOffload, BitPerfectUsbOutputKey, UsbExclusiveAudioKey, DirectUsbDriverKey ->
                    setPlaybackOutputPreference(restartPrompt, prefs, context, key, enabled)
                AppleMusicMenusKey -> setMenuStyle(if (enabled) PlayerMenuStyle.APPLE_MUSIC else PlayerMenuStyle.MD3)
                else -> setValue(enabled)
            }
        })
        reason?.let { Text(stringResource(it), style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.widthIn(max = 180.dp)) }
    }
}
