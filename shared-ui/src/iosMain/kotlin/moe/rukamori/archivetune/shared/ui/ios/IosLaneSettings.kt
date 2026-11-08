package moe.rukamori.archivetune.shared.ui.ios

import moe.rukamori.archivetune.shared.ui.resources.ios_aod_unavailable
import moe.rukamori.archivetune.shared.domain.settings.ModulesEnabledKey

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.shared.domain.settings.ContentCountryKey
import moe.rukamori.archivetune.shared.domain.settings.ContentLanguageKey
import moe.rukamori.archivetune.shared.domain.settings.HideExplicitKey
import moe.rukamori.archivetune.shared.domain.settings.JioSaavnEnabledKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.SaavnAudioQualityKey
import moe.rukamori.archivetune.shared.domain.settings.amazonInstanceList
import moe.rukamori.archivetune.shared.domain.settings.spotifyStreamEngineEffective
import moe.rukamori.archivetune.shared.domain.settings.spotifyStreamSessionPresent
import moe.rukamori.archivetune.shared.domain.settings.tidalInstanceList
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType
import moe.rukamori.archivetune.shared.ui.IosHost
import moe.rukamori.archivetune.shared.ui.navigation.ShellNavigation
import moe.rukamori.archivetune.shared.ui.resources.Res
import moe.rukamori.archivetune.shared.ui.resources.ios_settings_feature_unavailable
import moe.rukamori.archivetune.shared.ui.resources.m6_audio_quality
import moe.rukamori.archivetune.shared.ui.resources.m6_content_country
import moe.rukamori.archivetune.shared.ui.resources.m6_content_language
import moe.rukamori.archivetune.shared.ui.resources.m6_hide_explicit
import moe.rukamori.archivetune.shared.ui.resources.m6_integration_ok
import moe.rukamori.archivetune.shared.ui.resources.m6_quality_160
import moe.rukamori.archivetune.shared.ui.resources.m6_quality_320
import moe.rukamori.archivetune.shared.ui.resources.m6_quality_96
import moe.rukamori.archivetune.shared.ui.resources.m6_source_jio
import moe.rukamori.archivetune.shared.ui.resources.m6_system_default
import moe.rukamori.archivetune.shared.ui.settings.AppleMusicLoginRoute
import moe.rukamori.archivetune.shared.ui.settings.AppleMusicSettings
import moe.rukamori.archivetune.shared.ui.settings.NavigationBarSettingsPage
import moe.rukamori.archivetune.shared.ui.settings.SettingsController
import moe.rukamori.archivetune.shared.ui.settings.SettingsNotice
import moe.rukamori.archivetune.shared.ui.settings.SettingsPage
import moe.rukamori.archivetune.shared.ui.settings.SettingsRow
import moe.rukamori.archivetune.shared.ui.settings.SourcePoolRefreshOutcome
import moe.rukamori.archivetune.shared.ui.settings.SourceSettings
import moe.rukamori.archivetune.shared.ui.settings.TelegramLoginRoute
import moe.rukamori.archivetune.shared.ui.settings.YouTubeHiddenPlaylistsRoute
import org.jetbrains.compose.resources.stringResource
import platform.Foundation.ISOCountryCodes
import platform.Foundation.ISOLanguageCodes
import platform.Foundation.NSLocale
import platform.Foundation.currentLocale
import platform.Foundation.localizedStringForCountryCode
import platform.Foundation.localizedStringForLanguageCode

/**
 * The settings routes this lane mounts on top of [IOS_GENERAL_SETTINGS_ROUTES]. Pages whose owner does not exist on
 * this host (the palette picker, the icon picker, Android Auto, the Android-only AOD and Liquid Glass tuning pages,
 * and the Discord button editor) mount an honest unavailable page instead of the unbound boundary.
 */
internal val IOS_LANE_SETTINGS_ROUTES = setOf(
    "settings/hidden_playlists", "settings/po_token",
    "settings/backup_restore", "settings/storage/export_songs", "settings/appearance/palette_picker",
    "settings/appearance/icon", "settings/appearance/theme_creator", "settings/appearance/navigation_bar",
    "settings/appearance/aod_customized", "settings/appearance/liquid_glass", "settings/discord/experimental",
    "settings/android_auto", "settings/system_integrations", "settings/modules", "settings/sources", "settings/jiosaavn", "settings/amazon", "settings/applemusic",
    "settings/content", "settings/canvas", "settings/changelog", "settings/internet", "settings/storage",
    "settings/downloads", "settings/discord", "settings/qqmusic", "settings/lastfm", "settings/spotify_streaming",
    TelegramLoginRoute, IosLastFmDashboardRoute, "customize_background", "settings/customize_background",
)

