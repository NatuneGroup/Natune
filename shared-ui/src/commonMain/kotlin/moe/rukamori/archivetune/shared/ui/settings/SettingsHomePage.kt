package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import moe.rukamori.archivetune.shared.domain.settings.BooleanPreferenceKey
import moe.rukamori.archivetune.shared.ui.shortcuts.KeyboardShortcutsDialog

/**
 * The top settings page: the groups and entries of the Android settings screen, in its order. Each entry opens its own
 * route; a host that has not mounted a page there shows its unbound boundary, so no entry is dropped for a missing page.
 * Tidal, Qobuz and Telegram stay under Integration, as on Android.
 *
 * [androidAuto] shows the Android Auto entry, which only the Android host has. [hideToTrayKey] adds the Window group with
 * the close-to-tray switch for a host that owns a tray icon.
 */
@Composable
fun SettingsHomePage(
    controller: SettingsController,
    navigate: (String) -> Unit,
    onFailure: () -> Unit,
    androidAuto: Boolean = false,
    hideToTrayKey: BooleanPreferenceKey? = null,
    scrollTo: String? = null,
    resumed: Boolean = true,
    motionEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val snapshot by controller.store.snapshots.collectAsState()
    var shortcutsVisible by remember { mutableStateOf(false) }
    val preferences = snapshot
    if (preferences == null) { CircularProgressIndicator(); return }
    val rows = settingsHomeRows(
        navigate = navigate,
        androidAuto = androidAuto,
        hideToTrayKey = hideToTrayKey,
        openShortcuts = { shortcutsVisible = true },
    )
    SettingsPage(
        rows = rows,
        preferences = preferences,
        controller = controller,
        scrollTo = scrollTo,
        resumed = resumed,
        motionEnabled = motionEnabled,
        onFailure = onFailure,
        modifier = modifier,
    )
    if (shortcutsVisible) KeyboardShortcutsDialog(onDismiss = { shortcutsVisible = false })
}
