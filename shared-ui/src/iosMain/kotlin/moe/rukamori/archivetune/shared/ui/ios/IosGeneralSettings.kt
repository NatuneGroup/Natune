package moe.rukamori.archivetune.shared.ui.ios

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.domain.lyrics.romanization.JapaneseLanguagePackState
import moe.rukamori.archivetune.shared.domain.lyrics.romanization.JapanesePackFailure
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.ui.resources.Res
import moe.rukamori.archivetune.shared.ui.resources.m6_appearance_hub_interface
import moe.rukamori.archivetune.shared.ui.resources.m6_appearance_hub_interface_desc
import moe.rukamori.archivetune.shared.ui.resources.m6_appearance_hub_player
import moe.rukamori.archivetune.shared.ui.resources.m6_appearance_hub_player_desc
import moe.rukamori.archivetune.shared.ui.resources.m6_appearance_hub_theme
import moe.rukamori.archivetune.shared.ui.resources.m6_appearance_hub_theme_desc
import moe.rukamori.archivetune.shared.ui.resources.m6_language_pack_japanese_failed
import moe.rukamori.archivetune.shared.ui.resources.ios_settings_feature_unavailable
import moe.rukamori.archivetune.shared.ui.settings.AiIntegrationSettings
import moe.rukamori.archivetune.shared.ui.settings.AddonDownloadUiState
import moe.rukamori.archivetune.shared.ui.settings.AppearanceThemeSettings
import moe.rukamori.archivetune.shared.ui.settings.DeveloperSettings
import moe.rukamori.archivetune.shared.ui.settings.DiscordExperimentalRoute
import moe.rukamori.archivetune.shared.ui.settings.DiscordExperimentalSettings
import moe.rukamori.archivetune.shared.ui.settings.IntegrationSettings
import moe.rukamori.archivetune.shared.ui.settings.JapaneseLanguagePackUiState
import moe.rukamori.archivetune.shared.ui.settings.LanguagePackSettings
import moe.rukamori.archivetune.shared.ui.settings.LyricsProviderSettings
import moe.rukamori.archivetune.shared.ui.settings.PlaybackSettings
import moe.rukamori.archivetune.shared.ui.settings.PlayerAppearanceSettings
import moe.rukamori.archivetune.shared.ui.settings.SettingsController
import moe.rukamori.archivetune.shared.ui.settings.SettingsPage
import moe.rukamori.archivetune.shared.ui.settings.SettingsRow
import moe.rukamori.archivetune.shared.ui.settings.SettingsRoutes
import moe.rukamori.archivetune.shared.ui.settings.SpotifyCatalogAccountState
import moe.rukamori.archivetune.shared.ui.settings.YtDlpRuntimeSettings
import moe.rukamori.archivetune.shared.ui.settings.YtDlpRuntimeStatus
import moe.rukamori.archivetune.shared.ui.settings.appearanceExtrasSettingsRows
import moe.rukamori.archivetune.shared.ui.settings.appearanceInterfaceSettingsRows
import moe.rukamori.archivetune.shared.ui.settings.lyricsAnimationSettingsRows
import moe.rukamori.archivetune.shared.ui.settings.lyricsSettingsRows
import moe.rukamori.archivetune.shared.ui.settings.romanizationSettingsRows
import moe.rukamori.archivetune.shared.ui.IosHost
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * Settings pages that need nothing from this host beyond the settings owner, navigation and the unavailable
 * reporter. Same set the desktop host mounts through its general page.
 */
internal val IOS_GENERAL_SETTINGS_ROUTES = setOf(
    "settings/appearance", "settings/appearance/theme", "settings/appearance/player", "settings/appearance/interface",
    "settings/appearance/extras", "settings/appearance/lyrics_animations", "settings/player", SettingsRoutes.YTDLP,
    "settings/lyrics", "settings/lyrics/romanisation", DiscordExperimentalRoute,
)

