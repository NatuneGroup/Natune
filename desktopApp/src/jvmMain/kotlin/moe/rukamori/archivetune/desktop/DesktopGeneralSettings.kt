package moe.rukamori.archivetune.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import moe.rukamori.archivetune.desktop.integration.DesktopFilePickers
import moe.rukamori.archivetune.desktop.integration.DesktopStrings
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.ui.settings.AppearanceThemeSettings
import moe.rukamori.archivetune.shared.ui.settings.DiscordExperimentalRoute
import moe.rukamori.archivetune.shared.ui.settings.DiscordExperimentalSettings
import moe.rukamori.archivetune.shared.ui.settings.PlaybackSettings
import moe.rukamori.archivetune.shared.ui.settings.PlayerAppearanceSettings
import moe.rukamori.archivetune.shared.ui.settings.SettingsController
import moe.rukamori.archivetune.shared.ui.settings.SettingsPage
import moe.rukamori.archivetune.shared.ui.settings.SettingsRow
import moe.rukamori.archivetune.shared.ui.settings.appearanceExtrasSettingsRows
import moe.rukamori.archivetune.shared.ui.settings.appearanceInterfaceSettingsRows
import moe.rukamori.archivetune.shared.ui.settings.lyricsAnimationSettingsRows
import moe.rukamori.archivetune.shared.ui.settings.lyricsSettingsRows

/** Settings pages that need nothing from this host beyond the settings owner, navigation and the file chooser. */
internal val DESKTOP_GENERAL_SETTINGS_ROUTES = setOf(
    "settings/appearance", "settings/appearance/theme", "settings/appearance/player", "settings/appearance/interface",
    "settings/appearance/extras", "settings/appearance/lyrics_animations", "settings/player", "settings/lyrics",
    DiscordExperimentalRoute,
)

/**
 * One of [DESKTOP_GENERAL_SETTINGS_ROUTES]. This host has no bit-perfect or USB output, no wallpaper colours and no
 * low-memory mode, so those rows read as unsupported. Arranging chips, tags, home screens and artwork providers needs
 * an editor this host does not have yet, and reports itself unavailable.
 */
@Composable
internal fun DesktopGeneralSettingsPage(
    path: String,
    controller: SettingsController,
    navigate: (String) -> Unit,
    onFailure: () -> Unit,
    unavailable: () -> Unit,
    scrollTo: String?,
) {
    when (path) {
        "settings/appearance" -> AppearanceHub(navigate)
        "settings/appearance/theme" -> AppearanceThemeSettings(
            controller = controller,
            systemDark = isSystemInDarkTheme(),
            lowRam = false,
            nativeDynamicColors = false,
            wallpaperPermissionNeeded = false,
            navigate = navigate,
            openWallpaperSettings = { },
            pickCustomFont = ::pickDesktopFont,
            onFailure = onFailure,
            scrollTo = scrollTo,
        )
        "settings/appearance/player" -> PlayerAppearanceSettings(
            controller = controller,
            blurSupported = DesktopOpticalCapabilities.blur,
            navigate = navigate,
            chooseHomeScreens = unavailable,
            onFailure = onFailure,
            scrollTo = scrollTo,
        )
        "settings/appearance/interface" -> RowsPage(controller, scrollTo, onFailure) {
            appearanceInterfaceSettingsRows(navigate, unavailable, unavailable)
        }
        "settings/appearance/extras" -> RowsPage(controller, scrollTo, onFailure) { appearanceExtrasSettingsRows() }
        "settings/appearance/lyrics_animations" -> RowsPage(controller, scrollTo, onFailure) { lyricsAnimationSettingsRows() }
        "settings/player" -> PlaybackSettings(
            controller = controller,
            bitPerfectSupported = false,
            bitPerfectSinkActive = false,
            usbExclusiveSupported = false,
            releaseBitPerfectSink = { },
            navigate = navigate,
            arrangeArtworkProviders = unavailable,
            managePlaylistTags = unavailable,
            onFailure = onFailure,
            scrollTo = scrollTo,
        )
        "settings/lyrics" -> RowsPage(controller, scrollTo, onFailure) { lyricsSettingsRows(navigate) }
        DiscordExperimentalRoute -> DiscordExperimentalSettings(controller, onFailure, scrollTo)
    }
}

@Composable
private fun RowsPage(
    controller: SettingsController,
    scrollTo: String?,
    onFailure: () -> Unit,
    rows: (PreferenceSnapshot) -> List<SettingsRow>,
) {
    val snapshot by controller.store.snapshots.collectAsState()
    val preferences = snapshot
    if (preferences == null) {
        CircularProgressIndicator()
        return
    }
    SettingsPage(rows(preferences), preferences, controller, scrollTo, onFailure = onFailure)
}

/** The baseline Appearance page: three entries, one per section. */
@Composable
private fun AppearanceHub(navigate: (String) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        HubEntry("appearance_theme", "settings/appearance/theme", navigate)
        HubEntry("appearance_player", "settings/appearance/player", navigate)
        HubEntry("appearance_interface", "settings/appearance/interface", navigate)
    }
}

@Composable
private fun HubEntry(key: String, route: String, navigate: (String) -> Unit) {
    ListItem(
        headlineContent = { Text(DesktopStrings[key]) },
        supportingContent = { Text(DesktopStrings["${key}_desc"]) },
        modifier = Modifier.clickable { navigate(route) },
    )
}

/** A custom font is one local font file, kept by its file URI and shown by its file name. */
private suspend fun pickDesktopFont(): Pair<String, String>? =
    DesktopFilePickers().use { pickers ->
        pickers.files(multiple = false).firstOrNull()?.let { path -> path.toUri().toString() to path.fileName.toString() }
    }