/** Routes that mount the unavailable page: their owners do not exist on this host. */
private val IOS_LANE_UNAVAILABLE_ROUTES = setOf(
    // Pages whose rows are Android-only and which this host does not mount.
    "settings/appearance/aod_customized",
)

/** One mounted lane page; the host, notices and the unavailable snackbar come from [IosSettingsScreen]. */
@Composable
internal fun IosLaneSettingsPage(
    path: String,
    host: IosHost,
    owners: IosSettingsOwners,
    channel: String?,
    navigation: ShellNavigation,
    navigate: (String) -> Unit,
    onNotice: (SettingsNotice) -> Unit,
    onFailure: () -> Unit,
    unavailable: () -> Unit,
    scrollTo: String?,
) {
    when (path) {
        in IOS_LANE_UNAVAILABLE_ROUTES -> IosLaneUnavailablePage(stringResource(Res.string.ios_aod_unavailable))
        "settings/android_auto", "settings/system_integrations" -> IosSystemIntegrationsPage()
        "settings/modules" -> IosModulesPage(host, owners.graph)
        "settings/appearance/icon" -> IosIconEditor(onFailure)
        "settings/appearance/palette_picker", "settings/appearance/theme_creator" -> IosPaletteEditor(owners.controller, onFailure)
        "settings/appearance/liquid_glass" -> moe.rukamori.archivetune.shared.ui.settings.LiquidGlassSettings(
            owners.controller, IosOpticalCapabilities.blur, IosOpticalCapabilities.refraction, onFailure, scrollTo)
        "settings/discord/experimental" -> moe.rukamori.archivetune.shared.ui.settings.DiscordExperimentalSettings(
            owners.controller, onFailure, scrollTo)
        "settings/hidden_playlists" -> IosHiddenPlaylistsPage(owners.graph, onNotice, onFailure)
        "settings/backup_restore" ->
            IosBackupRestorePage(host, owners.graph, owners.controller, onNotice, onFailure, scrollTo)
        "settings/storage/export_songs" -> IosExportSongsPage(host, owners.graph, onNotice, onFailure)
        "settings/appearance/navigation_bar" ->
            NavigationBarSettingsPage(
                owners.controller, IosOpticalCapabilities.blur, IosOpticalCapabilities.refraction, onFailure, scrollTo,
            )
        "settings/jiosaavn" -> IosLaneRowsPage(owners.controller, scrollTo, onFailure) { preferences ->
            listOf(
                SettingsRow.Toggle("jiosaavn_enable", Res.string.m6_source_jio, JioSaavnEnabledKey, false),
                SettingsRow.Choice(
                    "jiosaavn_audio_quality", Res.string.m6_audio_quality, SaavnAudioQualityKey, "QUALITY_320",
                    listOf(
                        SettingsRow.Option("QUALITY_320", Res.string.m6_quality_320),
                        SettingsRow.Option("QUALITY_160", Res.string.m6_quality_160),
                        SettingsRow.Option("QUALITY_96", Res.string.m6_quality_96),
                    ),
                    enabled = preferences[JioSaavnEnabledKey, false],
                ),
            )
        }
        "settings/content" -> IosLaneContentSettingsPage(owners.controller, onFailure, scrollTo)
        "settings/applemusic" -> AppleMusicSettings(
            owners.controller, onFailure, scrollTo,
            onOpenLogin = { navigate(AppleMusicLoginRoute) },
        )
        "settings/sources" -> IosLaneSourceSettingsPage(owners, navigate, onFailure, scrollTo)
        TelegramLoginRoute -> {
            val session by host.telegram.collectAsState()
            val telegram = session
            if (telegram == null) IosUnboundSurface(Modifier.fillMaxSize())
            else IosTelegramLoginPage(telegram, navigation)
        }
        "customize_background", "settings/customize_background" ->
            IosCustomBackgroundPage(host, owners.graph, owners.controller, onFailure)
        IosLastFmDashboardRoute -> IosLastFmDashboardPage(owners.graph, navigation, unavailable)
        YouTubeHiddenPlaylistsRoute -> IosHiddenPlaylistsPage(owners.graph, onNotice, onFailure)
        else -> IosLaneServiceSettingsPage(path, host, owners, channel, navigate, onNotice, onFailure, unavailable, scrollTo)
    }
}

