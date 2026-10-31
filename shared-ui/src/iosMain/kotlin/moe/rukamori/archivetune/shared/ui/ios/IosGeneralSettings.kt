package moe.rukamori.archivetune.shared.ui.ios

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.ui.settings.AppearanceRoute
import moe.rukamori.archivetune.shared.ui.settings.AppearanceSectionRoutes
import moe.rukamori.archivetune.shared.ui.settings.AppearanceSettings
import moe.rukamori.archivetune.shared.ui.settings.DiscordExperimentalRoute
import moe.rukamori.archivetune.shared.ui.settings.DiscordExperimentalSettings
import moe.rukamori.archivetune.shared.ui.settings.PlaybackSettings
import moe.rukamori.archivetune.shared.ui.settings.SettingsController
import moe.rukamori.archivetune.shared.ui.settings.SettingsPage
import moe.rukamori.archivetune.shared.ui.settings.SettingsRoutes
import moe.rukamori.archivetune.shared.ui.settings.SettingsRow
import moe.rukamori.archivetune.shared.ui.settings.YtDlpRuntimeSettings
import moe.rukamori.archivetune.shared.ui.settings.YtDlpRuntimeStatus
import moe.rukamori.archivetune.shared.ui.settings.appearanceExtrasSettingsRows
import moe.rukamori.archivetune.shared.ui.settings.appearanceScrollTarget
import moe.rukamori.archivetune.shared.ui.settings.lyricsAnimationSettingsRows
import moe.rukamori.archivetune.shared.ui.settings.lyricsSettingsRows

/** Settings pages that need nothing from this host beyond the settings owner and navigation. */
internal val IOS_GENERAL_SETTINGS_ROUTES = setOf(
    "settings/appearance", "settings/appearance/theme", "settings/appearance/player", "settings/appearance/interface",
    "settings/appearance/extras", "settings/appearance/lyrics_animations", "settings/player", "settings/lyrics",
    DiscordExperimentalRoute,
    SettingsRoutes.YTDLP,
)

/** Which arrange dialog is open, if any. */
private enum class IosArrangeDialogKind { HOME_SCREENS, LIBRARY_CHIPS, PLAYLIST_TAGS, ARTWORK_PROVIDERS }

/**
 * One of [IOS_GENERAL_SETTINGS_ROUTES]. This host has no bit-perfect or USB output, no wallpaper colours and no
 * custom-font pick, so those rows read as unsupported. The home-screen, chip, tag and artwork arrange rows open the
 * dialogs below, which edit the same preferences the Android dialogs do.
 */
@Composable
internal fun IosGeneralSettingsPage(
    path: String,
    graph: IosSessionGraph,
    controller: SettingsController,
    navigate: (String) -> Unit,
    onFailure: () -> Unit,
    scrollTo: String?,
) {
    var arrangeDialog by remember { mutableStateOf<IosArrangeDialogKind?>(null) }
    when (path) {
        AppearanceRoute, in AppearanceSectionRoutes.keys -> AppearanceSettings(
            controller = controller,
            systemDark = isSystemInDarkTheme(),
            lowRam = false,
            nativeDynamicColors = false,
            wallpaperPermissionNeeded = false,
            blurSupported = IosOpticalCapabilities.blur,
            navigate = navigate,
            openWallpaperSettings = { },
            // The file picker returns a security-scoped bookmark rather than a font name the theme row needs.
            pickCustomFont = { null },
            customFontPickerSupported = false,
            chooseHomeScreens = { arrangeDialog = IosArrangeDialogKind.HOME_SCREENS },
            arrangeLibraryChips = { arrangeDialog = IosArrangeDialogKind.LIBRARY_CHIPS },
            arrangePlaylistTags = { arrangeDialog = IosArrangeDialogKind.PLAYLIST_TAGS },
            onFailure = onFailure,
            scrollTo = appearanceScrollTarget(path, scrollTo),
            nativeIosInterface = true,
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
            arrangeArtworkProviders = { arrangeDialog = IosArrangeDialogKind.ARTWORK_PROVIDERS },
            // The tag manager is the library's own tags page, so the row opens the page that edits this graph's tags.
            managePlaylistTags = { navigate("library/tags") },
            onFailure = onFailure,
            scrollTo = scrollTo,
        )
        "settings/lyrics" -> RowsPage(controller, scrollTo, onFailure) { lyricsSettingsRows(navigate) }
        DiscordExperimentalRoute -> DiscordExperimentalSettings(controller, onFailure, scrollTo)
        // This host ships no Python or yt-dlp runtime, so both checks report absent rather than probing a missing binary.
        SettingsRoutes.YTDLP -> YtDlpRuntimeSettings(
            readRuntime = { YtDlpRuntimeStatus(pythonAvailable = false, scriptAvailable = false) },
        )
    }
    val dialog = arrangeDialog
    if (dialog != null) {
        val snapshot by controller.store.snapshots.collectAsState()
        val preferences = snapshot
        if (preferences != null) {
            val dismiss = { arrangeDialog = null }
            when (dialog) {
                IosArrangeDialogKind.HOME_SCREENS ->
                    IosHomeScreensDialog(preferences, controller, dismiss, onFailure)
                IosArrangeDialogKind.LIBRARY_CHIPS ->
                    IosArrangeLibraryChipsDialog(preferences, controller, dismiss, onFailure)
                IosArrangeDialogKind.PLAYLIST_TAGS ->
                    IosArrangePlaylistTagsDialog(graph, preferences, controller, dismiss, onFailure)
                IosArrangeDialogKind.ARTWORK_PROVIDERS ->
                    IosArrangeArtworkProvidersDialog(preferences, controller, dismiss, onFailure)
            }
        }
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
