package app.natune.shared.ui.ios

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.natune.shared.domain.settings.PreferenceSnapshot
import app.natune.shared.ui.settings.AppearanceRoute
import app.natune.shared.ui.settings.AppearanceSectionRoutes
import app.natune.shared.ui.settings.AppearanceSettings
import app.natune.shared.ui.settings.PlaybackSettings
import app.natune.shared.ui.settings.SettingsController
import app.natune.shared.ui.settings.SettingsPage
import app.natune.shared.ui.settings.SettingsRoutes
import app.natune.shared.ui.settings.SettingsRow
import app.natune.shared.ui.settings.YtDlpRuntimeSettings
import app.natune.shared.ui.settings.YtDlpRuntimeStatus
import app.natune.shared.ui.settings.appearanceExtrasSettingsRows
import app.natune.shared.ui.settings.appearanceScrollTarget
import app.natune.shared.ui.settings.lyricsAnimationSettingsRows
import app.natune.shared.ui.settings.lyricsSettingsRows

/** Settings pages that need nothing from this host beyond the settings owner and navigation. */
internal val IOS_GENERAL_SETTINGS_ROUTES = setOf(
    "settings/appearance", "settings/appearance/theme", "settings/appearance/player", "settings/appearance/interface",
    "settings/appearance/extras", "settings/appearance/lyrics_animations", "settings/player", "settings/lyrics",
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
    host: app.natune.shared.ui.IosHost,
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
            // The picked font is copied into the app container; the picker returns its file URI and family name.
            pickCustomFont = { IosCustomFont.pick(host) },
            customFontPickerSupported = true,
            chooseHomeScreens = { arrangeDialog = IosArrangeDialogKind.HOME_SCREENS },
            arrangeLibraryChips = { arrangeDialog = IosArrangeDialogKind.LIBRARY_CHIPS },
            arrangePlaylistTags = { arrangeDialog = IosArrangeDialogKind.PLAYLIST_TAGS },
            onFailure = onFailure,
            scrollTo = appearanceScrollTarget(path, scrollTo),
            nativeIosInterface = true,
        )
        "settings/appearance/extras" -> RowsPage(controller, scrollTo, onFailure) { appearanceExtrasSettingsRows() }
        "settings/appearance/lyrics_animations" -> RowsPage(controller, scrollTo, onFailure) { lyricsAnimationSettingsRows() }
        "settings/player" -> IosPlaybackSettingsPage(controller, graph, navigate,
            { arrangeDialog = IosArrangeDialogKind.ARTWORK_PROVIDERS }, onFailure, scrollTo)
        "settings/lyrics" -> RowsPage(controller, scrollTo, onFailure) { lyricsSettingsRows(navigate) }
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
