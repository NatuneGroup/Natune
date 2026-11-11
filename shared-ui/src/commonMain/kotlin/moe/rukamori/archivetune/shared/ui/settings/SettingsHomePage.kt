package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import org.jetbrains.compose.resources.stringResource
import moe.rukamori.archivetune.shared.ui.resources.m4_empty
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
    /** False on a host with no key path to the shortcut overlay, which then hides that group instead of a dead row. */
    keyboardShortcuts: Boolean = true,
    modifier: Modifier = Modifier,
    hostIntegration: SettingsRow.Action? = null,
) {
    val snapshot by controller.store.snapshots.collectAsState()
    var shortcutsVisible by remember { mutableStateOf(false) }
    val reference = LocalPhoneSettingsReference.current
    var searchQuery by remember { mutableStateOf("") }
    val preferences = snapshot
    if (preferences == null) { CircularProgressIndicator(); return }
    val rows = settingsHomeRows(
        navigate = navigate,
        androidAuto = androidAuto,
        hideToTrayKey = hideToTrayKey,
        openShortcuts = { shortcutsVisible = true },
        desktop = moe.rukamori.archivetune.shared.ui.navigation.LocalDesktopLayout.current,
        keyboardShortcuts = keyboardShortcuts,
        hostIntegration = hostIntegration,
    )
    val labels = rows.associate { it.anchor to stringResource(it.title) }
    val visibleRows = if (reference != null && searchQuery.isNotBlank()) rows.filter { row ->
        row !is SettingsRow.Header && labels[row.anchor].orEmpty().contains(searchQuery, ignoreCase = true)
    } else if (reference != null && scrollTo.isNullOrBlank()) rows.filterNot { it.anchor in phoneSettingsHomeBrowseHidden } else rows
    val visibleReference = if (reference != null && searchQuery.isNotBlank()) reference.copy(rows = reference.rows.filter { spec ->
        visibleRows.any { it.anchor == spec.anchor }
    }) else if (reference != null && scrollTo.isNullOrBlank()) reference.copy(rows = reference.rows.filterNot {
        it.anchor in phoneSettingsHomeBrowseHidden
    }) else reference
    CompositionLocalProvider(LocalPhoneSettingsReference provides visibleReference, LocalPhoneSettingsRoot provides (reference != null)) {
        SettingsPage(
            rows = visibleRows,
            preferences = preferences,
            controller = controller,
            scrollTo = scrollTo,
            resumed = resumed,
            motionEnabled = motionEnabled,
            onFailure = onFailure,
            modifier = modifier,
            extraItems = { if (visibleRows.isEmpty()) item(key = "settings-empty") {
                androidx.compose.material3.Text(stringResource(moe.rukamori.archivetune.shared.ui.resources.Res.string.m4_empty))
            } },
            header = if (reference != null) { {
                PhoneSettingsSearchHeader(LocalSettingsBack.current ?: {})
            } } else null,
            leadingItems = { if (reference != null) item(key = "settings-search") { PhoneSettingsSearchField(searchQuery) { searchQuery = it } } },
            leadingItemCount = if (reference != null) 1 else 0,
        )
    }
    if (shortcutsVisible) KeyboardShortcutsDialog(onDismiss = { shortcutsVisible = false })
}
