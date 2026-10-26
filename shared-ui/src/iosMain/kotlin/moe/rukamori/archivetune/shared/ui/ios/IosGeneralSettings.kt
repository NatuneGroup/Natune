package moe.rukamori.archivetune.shared.ui.ios

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
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.ui.resources.*
import moe.rukamori.archivetune.shared.ui.resources.m6_appearance_interface_desc
import moe.rukamori.archivetune.shared.ui.resources.m6_appearance_player_desc
import moe.rukamori.archivetune.shared.ui.resources.m6_appearance_theme_desc
import moe.rukamori.archivetune.shared.ui.resources.m6_appearance_interface
import moe.rukamori.archivetune.shared.ui.resources.m6_appearance_player
import moe.rukamori.archivetune.shared.ui.resources.m6_appearance_theme
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
import org.jetbrains.compose.resources.stringResource

/** Settings pages that need nothing from this host beyond the settings owner and navigation. */
internal val IOS_GENERAL_SETTINGS_ROUTES = setOf(
    "settings/appearance", "settings/appearance/theme", "settings/appearance/player", "settings/appearance/interface",
    "settings/appearance/extras", "settings/appearance/lyrics_animations", "settings/player", "settings/lyrics",
    DiscordExperimentalRoute,
)

/**
 * One of [IOS_GENERAL_SETTINGS_ROUTES]. This host has no bit-perfect or USB output, no wallpaper colours, no
 * home-screen editor and no custom-font pick, so those rows read as unsupported.
 */
@Composable
internal fun IosGeneralSettingsPage(
    path: String,
    controller: SettingsController,
    navigate: (String) -> Unit,
    onFailure: () -> Unit,
    unavailable: () -> Unit,
    scrollTo: String?,
) {
    when (path) {
        "settings/appearance" -> IosAppearanceHub(navigate)
        "settings/appearance/theme" -> AppearanceThemeSettings(
            controller = controller,
            systemDark = isSystemInDarkTheme(),
            lowRam = false,
            nativeDynamicColors = false,
            wallpaperPermissionNeeded = false,
            navigate = navigate,
            openWallpaperSettings = { },
            // The file picker returns a security-scoped bookmark rather than a font name the theme row needs.
            pickCustomFont = { null },
            onFailure = onFailure,
            scrollTo = scrollTo,
        )
        "settings/appearance/player" -> PlayerAppearanceSettings(
            controller = controller,
            blurSupported = IosOpticalCapabilities.blur,
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
private fun IosAppearanceHub(navigate: (String) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        HubEntry(Res.string.m6_appearance_theme, Res.string.m6_appearance_theme_desc, "settings/appearance/theme", navigate)
        HubEntry(Res.string.m6_appearance_player, Res.string.m6_appearance_player_desc, "settings/appearance/player", navigate)
        HubEntry(Res.string.m6_appearance_interface, Res.string.m6_appearance_interface_desc, "settings/appearance/interface", navigate)
    }
}

@Composable
private fun HubEntry(title: org.jetbrains.compose.resources.StringResource, description: org.jetbrains.compose.resources.StringResource, route: String, navigate: (String) -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = { Text(stringResource(description)) },
        modifier = Modifier.clickable { navigate(route) },
    )
}
