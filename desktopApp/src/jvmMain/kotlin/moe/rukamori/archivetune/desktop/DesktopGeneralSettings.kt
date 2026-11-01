package moe.rukamori.archivetune.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import moe.rukamori.archivetune.desktop.integration.DesktopFilePickers
import moe.rukamori.archivetune.shared.domain.playlisttags.PlaylistTagsRepository
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.ui.settings.AppearanceRoute
import moe.rukamori.archivetune.shared.ui.settings.AppearanceSectionRoutes
import moe.rukamori.archivetune.shared.ui.settings.AppearanceSettings
import moe.rukamori.archivetune.shared.ui.settings.DiscordExperimentalRoute
import moe.rukamori.archivetune.shared.ui.settings.DiscordExperimentalSettings
import moe.rukamori.archivetune.shared.ui.settings.LiquidGlassSettings
import moe.rukamori.archivetune.shared.ui.settings.LiquidGlassSettingsRoute
import moe.rukamori.archivetune.shared.ui.settings.NavigationBarSettingsPage
import moe.rukamori.archivetune.shared.ui.settings.NavigationBarSettingsRoute
import moe.rukamori.archivetune.shared.ui.settings.PlaybackSettings
import moe.rukamori.archivetune.shared.ui.settings.SettingsController
import moe.rukamori.archivetune.shared.ui.settings.SettingsPage
import moe.rukamori.archivetune.shared.ui.settings.SettingsRow
import moe.rukamori.archivetune.shared.ui.settings.appearanceExtrasSettingsRows
import moe.rukamori.archivetune.shared.ui.settings.appearanceScrollTarget
import moe.rukamori.archivetune.shared.ui.settings.lyricsAnimationSettingsRows
import moe.rukamori.archivetune.shared.ui.settings.lyricsSettingsRows

/** Settings pages that need nothing from this host beyond the settings owner, navigation and the file chooser. */
internal val DESKTOP_GENERAL_SETTINGS_ROUTES = setOf(
    "settings/appearance", "settings/appearance/theme", "settings/appearance/player", "settings/appearance/interface",
    "settings/appearance/extras", "settings/appearance/lyrics_animations", "settings/player", "settings/lyrics",
    NavigationBarSettingsRoute, LiquidGlassSettingsRoute, DiscordExperimentalRoute,
)

/**
 * One of [DESKTOP_GENERAL_SETTINGS_ROUTES]. This host has no bit-perfect or USB output, no wallpaper colours and no
 * low-memory mode, so those rows read as unsupported. Home screens, chip, tag and artwork-provider order open the
 * editors in [DesktopGeneralEditorHost]; Manage playlist tags opens the Library tag manager.
 */
@Composable
internal fun DesktopGeneralSettingsPage(
    path: String,
    controller: SettingsController,
    navigate: (String) -> Unit,
    onFailure: () -> Unit,
    scrollTo: String?,
    playlistTags: PlaylistTagsRepository,
) {
    var editor by remember { mutableStateOf<DesktopGeneralEditor?>(null) }
    editor?.let { open ->
        DesktopGeneralEditorHost(open, controller, playlistTags, onDismiss = { editor = null }, onFailure = onFailure)
    }
    when (path) {
        AppearanceRoute, in AppearanceSectionRoutes.keys -> AppearanceSettings(
            controller = controller,
            systemDark = LocalDesktopSystemDark.current ?: isSystemInDarkTheme(),
            lowRam = false,
            nativeDynamicColors = false,
            wallpaperPermissionNeeded = false,
            blurSupported = DesktopOpticalCapabilities.blur,
            navigate = navigate,
            openWallpaperSettings = { },
            pickCustomFont = ::pickDesktopFont,
            chooseHomeScreens = { editor = DesktopGeneralEditor.HOME_SCREENS },
            arrangeLibraryChips = { editor = DesktopGeneralEditor.LIBRARY_CHIPS },
            arrangePlaylistTags = { editor = DesktopGeneralEditor.PLAYLIST_TAGS },
            onFailure = onFailure,
            scrollTo = appearanceScrollTarget(path, scrollTo),
        )
        NavigationBarSettingsRoute -> NavigationBarSettingsPage(
            controller, DesktopOpticalCapabilities.blur, DesktopOpticalCapabilities.refraction, onFailure, scrollTo,
        )
        LiquidGlassSettingsRoute -> LiquidGlassSettings(
            controller, DesktopOpticalCapabilities.blur, DesktopOpticalCapabilities.refraction, onFailure, scrollTo,
        )
        NavigationBarSettingsRoute -> NavigationBarSettingsPage(
            controller, DesktopOpticalCapabilities.blur, DesktopOpticalCapabilities.refraction, onFailure, scrollTo,
        )
        LiquidGlassSettingsRoute -> LiquidGlassSettings(
            controller, DesktopOpticalCapabilities.blur, DesktopOpticalCapabilities.refraction, onFailure, scrollTo,
        )
        "settings/appearance/extras" -> RowsPage(controller, scrollTo, onFailure) { appearanceExtrasSettingsRows() }
        "settings/appearance/lyrics_animations" -> RowsPage(controller, scrollTo, onFailure) { lyricsAnimationSettingsRows() }
        "settings/player" -> PlaybackSettings(
            controller = controller,
            bitPerfectSupported = false,
            bitPerfectSinkActive = false,
            usbExclusiveSupported = false,
            releaseBitPerfectSink = { },
            navigate = navigate,
            arrangeArtworkProviders = { editor = DesktopGeneralEditor.ARTWORK_PROVIDERS },
            managePlaylistTags = { navigate(DesktopTagsRoute) },
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

/** A custom font is one local font file, kept by its file URI and shown by its file name. */
private suspend fun pickDesktopFont(): Pair<String, String>? =
    DesktopFilePickers().use { pickers ->
        pickers.files(multiple = false).firstOrNull()?.let { path -> path.toUri().toString() to path.fileName.toString() }
    }
