package moe.rukamori.archivetune.ui.screens.settings

import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.preferences.core.Preferences
import moe.rukamori.archivetune.constants.AudioOffload
import moe.rukamori.archivetune.constants.CrossfadeEnabledKey
import moe.rukamori.archivetune.constants.DisableBlurKey
import moe.rukamori.archivetune.constants.LiquidGlassEnabledKey
import moe.rukamori.archivetune.constants.TabletModeEnabledKey
import moe.rukamori.archivetune.ui.component.LocalRestartPrompt
import moe.rukamori.archivetune.ui.component.navigationTreatmentKeys
import moe.rukamori.archivetune.ui.component.setNavigationTreatment
import moe.rukamori.archivetune.ui.component.setPlaybackOutputPreference

/** Search switches use the same persisted snapshot and post-commit restart owner as their destination page. */
@Composable
internal fun SearchResultSwitch(key: Preferences.Key<Boolean>, defaultValue: Boolean, blurOverridden: Boolean = false) {
    val prefs = LocalSettingsPreferenceSnapshot.current
    val restartPrompt = LocalRestartPrompt.current
    val context = LocalContext.current
    val (checked, setValue) = prefs.state(key, defaultValue)
    val onCheckedChange: (Boolean) -> Unit = { enabled ->
        when (key) {
            in navigationTreatmentKeys -> setNavigationTreatment(restartPrompt, prefs, key, enabled)
            LiquidGlassEnabledKey, TabletModeEnabledKey -> restartPrompt.edit(prefs) { this[key] = enabled }
            CrossfadeEnabledKey, AudioOffload ->
                setPlaybackOutputPreference(restartPrompt, prefs, context, key, enabled)
            else -> setValue(enabled)
        }
    }
    val blurDisabled = blurOverridden && prefs[DisableBlurKey, false]
    Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = !blurDisabled)
}