/**
 * One of [IOS_GENERAL_SETTINGS_ROUTES]. This host has no bit-perfect or USB output, no wallpaper colours and no
 * low-memory mode, so those rows read as unsupported. Arranging chips, tags, home screens, artwork providers and a
 * custom font picker need owners this host does not have yet, and they report themselves unavailable.
 */
@Composable
internal fun IosGeneralSettingsPage(
    path: String,
    host: IosHost,
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
            // No document picker is connected yet; the tap says so instead of silently doing nothing.
            pickCustomFont = { unavailable(); null },
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
        "settings/appearance/interface" -> IosRowsPage(controller, scrollTo, onFailure) {
            appearanceInterfaceSettingsRows(navigate, unavailable, unavailable)
        }
        "settings/appearance/extras" -> IosRowsPage(controller, scrollTo, onFailure) { appearanceExtrasSettingsRows() }
        "settings/appearance/lyrics_animations" -> IosRowsPage(controller, scrollTo, onFailure) { lyricsAnimationSettingsRows() }
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
        // The external yt-dlp runtime ships only on hosts with a plugin mechanism; the row reports its real state.
        SettingsRoutes.YTDLP -> YtDlpRuntimeSettings(readRuntime = { YtDlpRuntimeStatus(pythonAvailable = false, scriptAvailable = false) })
        "settings/lyrics" -> IosRowsPage(controller, scrollTo, onFailure) { lyricsSettingsRows(navigate) }
        "settings/lyrics/romanisation" -> {
            val pack by host.japaneseLanguagePack.collectAsState()
            val packState = pack?.state?.collectAsState()?.value
            IosRowsPage(controller, scrollTo, onFailure) { preferences ->
                romanizationSettingsRows(
                    // The AI romanisation pipeline is not connected to the iOS lyrics surface.
                    aiHandled = false,
                    japanesePackReady = packState is JapaneseLanguagePackState.Installed,
                    preferences = preferences,
                )
            }
        }
        DiscordExperimentalRoute -> DiscordExperimentalSettings(controller, onFailure, scrollTo)
    }
}

