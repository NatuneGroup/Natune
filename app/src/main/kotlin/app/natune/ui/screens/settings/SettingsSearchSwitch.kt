package app.natune.ui.screens.settings

import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.datastore.preferences.core.Preferences
import app.natune.constants.AppFontPreference
import app.natune.ui.component.LocalRestartPrompt
import app.natune.utils.RestartScopedSettings
import app.natune.utils.from
import app.natune.utils.setFontPreference

/**
 * Search switches use the same persisted snapshot, atomic conflict rules and post-commit restart owner as their
 * destination page, so search is not a route around page dependencies.
 */
@Composable
internal fun SearchResultSwitch(
    key: Preferences.Key<Boolean>,
    defaultValue: Boolean,
    blurOverridden: Boolean = false,
) = ConflictAwareSearchSwitch(key, defaultValue, blurOverridden)

/**
 * "Use system font" drives the canonical font preference key, the one Appearance writes and
 * StartupSettings resolves; the legacy boolean is ignored once that key exists.
 */
@Composable
internal fun SearchResultSystemFontSwitch() {
    val prefs = LocalSettingsPreferenceSnapshot.current
    val restartPrompt = LocalRestartPrompt.current
    val fontPreference = RestartScopedSettings.from(prefs.current).fontPreference
    Switch(
        checked = fontPreference == AppFontPreference.SYSTEM,
        onCheckedChange = { checked ->
            restartPrompt.edit(prefs) {
                setFontPreference(if (checked) AppFontPreference.SYSTEM else AppFontPreference.DEFAULT)
            }
        },
    )
}