/** The unavailable page: one honest line, no dead controls. */
@Composable
internal fun IosLaneUnavailablePage(reason: String? = null) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            reason ?: stringResource(Res.string.ios_settings_feature_unavailable),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(24.dp),
        )
    }
}

/** A plain rows page over the settings owner, for routes that need nothing else from this host. */
@Composable
private fun IosLaneRowsPage(
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

/** Settings > Content: the ISO language and country catalogs of the platform locale, as the desktop page offers. */
@Composable
private fun IosLaneContentSettingsPage(
    controller: SettingsController,
    onFailure: () -> Unit,
    scrollTo: String?,
) {
    val snapshot by controller.store.snapshots.collectAsState()
    val preferences = snapshot
    if (preferences == null) {
        CircularProgressIndicator()
        return
    }
    val systemDefault = stringResource(Res.string.m6_system_default)
    val languageOptions = remember(systemDefault) {
        listOf("system" to systemDefault) + iosLaneIsoOptions(languages = true)
    }
    val countryOptions = remember(systemDefault) {
        listOf("system" to systemDefault) + iosLaneIsoOptions(languages = false)
    }
    val languageLabel = stringResource(Res.string.m6_content_language)
    val countryLabel = stringResource(Res.string.m6_content_country)
    SettingsPage(
        rows = listOf(
            SettingsRow.Custom("content_language", Res.string.m6_content_language) {
                IosLaneValueChoice(languageLabel, preferences[ContentLanguageKey, "system"], languageOptions, onFailure) {
                    controller.edit { this[ContentLanguageKey] = it }
                }
            },
            SettingsRow.Custom("content_country", Res.string.m6_content_country) {
                IosLaneValueChoice(countryLabel, preferences[ContentCountryKey, "system"], countryOptions, onFailure) {
                    controller.edit { this[ContentCountryKey] = it }
                }
            },
            SettingsRow.Toggle("hide_explicit", Res.string.m6_hide_explicit, HideExplicitKey, false),
        ),
        preferences = preferences,
        controller = controller,
        scrollTo = scrollTo,
        onFailure = onFailure,
    )
}

/** (code, localized name) pairs for the platform's ISO catalogs; an unnamed code shows itself. */
private fun iosLaneIsoOptions(languages: Boolean): List<Pair<String, String>> {
    val locale = NSLocale.currentLocale
    val raw: List<*> = if (languages) NSLocale.ISOLanguageCodes else NSLocale.ISOCountryCodes
    return raw.filterIsInstance<String>().sorted().map { code ->
        val name = if (languages) {
            locale.localizedStringForLanguageCode(code)
        } else {
            locale.localizedStringForCountryCode(code)
        }
        code to (name?.replaceFirstChar { it.uppercase() } ?: code)
    }
}

/** A host-drawn choice row: the ISO catalogs are runtime text, which SettingsRow.Option cannot hold. */
@Composable
private fun IosLaneValueChoice(
    title: String,
    current: String,
    options: List<Pair<String, String>>,
    onFailure: () -> Unit,
    onPick: suspend (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(options.firstOrNull { it.first == current }?.second ?: current) },
        modifier = Modifier.clickable { open = true },
    )
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = { open = false }) { Text(stringResource(Res.string.m6_integration_ok)) }
            },
            title = { Text(title) },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp).fillMaxWidth()) {
                    items(options) { (value, label) ->
                        ListItem(
                            headlineContent = { Text(label) },
                            modifier = Modifier.clickable {
                                open = false
                                scope.launch {
                                    try {
                                        onPick(value)
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: Exception) {
                                        onFailure()
                                    }
                                }
                            },
                        )
                    }
                }
            },
        )
    }
}

