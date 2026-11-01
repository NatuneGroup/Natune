package moe.rukamori.archivetune.shared.ui.ios

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.time.Clock
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import moe.rukamori.archivetune.shared.data.platform.ios.IosProxyTestResult
import moe.rukamori.archivetune.shared.domain.settings.CanvasConnectivity
import moe.rukamori.archivetune.shared.domain.settings.CanvasHealthProvider
import moe.rukamori.archivetune.shared.domain.settings.CanvasProbeState
import moe.rukamori.archivetune.shared.domain.settings.CanvasProviderProbes
import moe.rukamori.archivetune.shared.domain.settings.UpdateChannel
import moe.rukamori.archivetune.shared.domain.settings.amazonInstanceList
import moe.rukamori.archivetune.shared.domain.update.UpdateRepository
import moe.rukamori.archivetune.shared.ui.IosHost
import moe.rukamori.archivetune.shared.ui.resources.Res
import moe.rukamori.archivetune.shared.ui.resources.ios_lane_spotify_streaming_note
import moe.rukamori.archivetune.shared.ui.resources.ios_lane_storage_note
import moe.rukamori.archivetune.shared.ui.resources.ios_services_internet_applies_next_launch
import moe.rukamori.archivetune.shared.ui.resources.ios_services_internet_note
import moe.rukamori.archivetune.shared.ui.settings.AmazonSettings
import moe.rukamori.archivetune.shared.ui.settings.CanvasSettings
import moe.rukamori.archivetune.shared.ui.settings.ChangelogRelease
import moe.rukamori.archivetune.shared.ui.settings.ChangelogSettings
import moe.rukamori.archivetune.shared.ui.settings.ChangelogState
import moe.rukamori.archivetune.shared.ui.settings.DiscordSettings
import moe.rukamori.archivetune.shared.ui.settings.DownloadsSettings
import moe.rukamori.archivetune.shared.ui.settings.InternetSettings
import moe.rukamori.archivetune.shared.ui.settings.InternetSupport
import moe.rukamori.archivetune.shared.ui.settings.PoTokenGeneration
import moe.rukamori.archivetune.shared.ui.settings.PoTokenSettings
import moe.rukamori.archivetune.shared.ui.settings.ProxyConnectionResult
import moe.rukamori.archivetune.shared.ui.settings.QqLoginFailure
import moe.rukamori.archivetune.shared.ui.settings.QqLoginUiState
import moe.rukamori.archivetune.shared.ui.settings.QqMusicSettings
import moe.rukamori.archivetune.shared.ui.settings.SettingsNotice
import moe.rukamori.archivetune.shared.ui.settings.SpotifyStreamingSettings
import moe.rukamori.archivetune.shared.ui.settings.StorageSettings
import org.jetbrains.compose.resources.stringResource
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.dateWithTimeIntervalSince1970

/**
 * The lane pages that need a host service behind them: reachability probes, the release-notes checker, the Discord
 * presence owner, the proxy probe and Last.fm scrobbling are real; cache clearing, storage moves, PO-token extraction
 * and Spotify streaming sign-in have no owner on this host and report themselves unavailable rather than faking a
 * result. The internet page mounts the saved static route with the limits the Darwin engine imposes.
 */
