package moe.rukamori.archivetune.shared.ui.ios

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import moe.rukamori.archivetune.shared.domain.settings.CanvasConnectivity
import moe.rukamori.archivetune.shared.domain.settings.CanvasHealthProvider
import moe.rukamori.archivetune.shared.domain.settings.CanvasProbeState
import moe.rukamori.archivetune.shared.domain.settings.CanvasProviderProbes
import moe.rukamori.archivetune.shared.domain.settings.DiscordAccountCheck
import moe.rukamori.archivetune.shared.domain.settings.DiscordAccountFacts
import moe.rukamori.archivetune.shared.domain.settings.UpdateChannel
import moe.rukamori.archivetune.shared.domain.settings.amazonInstanceList
import moe.rukamori.archivetune.shared.domain.update.UpdateRepository
import moe.rukamori.archivetune.shared.ui.IosHost
import moe.rukamori.archivetune.shared.ui.resources.Res
import moe.rukamori.archivetune.shared.ui.resources.ios_lane_spotify_streaming_note
import moe.rukamori.archivetune.shared.ui.resources.ios_lane_storage_note
import moe.rukamori.archivetune.shared.ui.settings.AmazonSettings
import moe.rukamori.archivetune.shared.ui.settings.CanvasSettings
import moe.rukamori.archivetune.shared.ui.settings.ChangelogRelease
import moe.rukamori.archivetune.shared.ui.settings.ChangelogSettings
import moe.rukamori.archivetune.shared.ui.settings.ChangelogState
import moe.rukamori.archivetune.shared.ui.settings.DiscordSettings
import moe.rukamori.archivetune.shared.ui.settings.DownloadsSettings
import moe.rukamori.archivetune.shared.ui.settings.InternetSettings
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

/**
 * The lane pages that need a host service behind them: reachability probes, the release-notes checker and the
 * Discord token check are real; cache clearing, storage moves, proxy application, PO-token extraction and Spotify
 * streaming sign-in have no owner on this host and report themselves unavailable rather than faking a result.
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
        "settings/discord" -> DiscordSettings(
            controller = owners.controller,
            // Rich Presence needs the Discord Gateway, which this host does not connect; sign-in needs an OAuth
            // redirect surface this host does not have. The saved-token check below is a real Discord API read.
            presenceSupported = false,
            currentTimeMillis = { Clock.System.now().toEpochMilliseconds() },
            authorize = {
                unavailable()
                null
            },
            verifyAccount = { token -> iosLaneVerifyDiscordAccount(owners.graph.http, token) },
            refreshPresence = { false },
            signOut = { },
            navigate = navigate,
            onNotice = onNotice,
            onFailure = onFailure,
            scrollTo = scrollTo,
        )
        "settings/downloads" -> DownloadsSettings(
            controller = owners.controller,
            poolConfigured = owners.poolConfigured,
            // The download store belongs to the Android player; this host has nothing to clear.
            clearDownloads = { unavailable() },
            navigate = navigate,
            onFailure = onFailure,
            scrollTo = scrollTo,
        )
        "settings/internet" -> InternetSettings(
            controller = owners.controller,
            // The iOS playback client applies neither the static proxy nor rotation; the edits persist so they
            // are ready on hosts that do apply them, and every apply/test reports its honest outcome.
            activeRotationCount = 0,
            applyNetworkSettings = { },
            applyIpRotation = { _, _ -> 0 },
            testProxy = { ProxyConnectionResult.FAILED },
            onFailure = onFailure,
            scrollTo = scrollTo,
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

/**
 * One real Discord API read of the saved token: the profile the bearer token names. Rejected means Discord refused
 * the token outright; any other failure is Unverified, so the row never claims a check that did not happen.
 */
private suspend fun iosLaneVerifyDiscordAccount(
    http: io.ktor.client.HttpClient,
    accessToken: String,
): DiscordAccountCheck = try {
    val response = http.get("https://discord.com/api/v10/users/@me") {
        header("Authorization", "Bearer $accessToken")
        header("Accept", "application/json")
    }
    when {
        response.status.isSuccess() -> {
            val body = Json { ignoreUnknownKeys = true }
                .parseToJsonElement(response.bodyAsText()) as? JsonObject
            val username = body?.text("username")
            if (username.isNullOrBlank()) {
                DiscordAccountCheck.Unverified
            } else {
                val id = body.text("id")
                val avatarHash = body.text("avatar")
                DiscordAccountCheck.Verified(
                    DiscordAccountFacts(
                        username = username,
                        displayName = body.text("global_name") ?: username,
                        avatarUrl = if (id != null && avatarHash != null) {
                            "https://cdn.discordapp.com/avatars/$id/$avatarHash.png"
                        } else {
                            null
                        },
                    ),
                )
            }
        }
        response.status.value == 401 || response.status.value == 403 -> DiscordAccountCheck.Rejected
        else -> DiscordAccountCheck.Unverified
    }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    DiscordAccountCheck.Unverified
}

private fun JsonObject.text(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