/** Settings > Playback > Sources over the same probes the desktop host runs. */
@Composable
private fun IosLaneSourceSettingsPage(
    owners: IosSettingsOwners,
    navigate: (String) -> Unit,
    onFailure: () -> Unit,
    scrollTo: String?,
) {
    val snapshot by owners.controller.store.snapshots.collectAsState()
    val preferences = snapshot
    if (preferences == null) {
        CircularProgressIndicator()
        return
    }
    SourceSettings(
        controller = owners.controller,
        spotifyStreamingReady =
            iosSpotifyStreamingReady(preferences),
        // The Echo transport is present; the selected engine/method and account still gate readiness.
        spotifyStreamingPlatformSupported = true,
        poolConfigured = owners.poolConfigured,
        refreshPool = { iosLaneRefreshPool(owners) },
        checkSource = { type, prefs -> if (type == AudioSourceType.MODULES) prefs[ModulesEnabledKey] == true && owners.graph.modules.installed().isNotEmpty() else iosLaneCheckSource(owners.graph.http, type, prefs) },
        navigate = navigate,
        onFailure = onFailure,
        scrollTo = scrollTo,
    )
}

/** Mirrors the desktop pool check: unconfigured fails, and a configured pool must name at least one live account. */
internal suspend fun iosLaneRefreshPool(owners: IosSettingsOwners): SourcePoolRefreshOutcome {
    if (!owners.poolConfigured) return SourcePoolRefreshOutcome.FAILED
    return try {
        if (owners.graph.playbackPool.tidalAccounts().isNotEmpty()) {
            SourcePoolRefreshOutcome.REFRESHED
        } else {
            SourcePoolRefreshOutcome.FAILED
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        SourcePoolRefreshOutcome.FAILED
    }
}

/** Real per-source reachability, on the same endpoints the other hosts probe, with the same six-second bound. */
internal suspend fun iosLaneCheckSource(
    http: HttpClient,
    type: AudioSourceType,
    preferences: PreferenceSnapshot,
): Boolean = when (type) {
    AudioSourceType.TIDAL -> preferences.tidalInstanceList().firstOrNull()
        ?.let { iosLaneProbe(http, it.trimEnd('/') + "/health", fallback = it) } ?: false
    AudioSourceType.QOBUZ -> iosLaneProbe(http, "https://www.qobuz.com/api.json/0.2/status")
    AudioSourceType.QOBUZ_BACKUP -> iosLaneProbe(http, "https://kouzu.in/")
    AudioSourceType.DEEZER -> iosLaneProbe(http, "https://api.deezer.com/infos")
    AudioSourceType.APPLE -> iosLaneProbe(http, "https://amp-api.music.apple.com/v1/catalog/us/genres")
    AudioSourceType.AMAZON -> preferences.amazonInstanceList().firstOrNull()
        ?.let { iosLaneProbe(http, it) } ?: false
    AudioSourceType.QQ -> iosLaneProbe(http, "https://c.y.qq.com/")
    // This host has no Spotify streaming transport, so a signed-in streaming account is still not a reachable source here.
    AudioSourceType.SPOTIFY -> iosLaneProbe(http, "https://open.spotify.com/")
    // The mounted API-v1 host additionally checks installed audio packages in the settings binding.
    AudioSourceType.MODULES -> preferences[ModulesEnabledKey] == true
    AudioSourceType.JIOSAAVN ->
        iosLaneProbe(http, "https://www.jiosaavn.com/api.php?__call=webapi.getLaunchData&_format=json&_marker=0")
    AudioSourceType.YOUTUBE -> iosLaneProbe(http, "https://music.youtube.com/generate_204")
}

/**
 * One GET with a six-second bound; 2xx/3xx counts as reachable. The fallback is the bare base URL, where any HTTP
 * answer already proves reachability.
 */
internal suspend fun iosLaneProbe(http: HttpClient, url: String, fallback: String? = null): Boolean {
    suspend fun get(target: String, successRange: IntRange): Boolean = try {
        withTimeoutOrNull(IOS_LANE_PROBE_TIMEOUT_MS) {
            http.get(if (target.startsWith("http")) target else "https://$target")
        }?.let { it.status.value in successRange } ?: false
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }
    if (get(url, 200..399)) return true
    return fallback != null && get(fallback, 200..599)
}

private const val IOS_LANE_PROBE_TIMEOUT_MS = 6_000L