@Composable
internal fun IosLaneServiceSettingsPage(
    path: String,
    host: IosHost,
    owners: IosSettingsOwners,
    channel: String?,
    navigate: (String) -> Unit,
    onNotice: (SettingsNotice) -> Unit,
    onFailure: () -> Unit,
    unavailable: () -> Unit,
    scrollTo: String?,
) {
    when (path) {
        "settings/amazon" -> {
            val snapshot by owners.controller.store.snapshots.collectAsState()
            AmazonSettings(
                controller = owners.controller,
                poolInstanceCount = snapshot?.amazonInstanceList()?.size ?: 0,
                currentTimeMillis = { Clock.System.now().toEpochMilliseconds() },
                formatExpiry = ::iosLaneFormatExpiry,
                onAuthorize = unavailable,
                onFailure = onFailure,
                onNotice = onNotice,
                scrollTo = scrollTo,
            )
        }
        "settings/canvas" -> {
            val connectivity by produceState<CanvasConnectivity?>(null) {
                value = CanvasConnectivity(
                    online = iosLaneProbe(owners.graph.http, "https://music.youtube.com/generate_204"),
                    wifi = true,
                    metered = false,
                )
            }
            val probes = remember { MutableStateFlow(CanvasProviderProbes()) }
            val probeStates by probes.collectAsState()
            CanvasSettings(
                controller = owners.controller,
                connectivity = connectivity,
                providerProbes = probeStates,
                refreshHealth = { providers -> iosLaneRefreshCanvasHealth(owners.graph.http, probes, providers) },
                // This host has no canvas cache store to measure or trim, so the rows throw into onFailure.
                cacheBytes = null,
                applyCacheLimit = { throw IllegalStateException("no canvas cache on this host") },
                clearCache = { throw IllegalStateException("no canvas cache on this host") },
                onFailure = onFailure,
            )
        }
        "settings/changelog" -> {
            val releaseChannel = UpdateChannel.entries.firstOrNull { it.name == channel }
                ?: owners.releaseNotes.selectedChannel()
            var retryTick by remember { mutableIntStateOf(0) }
            val state by produceState<ChangelogState>(ChangelogState.Loading, releaseChannel, retryTick) {
                value = ChangelogState.Loading
                value = when (releaseChannel) {
                    UpdateChannel.STABLE -> owners.releaseNotes.allReleases(forceRefresh = retryTick > 0)
                    UpdateChannel.NIGHTLY -> owners.releaseNotes.allPreReleases(forceRefresh = retryTick > 0)
                }.map { releases ->
                    releases.filter { it.tagName != UpdateRepository.UPDATE_FEED_TAG }
                        .map { ChangelogRelease(it.tagName, it.name ?: it.tagName, it.publishedAt, it.body) }
                }.fold(
                    onSuccess = { ChangelogState.Loaded(it) },
                    onFailure = { ChangelogState.Failed },
                )
            }
            ChangelogSettings(
                channel = releaseChannel,
                state = state,
                onRetry = { retryTick += 1 },
                onOpenUrl = { url -> openIosUri(url, host::report) },
            )
        }
        "settings/discord" -> {
            // Presence updates follow playback only; the driver starts when the page is visited, as the desktop's does.
            LaunchedEffect(Unit) { owners.discord.startPresenceDriver() }
            DiscordSettings(
                controller = owners.controller,
                presenceSupported = true,
                currentTimeMillis = { Clock.System.now().toEpochMilliseconds() },
                authorize = owners.discord::authorize,
                verifyAccount = owners.discord::verifyAccount,
                refreshPresence = owners.discord::refreshPresence,
                signOut = owners.discord::signOut,
                navigate = navigate,
                onNotice = onNotice,
                onFailure = onFailure,
                scrollTo = scrollTo,
                avatar = { url, _, modifier -> IosRemoteArtwork(url, owners.graph.artworkLoader, modifier) },
            )
        }
        "settings/downloads" -> DownloadsSettings(
            controller = owners.controller,
            poolConfigured = owners.poolConfigured,
            // The download store belongs to the Android player; this host has nothing to clear.
            clearDownloads = { unavailable() },
            navigate = navigate,
            onFailure = onFailure,
            scrollTo = scrollTo,
        )
        "settings/internet" -> {
            val network = owners.graph.network
            Column(Modifier.fillMaxSize()) {
                Text(
                    stringResource(Res.string.ios_services_internet_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                InternetSettings(
                    controller = owners.controller,
                    // No route is offered while running: the Darwin engine keeps the proxy it started with.
                    activeRotationCount = 0,
                    applyNetworkSettings = { snapshot ->
                        // The edits are already persisted by the page. This reports the one thing it cannot do: the
                        // running session keeps its route until the next launch.
                        if (network.savedRouteChanged(snapshot)) {
                            onNotice(SettingsNotice.Message(Res.string.ios_services_internet_applies_next_launch))
                        }
                    },
                    applyIpRotation = { _, _ -> 0 },
                    testProxy = { snapshot -> network.testProxy(snapshot).toProxyConnectionResult() },
                    onFailure = onFailure,
                    scrollTo = scrollTo,
                    modifier = Modifier.weight(1f),
                    // The Darwin engine cannot re-route a live session, and playback audio never takes the proxy, so the
                    // stream switch does not apply. DNS-over-HTTPS and the YouTube region have no iOS runtime yet.
                    support = InternetSupport(
                        rotation = false, streamBypass = false, dnsOverHttps = false, youtubeRegion = false,
                    ),
                )
            }
        }
        "settings/lastfm" -> IosLastFmSettingsPage(
            lastFm = owners.graph.lastFm,
            controller = owners.controller,
            onFailure = onFailure,
            scrollTo = scrollTo,
            report = host::report,
        )
        "settings/po_token" -> PoTokenSettings(
            controller = owners.controller,
            generateTokens = { PoTokenGeneration.Failed(null) },
            copyToClipboard = ::copyToIosClipboard,
            onFailure = onFailure,
        )
        "settings/qqmusic" -> QqMusicSettings(
            controller = owners.controller,
            accounts = owners.accounts,
            // The QR sign-in loop has no iOS implementation, so the card shows its unavailable state.
            loginState = QqLoginUiState.Failed(QqLoginFailure.UNAVAILABLE),
            onStartSignIn = { },
            onCancelSignIn = { },
            onSignOut = { },
            onFailure = onFailure,
            scrollTo = scrollTo,
        )
        "settings/spotify_streaming" -> SpotifyStreamingSettings(
            controller = owners.controller,
            signInInProgress = false,
            signInRefusal = null,
            onSignIn = unavailable,
            onDismissSignInRefusal = { },
            onSignOut = { },
            onFailure = onFailure,
            scrollTo = scrollTo,
            hostNote = stringResource(Res.string.ios_lane_spotify_streaming_note),
        )
        "settings/storage" -> StorageSettings(
            controller = owners.controller,
            usedBytes = emptyMap(),
            currentStorageLabel = stringResource(Res.string.ios_lane_storage_note),
            storageBusy = false,
            pickAndCommitStorage = {
                unavailable()
                null
            },
            clearCache = { unavailable() },
            applyCacheLimits = { },
            onFailure = onFailure,
            scrollTo = scrollTo,
        )
        else -> IosLaneUnavailablePage()
    }
}

/** The platform's locale-aware timestamp, matching the desktop page's `yyyy-MM-dd HH:mm`. */
private fun iosLaneFormatExpiry(millis: Long): String =
    NSDateFormatter()
        .apply { dateFormat = "yyyy-MM-dd HH:mm" }
        .stringFromDate(NSDate.dateWithTimeIntervalSince1970(millis / 1000.0))

/** The iOS probe's sanitized outcome in the shared page's vocabulary; DNS and host failures both read as unreachable. */
private fun IosProxyTestResult.toProxyConnectionResult(): ProxyConnectionResult =
    when (this) {
        IosProxyTestResult.CONNECTED -> ProxyConnectionResult.CONNECTED
        IosProxyTestResult.INVALID_CONFIGURATION -> ProxyConnectionResult.INVALID_CONFIGURATION
        IosProxyTestResult.AUTHENTICATION -> ProxyConnectionResult.AUTHENTICATION
        IosProxyTestResult.TIMEOUT -> ProxyConnectionResult.TIMEOUT
        IosProxyTestResult.UNREACHABLE -> ProxyConnectionResult.UNREACHABLE
        IosProxyTestResult.FAILED -> ProxyConnectionResult.FAILED
    }

/** Probes the Canvas providers on the same endpoints the desktop host uses, reporting each state as it lands. */
private suspend fun iosLaneRefreshCanvasHealth(
    http: io.ktor.client.HttpClient,
    probes: MutableStateFlow<CanvasProviderProbes>,
    providers: Set<CanvasHealthProvider>,
) {
    val endpoints = mapOf(
        CanvasHealthProvider.BETTER_LYRICS to "https://better-lyrics-api.boidu.dev/",
        CanvasHealthProvider.APPLE_MUSIC to "https://amp-api.music.apple.com/v1/catalog/us/genres",
        CanvasHealthProvider.TIDAL to "https://api.tidal.com/",
        CanvasHealthProvider.SPOTIFY to "https://open.spotify.com/",
    )
    val checks = providers.mapNotNull { provider -> endpoints[provider]?.let { provider to it } }
    checks.forEach { (provider, _) -> probes.value = probes.value.withState(provider, CanvasProbeState.CHECKING) }
    coroutineScope {
        checks.map { (provider, endpoint) ->
            async {
                val reachable = iosLaneProbe(http, endpoint)
                provider to if (reachable) CanvasProbeState.AVAILABLE else CanvasProbeState.UNAVAILABLE
            }
        }.awaitAll()
    }.forEach { (provider, state) -> probes.value = probes.value.withState(provider, state) }
}

private fun CanvasProviderProbes.withState(provider: CanvasHealthProvider, state: CanvasProbeState) =
    when (provider) {
        CanvasHealthProvider.BETTER_LYRICS -> copy(betterLyrics = state)
        CanvasHealthProvider.APPLE_MUSIC -> copy(appleMusic = state)
        CanvasHealthProvider.TIDAL -> copy(tidal = state)
        CanvasHealthProvider.SPOTIFY -> copy(spotify = state)
    }