@Composable
private fun IosRowsPage(
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
        HubEntry(Res.string.m6_appearance_hub_theme, Res.string.m6_appearance_hub_theme_desc,
            "settings/appearance/theme", navigate)
        HubEntry(Res.string.m6_appearance_hub_player, Res.string.m6_appearance_hub_player_desc,
            "settings/appearance/player", navigate)
        HubEntry(Res.string.m6_appearance_hub_interface, Res.string.m6_appearance_hub_interface_desc,
            "settings/appearance/interface", navigate)
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

/**
 * Settings > Misc on this host. The acoustic audit, Discord debug and nerd-stats sections need owners that are not
 * connected here, so each reports itself unavailable rather than showing an empty block.
 */
@Composable
internal fun IosDeveloperSettingsPage(
    controller: SettingsController,
    navigate: (String) -> Unit,
    onFailure: () -> Unit,
    scrollTo: String?,
) {
    DeveloperSettings(
        controller = controller,
        navigate = navigate,
        playerAvailable = true,
        acousticQualitySection = { IosUnavailablePanel() },
        discordDebugSection = { IosUnavailablePanel() },
        nerdStatsSection = { IosUnavailablePanel() },
        resumed = true,
        onFailure = onFailure,
        scrollTo = scrollTo,
    )
}

@Composable
private fun IosUnavailablePanel() {
    Text(
        stringResource(Res.string.ios_settings_feature_unavailable),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/**
 * Settings > Integration on this host. The pool is not configured and the Spotify catalog sign-in is not connected,
 * so those controls report unavailable instead of pretending an account exists.
 */
@Composable
internal fun IosIntegrationSettingsPage(
    controller: SettingsController,
    navigate: (String) -> Unit,
    unavailable: () -> Unit,
    onFailure: () -> Unit,
    scrollTo: String?,
) {
    IntegrationSettings(
        controller = controller,
        poolConfigured = false,
        spotifyAccount = SpotifyCatalogAccountState(),
        spotifyError = null,
        navigate = navigate,
        onConnectSpotify = unavailable,
        onReloadSpotifyPlaylists = { },
        onLogoutSpotify = { },
        onDismissSpotifyError = { },
        onPoolAccountsChanged = { },
        onOpenCrossServiceImport = unavailable,
        onFailure = onFailure,
        scrollTo = scrollTo,
    )
}

/**
 * Settings > Language packs. The Japanese dictionary owner reports its real download/install/remove state; the
 * SpatialFlow font has no add-on fetcher on this host, so its row reads "not available in this build".
 */
@Composable
internal fun IosLanguagePackSettingsPage(
    host: IosHost,
    graph: IosSessionGraph,
    controller: SettingsController,
    onFailure: () -> Unit,
) {
    val pack by host.japaneseLanguagePack.collectAsState()
    val packState = pack?.state?.collectAsState()?.value
    val failedMessage = stringResource(Res.string.m6_language_pack_japanese_failed)
    val notConnected = stringResource(Res.string.ios_settings_feature_unavailable)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LanguagePackSettings(
        controller = controller,
        japanese = packState?.toUiState(failedMessage) ?: JapaneseLanguagePackUiState.Failed(notConnected, removalFailed = false),
        spatialFlowFont = AddonDownloadUiState.Unavailable,
        formatSize = ::iosFormatBytes,
        onInstallJapanese = { scope.launch { pack?.install() } },
        onCancelJapanese = { scope.launch { pack?.cancel() } },
        onRemoveJapanese = { scope.launch { pack?.remove() } },
        onInstallSpatialFlowFont = { },
        onRemoveSpatialFlowFont = { },
        onFailure = onFailure,
    )
}

private fun JapaneseLanguagePackState.toUiState(failedMessage: String): JapaneseLanguagePackUiState = when (this) {
    JapaneseLanguagePackState.Checking -> JapaneseLanguagePackUiState.Checking
    JapaneseLanguagePackState.NotInstalled -> JapaneseLanguagePackUiState.NotInstalled
    is JapaneseLanguagePackState.Downloading -> JapaneseLanguagePackUiState.Downloading(progressPercent)
    JapaneseLanguagePackState.Removing -> JapaneseLanguagePackUiState.Removing
    is JapaneseLanguagePackState.Installed -> JapaneseLanguagePackUiState.Installed(sizeBytes)
    is JapaneseLanguagePackState.Failed -> JapaneseLanguagePackUiState.Failed(
        when (reason) {
            JapanesePackFailure.INVALID -> "invalid"
            JapanesePackFailure.INSTALL -> "install"
            JapanesePackFailure.REMOVE -> "remove"
        }.let { "$failedMessage ($it)" },
        removalFailed = removalFailed,
    )
}

/** A compact byte count for the pack rows: "13.3 MB", "512 KB", "900 B". */
private fun iosFormatBytes(bytes: Long): String = when {
    bytes >= 1_000_000L -> "${(bytes * 10 / 1_000_000).toInt() / 10.0} MB"
    bytes >= 1_000L -> "${bytes / 1_000} KB"
    else -> "$bytes B"
}

/**
 * Settings > AI integration. The shared service talks to the user's configured endpoint; without a configured
 * provider the page reads "not configured" and the test actions report the missing setup.
 */
@Composable
internal fun IosAiIntegrationSettingsPage(
    owners: IosSettingsOwners,
    onFailure: () -> Unit,
    openExternal: (String) -> Unit,
    scrollTo: String?,
) {
    AiIntegrationSettings(
        controller = owners.controller,
        actions = owners.aiActions,
        onFailure = onFailure,
        scrollTo = scrollTo,
        onOpenApiKeyPortal = openExternal,
    )
}
