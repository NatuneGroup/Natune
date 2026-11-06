package moe.rukamori.archivetune.shared.ui.ios

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.isSuccess
import kotlin.time.Clock
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.shared.data.platform.ios.IosTelegramClient
import moe.rukamori.archivetune.shared.data.platform.ios.IosAppleMusicLoginSheet
import moe.rukamori.archivetune.shared.data.platform.ios.IosTidalPkceSheet
import moe.rukamori.archivetune.shared.data.platform.ios.IosTelegramNativeAdmission
import moe.rukamori.archivetune.shared.data.platform.ios.IosProviderCredentials
import moe.rukamori.archivetune.shared.data.platform.ios.iosDiagnosticTime
import moe.rukamori.archivetune.shared.data.provider.QobuzAccountVerifier
import moe.rukamori.archivetune.shared.data.provider.QobuzBundleScrape
import moe.rukamori.archivetune.shared.data.provider.QobuzInstanceProbe
import moe.rukamori.archivetune.shared.data.provider.TidalAccountDeviceLink
import moe.rukamori.archivetune.shared.data.provider.TidalRefreshResult
import moe.rukamori.archivetune.shared.data.platform.ios.IosCookieOnlyBrowserAuth
import moe.rukamori.archivetune.shared.data.provider.verifyDeezerArl
import moe.rukamori.archivetune.shared.data.ai.KtorAiTextService
import moe.rukamori.archivetune.shared.data.lyrics.PaxsenixDiagnostics
import moe.rukamori.archivetune.shared.domain.lyrics.romanization.JapaneseLanguagePackManager
import moe.rukamori.archivetune.shared.domain.presence.ListenerPresenceState
import moe.rukamori.archivetune.shared.domain.platform.BrowserAuthPort
import moe.rukamori.archivetune.shared.domain.presence.ListenerPresenceStore
import moe.rukamori.archivetune.shared.domain.settings.DataSyncIdKey
import moe.rukamori.archivetune.shared.domain.settings.InnerTubeCookieKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.QobuzTokenEntry
import moe.rukamori.archivetune.shared.domain.settings.SourceHealth
import moe.rukamori.archivetune.shared.domain.settings.SourceHealthStatus
import moe.rukamori.archivetune.shared.domain.settings.TidalAuthFlowDevice
import moe.rukamori.archivetune.shared.domain.settings.TidalDeviceLink
import moe.rukamori.archivetune.shared.domain.settings.UpdateChannel
import moe.rukamori.archivetune.shared.domain.update.UpdateInstallMode
import moe.rukamori.archivetune.shared.domain.update.UpdateInstallProfile
import moe.rukamori.archivetune.shared.data.update.SharedUpdateChecker
import moe.rukamori.archivetune.shared.domain.settings.TidalInstanceHealth
import moe.rukamori.archivetune.shared.domain.settings.TidalInstanceStatus
import moe.rukamori.archivetune.shared.domain.settings.TidalSessionFacts
import moe.rukamori.archivetune.shared.domain.settings.tidalInstanceList
import moe.rukamori.archivetune.shared.domain.settings.youTubeLoginBlock
import moe.rukamori.archivetune.shared.domain.dsp.acoustic.AcousticAuditTarget
import moe.rukamori.archivetune.shared.ui.IosHost
import moe.rukamori.archivetune.shared.ui.acoustic.AcousticQualityPanel
import moe.rukamori.archivetune.shared.ui.host.paxsenixSettingsOf
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveRoute
import moe.rukamori.archivetune.shared.ui.navigation.LocalShellContentPadding
import moe.rukamori.archivetune.shared.ui.navigation.ShellNavigation
import moe.rukamori.archivetune.shared.ui.presence.ListenerPresenceSection
import moe.rukamori.archivetune.shared.ui.resources.*
import moe.rukamori.archivetune.shared.ui.resources.ios_lane_spotify_browser_unavailable
import moe.rukamori.archivetune.shared.ui.resources.ios_lane_spotify_sign_in_failed
import moe.rukamori.archivetune.shared.ui.resources.ios_settings_change_failed
import moe.rukamori.archivetune.shared.ui.resources.ios_settings_feature_unavailable
import moe.rukamori.archivetune.shared.ui.screens.diagnostics.DiagnosticLogActions
import moe.rukamori.archivetune.shared.ui.screens.diagnostics.DiagnosticLogScreen
import moe.rukamori.archivetune.shared.ui.settings.AccountSettings
import moe.rukamori.archivetune.shared.ui.settings.AppleMusicLoginRoute
import moe.rukamori.archivetune.shared.ui.settings.CrashLogActions
import moe.rukamori.archivetune.shared.ui.settings.CrashLogRecordRoute
import moe.rukamori.archivetune.shared.ui.settings.CrashLogRecordSettings
import moe.rukamori.archivetune.shared.ui.settings.CrashLogsRoute
import moe.rukamori.archivetune.shared.ui.settings.CrashLogsSettings
import moe.rukamori.archivetune.shared.ui.settings.AddonDownloadUiState
import moe.rukamori.archivetune.shared.ui.settings.AiIntegrationSettings
import moe.rukamori.archivetune.shared.ui.settings.AiSettingsActions
import moe.rukamori.archivetune.shared.ui.settings.DeveloperSettings
import moe.rukamori.archivetune.shared.ui.settings.HostDiscordDebugSection
import moe.rukamori.archivetune.shared.ui.settings.HostNerdStatsSection
import moe.rukamori.archivetune.shared.ui.settings.IntegrationSettings
import moe.rukamori.archivetune.shared.ui.settings.LocalSettingsBack
import moe.rukamori.archivetune.shared.ui.settings.LanguagePackSettings
import moe.rukamori.archivetune.shared.ui.settings.LyricsProviderSettings
import moe.rukamori.archivetune.shared.ui.settings.LyricsRomanisationSettings
import moe.rukamori.archivetune.shared.ui.settings.japanesePackUiState
import moe.rukamori.archivetune.shared.ui.settings.DeezerArlAccount
import moe.rukamori.archivetune.shared.ui.settings.DeezerSettings
import moe.rukamori.archivetune.shared.ui.settings.ProviderAccountLifecycle
import moe.rukamori.archivetune.shared.ui.settings.ProviderSignInFailure
import moe.rukamori.archivetune.shared.ui.settings.ProviderSignInKind
import moe.rukamori.archivetune.shared.ui.settings.ProviderSignInOutcome
import moe.rukamori.archivetune.shared.ui.settings.ProviderSignInPage
import moe.rukamori.archivetune.shared.ui.settings.ProviderSignInState
import moe.rukamori.archivetune.shared.ui.settings.QobuzLoginRoute
import moe.rukamori.archivetune.shared.ui.settings.QobuzLoginScreen
import moe.rukamori.archivetune.shared.ui.settings.QobuzSettings
import moe.rukamori.archivetune.shared.ui.settings.SettingsController
import moe.rukamori.archivetune.shared.ui.settings.SettingsHomePage
import moe.rukamori.archivetune.shared.ui.settings.SettingsNotice
import moe.rukamori.archivetune.shared.ui.settings.AndroidOnlySettingsAnchors
import moe.rukamori.archivetune.shared.ui.settings.LocalUnsupportedSettingsAnchors
import moe.rukamori.archivetune.shared.ui.settings.SettingsPage
import moe.rukamori.archivetune.shared.ui.settings.TelegramSettings
import moe.rukamori.archivetune.shared.ui.settings.DeezerLoginRoute
import moe.rukamori.archivetune.shared.ui.settings.TidalLoginRoute
import moe.rukamori.archivetune.shared.ui.settings.TidalSettings
import moe.rukamori.archivetune.shared.ui.settings.UpdateCommit
import moe.rukamori.archivetune.shared.ui.settings.UpdateFeedResult
import moe.rukamori.archivetune.shared.ui.settings.UpdateSettings
import moe.rukamori.archivetune.shared.ui.settings.YouTubeAccountRoute
import moe.rukamori.archivetune.shared.ui.settings.YouTubeLoginRoute
import moe.rukamori.archivetune.shared.ui.settings.privacyInlineSettingsRows
import moe.rukamori.archivetune.shared.ui.settings.settingsNoticeText
import org.jetbrains.compose.resources.stringResource
import platform.Foundation.NSByteCountFormatter
import platform.Foundation.NSByteCountFormatterCountStyleFile
import platform.UIKit.UIViewController

/**
 * The settings owners of the iOS host: one controller over the graph's settings, and the graph's sign-in lifecycle and
 * TIDAL account endpoints. The device link shows when this build carries a TIDAL device client, and a pasted refresh
 * token is always verified with TIDAL.
 */
internal class IosSettingsOwners(
    internal val graph: IosSessionGraph,
    browser: IosCookieOnlyBrowserAuth,
    browserAuth: BrowserAuthPort,
    appleMusicSheet: IosAppleMusicLoginSheet,
    presenter: () -> UIViewController,
) {
    val deezerWeb = IosDeezerWebSignIn(graph, browser)
    val appleMusicWeb = IosAppleMusicWebSignIn(graph, appleMusicSheet)
    val spotifyCatalog = IosSpotifyCatalogAccount(graph, browser)
    val spotifyStreaming = IosSpotifyStreamingAccount(graph, browser, browserAuth)
    val amazonAuthorize = IosAmazonAuthorize(graph, presenter)
    val controller = SettingsController(
        graph.settings,
        graph.settings.snapshots.value ?: PreferenceSnapshot.EMPTY,
        // The iOS engine has no bit-perfect output path, so its restart reasons are never offered.
        bitPerfectSupported = false,
    )
    val accounts = graph.accountLifecycle

    /** The QQ Music QR sign-in owner; the settings card's attempts share the graph's engine and lifecycle. */
    val qqMusic = IosQqMusicLoginOwner(graph.scope, accounts, graph.http, graph::report)

    /** The graph's Discord owner: the settings page starts its presence driver and drives its OAuth sheet. */
    val discord: IosDiscordPresence get() = graph.discord

    /** The YouTube sign-in sheet and account owner; the Settings > Account page and the login page both use it. */
    val youTube = IosYouTubeAccountOwner(graph, controller, browser)

    /** Lazy so the AI service is built only when its page opens; it shares the graph's HTTP engine and scope. */
    val aiActions by lazy { AiSettingsActions(graph.scope, controller, KtorAiTextService(graph.http)) }

    val tidalInstanceHealth = MutableStateFlow<Map<String, TidalInstanceHealth>>(emptyMap())

    /** The device-link flow when this build carries a TIDAL device client, else the row stays disabled. */
    val tidalDeviceLink: TidalDeviceLink? =
        if (graph.tidalAuth.deviceSignInAvailable) TidalAccountDeviceLink(graph.tidalAuth) else null

    /** Whether this build names a pool deployment, so the pool rows report their real availability. */
    val poolConfigured: Boolean get() = graph.playbackPool.configured

    /** Mirrors the Playback Pool switch into the feed, as on the other hosts. */
    suspend fun onPoolAccountsChanged(enabled: Boolean) = graph.playbackPool.onEnabledChanged(enabled)

    /**
     * iOS has no sideload path, so [UpdateInstallMode.UNAVAILABLE] keeps every row honest while the real
     * checker still feeds the commit history. Version facts are unknown to this host (installedCode = 0),
     * which is exactly the app's noBuildCode shape: nothing is ever offered.
     */
    val updateChecker = SharedUpdateChecker(
        client = graph.http,
        settings = graph.settings,
        profile = UpdateInstallProfile(
            updaterAvailable = false,
            installMode = UpdateInstallMode.UNAVAILABLE,
            isNightlyBuild = false,
            installedCode = 0,
            versionName = "",
        ),
        nowMs = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
    )

    /**
     * Release notes only: unlike [updateChecker] this one reads the feeds (updaterAvailable = true) so the changelog
     * page has real releases, while [UpdateInstallMode.UNAVAILABLE] keeps it from ever offering an install.
     */
    val releaseNotes by lazy {
        SharedUpdateChecker(
            client = graph.http,
            settings = graph.settings,
            profile = UpdateInstallProfile(
                updaterAvailable = true,
                installMode = UpdateInstallMode.UNAVAILABLE,
                isNightlyBuild = false,
                installedCode = 0,
                versionName = "",
            ),
            nowMs = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
        )
    }
    /** A pasted refresh token counts only once TIDAL exchanged it and its session names the account. */
    suspend fun verifyTidalRefreshToken(refreshToken: String): TidalSessionFacts? =
        when (val result = graph.tidalAuth.refresh(refreshToken, TidalAuthFlowDevice)) {
            is TidalRefreshResult.Refreshed ->
                graph.tidalAuth.verifiedSession(result.grant, TidalAuthFlowDevice, refreshFallback = refreshToken)
            else -> null
        }

    suspend fun verifyDeezer(arl: String): DeezerArlAccount? =
        verifyDeezerArl(graph.http, arl)?.let { DeezerArlAccount(it.name, it.lossless, it.userId) }

    val tidalWebState = MutableStateFlow<ProviderSignInState>(ProviderSignInState.Idle)
    private var tidalWebGeneration = 0L

    /** Dismissed sheet or navigated away: the next redirect-captured code belongs to a dead attempt. */
    fun cancelTidalWeb() {
        tidalWebGeneration += 1
        tidalWebState.value = ProviderSignInState.Idle
    }

    /**
     * The PKCE web sign-in the Android `settings/tidal/login` WebView ran: the authorize page navigates inside the
     * embedded sheet until it returns to the fixed https redirect, then the captured code is exchanged and the
     * session is verified and committed. No cookie or token is ever written to logs or settings outside the vault.
     */
    suspend fun signInTidalPkce(sheet: IosTidalPkceSheet) {
        val generation = ++tidalWebGeneration
        if (!graph.tidalAuth.pkceSignInAvailable) {
            tidalWebState.value = ProviderSignInState.Failed(ProviderSignInFailure.UNAVAILABLE)
            return
        }
        tidalWebState.value = ProviderSignInState.Opening
        val authorization = try {
            graph.tidalAuth.startPkceAuthorization()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (authorization == null) {
            tidalWebState.value = ProviderSignInState.Failed(ProviderSignInFailure.UNAVAILABLE)
            return
        }
        if (generation != tidalWebGeneration) return
        tidalWebState.value = ProviderSignInState.Waiting
        val code = sheet.authorizeCode(authorization.authorizeUrl)
        if (generation != tidalWebGeneration) return
        if (code.isNullOrBlank()) {
            tidalWebState.value = ProviderSignInState.Idle
            return
        }
        tidalWebState.value = ProviderSignInState.Verifying
        val session = try {
            graph.tidalAuth.exchangePkceCode(authorization, code)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (generation != tidalWebGeneration) return
        if (session == null) {
            tidalWebState.value = ProviderSignInState.Failed(ProviderSignInFailure.UNVERIFIED)
            return
        }
        val attempt = graph.accountLifecycle.begin(ProviderAccountLifecycle.TIDAL)
        val outcome = graph.accountLifecycle.commitTidal(attempt, session)
        if (generation != tidalWebGeneration) return
        tidalWebState.value = when (outcome) {
            ProviderSignInOutcome.Accepted ->
                ProviderSignInState.SignedIn(session.accountName ?: session.userId?.toString().orEmpty())
            ProviderSignInOutcome.NotSaved -> ProviderSignInState.Failed(ProviderSignInFailure.NOT_SAVED)
            else -> ProviderSignInState.Failed(ProviderSignInFailure.UNVERIFIED)
        }
    }

    private val qobuzVerifier = QobuzAccountVerifier(graph.http)
    private val qobuzScrape = QobuzBundleScrape(graph.http)
    private val qobuzProbe = QobuzInstanceProbe(graph.http)
    val qobuzTokenHealth = MutableStateFlow<Map<String, SourceHealth>>(emptyMap())
    val qobuzInstanceHealth = MutableStateFlow<Map<String, SourceHealth>>(emptyMap())

    /** The settings page's token test: the same two-call API proof per entry, with latency for the row. */
    suspend fun checkQobuzTokens(tokens: List<QobuzTokenEntry>, probeTrack: String, quality: String) {
        coroutineScope {
            tokens.map { entry ->
                async {
                    val started = TimeSource.Monotonic.markNow()
                    val status = try {
                        qobuzVerifier.verify(entry)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        SourceHealthStatus.UNREACHABLE
                    }
                    entry.id to SourceHealth(status, latencyMs = started.elapsedNow().inWholeMilliseconds)
                }
            }.awaitAll()
        }.also { results -> qobuzTokenHealth.update { it + results } }
    }

    /** The settings page's instance test: the same search + download-music probe per configured base. */
    suspend fun checkQobuzInstances(instances: List<String>, probeTrack: String, quality: String) {
        val formatId = when (quality) {
            "HI_RES" -> 7
            "MAX" -> 27
            else -> 6
        }
        coroutineScope {
            instances.map { base ->
                async {
                    val started = TimeSource.Monotonic.markNow()
                    val status = try {
                        qobuzProbe.verify(base, probeTrack.ifBlank { null }, formatId)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        SourceHealthStatus.UNREACHABLE
                    }
                    base to SourceHealth(status, latencyMs = started.elapsedNow().inWholeMilliseconds)
                }
            }.awaitAll()
        }.also { results -> qobuzInstanceHealth.update { it + results } }
    }

    fun forgetQobuzTokenHealth(ids: Set<String>) {
        qobuzTokenHealth.update { it - ids }
    }

    fun forgetQobuzInstanceHealth(instances: Set<String>) {
        qobuzInstanceHealth.update { it - instances }
    }

    suspend fun qobuzSecretCandidates(): List<String> = qobuzScrape.candidates().orEmpty()

    suspend fun verifyQobuzToken(entry: QobuzTokenEntry): SourceHealthStatus =
        try {
            qobuzVerifier.verify(entry)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            SourceHealthStatus.UNREACHABLE
        }

    /** Probes every configured instance at once with one bounded search, which proves reachability only. */
    suspend fun checkTidalInstances() {
        val instances = graph.settings.snapshots.value?.tidalInstanceList().orEmpty()
        val results = coroutineScope {
            instances.map { base -> async { base to probeTidalInstance(base) } }.awaitAll()
        }
        tidalInstanceHealth.update { it + results }
    }

    fun forgetTidalInstanceHealth(instances: Set<String>) {
        tidalInstanceHealth.update { it - instances }
    }

    private suspend fun probeTidalInstance(base: String): TidalInstanceHealth {
        val started = TimeSource.Monotonic.markNow()
        val reachable = try {
            withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                graph.http.get("${base.trimEnd('/')}/search/") {
                    parameter("s", PROBE_QUERY)
                    parameter("limit", "1")
                }.status.isSuccess()
            } ?: false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        if (!reachable) return TidalInstanceHealth(TidalInstanceStatus.UNREACHABLE)
        return TidalInstanceHealth(TidalInstanceStatus.HEALTHY, latencyMs = started.elapsedNow().inWholeMilliseconds)
    }

    private companion object {
        const val PROBE_TIMEOUT_MS = 8_000L
        const val PROBE_QUERY = "love"
    }
}

private const val IOS_UPDATE_COMMIT_COUNT = 20

/** The settings routes this host mounts. Every other settings route keeps the unbound boundary. */
internal fun iosMountsSettings(route: ArchiveRoute): Boolean = route.value.substringBefore('?') in IOS_SETTINGS_ROUTES

private val IOS_SETTINGS_ROUTES = setOf(
    "settings", "settings/tidal", "settings/deezer", "settings/telegram", "settings/privacy", "settings/logcat",
    AppleMusicLoginRoute, CrashLogsRoute, CrashLogRecordRoute,
    "settings/qobuz", QobuzLoginRoute, TidalLoginRoute, DeezerLoginRoute, "settings/update",
    "settings/account", YouTubeAccountRoute, YouTubeLoginRoute, IosYouTubeOAuthRoute,
    "settings/integration", "settings/integrations/device_connect", "settings/misc", "settings/language_packs", "settings/lyrics/providers",
    "settings/lyrics/romanisation", "settings/ai_integration", "settings/about", IosListenTogetherSettingsRoute,
) + IOS_GENERAL_SETTINGS_ROUTES + IOS_LANE_SETTINGS_ROUTES

/** One mounted settings page. Notices and host messages show in this page's snackbar; a failed save is logged and announced. */
@Composable
internal fun IosSettingsScreen(host: IosHost, owners: IosSettingsOwners, route: ArchiveRoute, navigation: ShellNavigation) {
    // This shell draws its own navigation chrome, so no system back gesture exists: the host supplies the pop.
    val back: () -> Unit = { navigation.back() }
    CompositionLocalProvider(
        LocalSettingsBack provides back,
        // The shared pages stay mounted; only rows whose readers are Android-only are omitted here.
        LocalUnsupportedSettingsAnchors provides AndroidOnlySettingsAnchors.all,
    ) {
        IosSettingsRouteBody(host, owners, route, navigation)
    }
}

@Composable
private fun IosSettingsRouteBody(
    host: IosHost,
    owners: IosSettingsOwners,
    route: ArchiveRoute,
    navigation: ShellNavigation,
) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showCrossServiceImport by remember { mutableStateOf(false) }
    val scrollTo = route.arguments["scrollTo"]
    val navigate: (String) -> Unit = { target -> navigation.open(target) }
    val changeFailed = stringResource(Res.string.ios_settings_change_failed)
    val featureUnavailable = stringResource(Res.string.ios_settings_feature_unavailable)
    fun show(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }
    val onNotice: (SettingsNotice) -> Unit = { notice -> scope.launch { snackbar.showSnackbar(settingsNoticeText(notice)) } }
    val onFailure: () -> Unit = {
        host.report("a settings change could not be completed")
        show(changeFailed)
    }
    Box(Modifier.fillMaxSize().padding(LocalShellContentPadding.current)) {
        when (route.value.substringBefore('?')) {
            "settings" -> SettingsHomePage(
                controller = owners.controller,
                navigate = navigate,
                onFailure = onFailure,
                scrollTo = scrollTo,
            )
            "settings/tidal" -> {
                val health by owners.tidalInstanceHealth.collectAsState()
                TidalSettings(
                    controller = owners.controller,
                    accounts = owners.accounts,
                    deviceLink = owners.tidalDeviceLink,
                    openExternal = { uri -> openIosUri(uri, host::report) },
                    instanceHealth = health,
                    checkInstances = owners::checkTidalInstances,
                    forgetInstanceHealth = owners::forgetTidalInstanceHealth,
                    verifyRefreshToken = owners::verifyTidalRefreshToken,
                    // TIDAL repositories are built per resolve from the account lease, so no token is held in memory.
                    onSignOut = { },
                    copyToClipboard = ::copyToIosClipboard,
                    navigate = navigate,
                    onNotice = onNotice,
                    onFailure = onFailure,
                    scrollTo = scrollTo,
                )
            }
            DeezerLoginRoute -> {
                val signInState by owners.deezerWeb.state.collectAsState()
                ProviderSignInPage(
                    kind = ProviderSignInKind.DEEZER_WEB,
                    state = signInState,
                    onStart = { scope.launch { owners.deezerWeb.signIn() } },
                    onCancel = owners.deezerWeb::cancel,
                    onDone = { navigation.back() },
                )
            }
            AppleMusicLoginRoute -> {
                val signInState by owners.appleMusicWeb.state.collectAsState()
                ProviderSignInPage(
                    kind = ProviderSignInKind.APPLE_MUSIC_WEB,
                    state = signInState,
                    onStart = { scope.launch { owners.appleMusicWeb.signIn() } },
                    onCancel = owners.appleMusicWeb::cancel,
                    onDone = { navigation.back() },
                )
            }
            CrashLogsRoute, CrashLogRecordRoute -> {
                val store = host.crashLogs
                if (store == null) {
                    IosUnboundSurface(Modifier.fillMaxSize())
                } else {
                    val crashActions = CrashLogActions(
                        back = { navigation.back() },
                        copyToClipboard = ::copyToIosClipboard,
                        share = { text -> iosShareText(host, text) },
                    )
                    if (route.value.substringBefore('?') == CrashLogsRoute) {
                        CrashLogsSettings(store, ::iosDiagnosticTime, navigate, crashActions, onFailure)
                    } else {
                        CrashLogRecordSettings(store, route.arguments["id"], ::iosDiagnosticTime, crashActions, onFailure)
                    }
                }
            }
            TidalLoginRoute -> {
                val signInState by owners.tidalWebState.collectAsState()
                ProviderSignInPage(
                    kind = ProviderSignInKind.TIDAL_WEB,
                    state = signInState,
                    onStart = { scope.launch { owners.signInTidalPkce(host.tidalPkceSheet) } },
                    onCancel = owners::cancelTidalWeb,
                    onDone = { navigation.back() },
                )
            }
            "settings/account", YouTubeAccountRoute -> {
                val snapshot by owners.graph.settings.snapshots.collectAsState()
                val channels by owners.youTube.channels.collectAsState()
                val sessionKey = snapshot?.let { "${it[InnerTubeCookieKey, ""].hashCode()}|${it[DataSyncIdKey, ""]}" }
                LaunchedEffect(sessionKey) { owners.youTube.refreshChannels() }
                AccountSettings(
                    controller = owners.controller,
                    channels = channels,
                    verifyAccount = owners.youTube::verify,
                    logOut = owners.youTube::logOut,
                    revokeCodeSession = owners.youTube::revokeCodeSession,
                    onAuthCommitted = { scope.launch { owners.youTube.sessionChanged() } },
                    onForceSyncAfterSwitch = {
                        scope.launch {
                            runCatching { owners.graph.youtubeLibrarySync.performFullSync(authoritative = true) }
                        }
                    },
                    navigate = navigate,
                    onNotice = onNotice,
                    onFailure = onFailure,
                    scrollTo = scrollTo,
                    avatar = { url, _, modifier -> IosRemoteArtwork(url, owners.graph.artworkLoader, modifier) },
                    revealCredentialBlock = { owners.graph.settings.snapshots.value?.youTubeLoginBlock() },
                    copyToClipboard = ::copyToIosClipboard,
                )
            }
            YouTubeLoginRoute -> IosYouTubeLoginPage(owners.youTube, navigation)
            IosYouTubeOAuthRoute -> IosYouTubeOAuthPage(owners.graph.http, owners.controller, navigation, host::report)
            "settings/deezer" -> DeezerSettings(
                controller = owners.controller,
                accounts = owners.accounts,
                poolConfigured = owners.poolConfigured,
                verifyArl = owners::verifyDeezer,
                // The Deezer source keys its session to the lease generation, which a sign-out retires.
                onSignOut = { },
                navigate = navigate,
                onFailure = onFailure,
                onNotice = onNotice,
                scrollTo = scrollTo,
            )
            "settings/qobuz" -> {
                val tokenHealth by owners.qobuzTokenHealth.collectAsState()
                val instanceHealth by owners.qobuzInstanceHealth.collectAsState()
                QobuzSettings(
                    controller = owners.controller,
                    tokenHealth = tokenHealth,
                    instanceHealth = instanceHealth,
                    checkTokens = owners::checkQobuzTokens,
                    checkInstances = owners::checkQobuzInstances,
                    forgetTokenHealth = owners::forgetQobuzTokenHealth,
                    forgetInstanceHealth = owners::forgetQobuzInstanceHealth,
                    copyToClipboard = ::copyToIosClipboard,
                    navigate = navigate,
                    onNotice = onNotice,
                    onFailure = onFailure,
                    scrollTo = scrollTo,
                )
            }
            QobuzLoginRoute -> QobuzLoginScreen(
                controller = owners.controller,
                scrapeCandidates = owners::qobuzSecretCandidates,
                verify = owners::verifyQobuzToken,
                openWebPlayer = { openIosUri("https://play.qobuz.com/", host::report) },
                navigateUp = { navigation.back() },
                onFailure = onFailure,
            )
            "settings/telegram" -> {
                val session by host.telegram.collectAsState()
                val telegram = session
                if (telegram == null) IosUnboundSurface(Modifier.fillMaxSize())
                else IosTelegramSettingsPage(telegram, owners.controller, navigate, onFailure, scrollTo)
            }
            "settings/privacy" -> IosPrivacySettingsPage(host, owners.controller, onFailure, scrollTo)
            "settings/update" -> UpdateSettings(
                controller = owners.controller,
                isNightlyBuild = false,
                updaterAvailable = false,
                installMode = UpdateInstallMode.UNAVAILABLE,
                // Never reached: the page gates checks on updaterAvailable.
                checkFeed = { _, _ -> UpdateFeedResult.Unavailable },
                onChannelChanged = { },
                onNotificationChanged = { },
                installOffer = { },
                loadCommits = { channel ->
                    owners.updateChecker.commitHistory(IOS_UPDATE_COMMIT_COUNT)
                        .getOrThrow().map {
                            UpdateCommit(it.sha, it.message, it.author, it.date.take(10), it.authorAvatarUrl, it.htmlUrl)
                        }
                },
                commitAvatar = { _, _ -> },
                onOpenCommit = { url -> openIosUri(url, host::report) },
                navigate = navigate,
                onFailure = onFailure,
                scrollTo = scrollTo,
            )
            "settings/integration" -> {
                val spotifyAccount by owners.spotifyCatalog.state.collectAsState()
                val spotifyErrorKind by owners.spotifyCatalog.error.collectAsState()
                val spotifyError = spotifyErrorKind?.let { kind ->
                    stringResource(
                        when (kind) {
                            IosSpotifyCatalogError.SIGN_IN_FAILED -> Res.string.ios_lane_spotify_sign_in_failed
                            IosSpotifyCatalogError.BROWSER_UNAVAILABLE -> Res.string.ios_lane_spotify_browser_unavailable
                        }
                    )
                }
                LaunchedEffect(Unit) { owners.spotifyCatalog.restore() }
                IntegrationSettings(
                    controller = owners.controller,
                    poolConfigured = owners.poolConfigured,
                    // The catalog account signs in here; the separate streaming sign-in lives on its own page.
                    spotifyAccount = spotifyAccount,
                    spotifyError = spotifyError,
                    navigate = navigate,
                    onConnectSpotify = owners.spotifyCatalog::connect,
                    onReloadSpotifyPlaylists = owners.spotifyCatalog::reload,
                    onLogoutSpotify = owners.spotifyCatalog::logOut,
                    onDismissSpotifyError = owners.spotifyCatalog::dismissError,
                    onPoolAccountsChanged = { enabled -> owners.onPoolAccountsChanged(enabled) },
                    onOpenCrossServiceImport = { showCrossServiceImport = true },
                    onFailure = onFailure,
                    scrollTo = scrollTo,
                )
            }
            "settings/integrations/device_connect" -> IosDeviceConnectSettingsPage(owners.graph)
            IosListenTogetherSettingsRoute -> IosListenTogetherSettingsPage(
                owner = owners.graph.listenTogether,
                controller = owners.controller,
                onNotice = onNotice,
                onFailure = onFailure,
                unavailable = { show(featureUnavailable) },
                scrollTo = scrollTo,
                host = host,
            )
            "settings/misc" -> IosDeveloperSettingsPage(owners.graph, owners.controller, navigate, onFailure, scrollTo)
            "settings/language_packs" -> IosLanguagePackSettingsPage(host, owners.controller, onFailure)
            "settings/lyrics/providers" -> LyricsProviderSettings(
                controller = owners.controller,
                readStats = { preferences ->
                    PaxsenixDiagnostics(owners.graph.http, paxsenixSettingsOf(preferences)).getStats().getOrThrow()
                },
                checkEndpoints = { preferences ->
                    PaxsenixDiagnostics(owners.graph.http, paxsenixSettingsOf(preferences)).checkProviderPaths()
                },
                onFailure = onFailure,
                scrollTo = scrollTo,
                lyprioBuiltInServer = IosProviderCredentials.lyprioEndpoint != null,
            )
            "settings/lyrics/romanisation" -> {
                // Until the host has a pack manager there is no state flow; the page then treats the pack as not installed.
                val pack by host.japaneseLanguagePack.collectAsState()
                LyricsRomanisationSettings(
                    controller = owners.controller,
                    japanesePack = pack?.state,
                    onFailure = onFailure,
                    scrollTo = scrollTo,
                )
            }
            "settings/ai_integration" -> AiIntegrationSettings(
                controller = owners.controller,
                actions = owners.aiActions,
                onFailure = onFailure,
                scrollTo = scrollTo,
                onOpenApiKeyPortal = { uri -> openIosUri(uri, host::report) },
            )
            "settings/about" -> IosAboutPage(owners.graph, owners.controller, navigation)
            in IOS_LANE_SETTINGS_ROUTES -> IosLaneSettingsPage(
                path = route.value.substringBefore('?'),
                host = host,
                owners = owners,
                channel = route.arguments["channel"],
                navigation = navigation,
                navigate = navigate,
                onNotice = onNotice,
                onFailure = onFailure,
                unavailable = { show(featureUnavailable) },
                scrollTo = scrollTo,
            )
            in IOS_GENERAL_SETTINGS_ROUTES -> IosGeneralSettingsPage(
                host = host,
                path = route.value.substringBefore('?'),
                graph = owners.graph,
                controller = owners.controller,
                navigate = navigate,
                onFailure = onFailure,
                scrollTo = scrollTo,
            )
            "settings/logcat" -> {
                val store = host.diagnostics
                if (store == null) {
                    IosUnboundSurface(Modifier.fillMaxSize())
                } else {
                    DiagnosticLogScreen(
                        store = store,
                        settings = owners.controller.store,
                        formatTime = ::iosDiagnosticTime,
                        actions = DiagnosticLogActions(
                            back = { navigation.back() },
                            copyToClipboard = ::copyToIosClipboard,
                            share = { text -> iosShareText(host, text) },
                            // A real document, so Save to Files and AirDrop carry the report rather than a copied string.
                            export = {
                                iosExportTextFile(
                                    host,
                                    "natune-log-${Clock.System.now().toEpochMilliseconds()}.txt",
                                    iosDiagnosticReport(host),
                                )
                            },
                        ),
                    )
                }
            }
        }
        if (showCrossServiceImport) {
            IosCrossServiceImportDialog(
                graph = owners.graph,
                host = host,
                onOpenRoute = { target ->
                    showCrossServiceImport = false
                    navigation.open(target)
                },
                onDismiss = { showCrossServiceImport = false },
            )
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

/**
 * Settings > Telegram over the host's one TDLib session. Signed in means a verified account, so the row never names an
 * account that Telegram has not confirmed. The account facts and the sign-in page are not connected on iOS yet.
 */
@Composable
private fun IosTelegramSettingsPage(
    telegram: IosTelegramClient,
    controller: SettingsController,
    navigate: (String) -> Unit,
    onFailure: () -> Unit,
    scrollTo: String?,
) {
    val admission by telegram.admission.collectAsState()
    TelegramSettings(
        controller = controller,
        ready = admission is IosTelegramNativeAdmission.Admitted,
        ensureStarted = { telegram.start() },
        loadAccount = { null },
        logOut = telegram::logOut,
        navigate = navigate,
        onFailure = onFailure,
        scrollTo = scrollTo,
    )
}

/**
 * Settings > Privacy on this host: the inline privacy rows and the listener-presence opt-in. The history controls of
 * the Android page need a history editor this host does not have yet.
 */
@Composable
private fun IosPrivacySettingsPage(host: IosHost, controller: SettingsController, onFailure: () -> Unit, scrollTo: String?) {
    val snapshot by controller.store.snapshots.collectAsState()
    val preferences = snapshot
    if (preferences == null) {
        CircularProgressIndicator()
        return
    }
    val presence = host.listenerPresence
    SettingsPage(
        rows = privacyInlineSettingsRows(),
        preferences = preferences,
        controller = controller,
        scrollTo = scrollTo,
        onFailure = onFailure,
        extraItems = {
            if (presence != null) {
                item(key = "listener_presence") {
                    IosListenerPresenceSection(presence, host::report, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
            }
        },
    )
}

/** The listener-presence switch over the iOS opt-in store, with the identifier reset Settings > Privacy offers. */
@Composable
private fun IosListenerPresenceSection(store: ListenerPresenceStore, report: (String) -> Unit, modifier: Modifier) {
    val state by store.state.collectAsState(initial = ListenerPresenceState.Off)
    val scope = rememberCoroutineScope()
    fun change(action: suspend () -> Unit) {
        scope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                report("listener presence change was not saved: ${failure::class.simpleName}")
            }
        }
    }
    ListenerPresenceSection(
        enabled = state.active,
        onEnabledChange = { on -> change { if (on) store.enable() else store.disable() } },
        modifier = modifier,
        onResetId = { change { store.resetId() } },
    )
}

/**
 * Settings > Misc on this host. The acoustic audit mounts the shared panel over the queue's current song,
 * through the session's audit owner. The Discord card reads the presence owner's live debug state and nerd
 * stats poll the player snapshot; this host keeps no stored audio file to parse, so the encoded-format
 * fields report themselves unavailable rather than hiding the panels.
 */
@Composable
private fun IosDeveloperSettingsPage(
    graph: IosSessionGraph,
    controller: SettingsController,
    navigate: (String) -> Unit,
    onFailure: () -> Unit,
    scrollTo: String?,
) {
    DeveloperSettings(
        controller = controller,
        navigate = navigate,
        playerAvailable = true,
        acousticQualitySection = { active -> IosAcousticQualitySection(graph, active) },
        discordDebugSection = { HostDiscordDebugSection(graph.discord.debug, ::iosDiagnosticTime) },
        nerdStatsSection = {
            HostNerdStatsSection(
                player = graph.player,
                queue = graph.queue.state,
                formatFor = { null },
            )
        },
        resumed = true,
        onFailure = onFailure,
        scrollTo = scrollTo,
        androidDebugSections = false,
    )
}

/**
 * The developer-page acoustic section: the shared audit panel of the queue's current song, or the
 * no-song note when nothing is loaded. Leaving or hiding the section cancels a run in flight.
 */
@Composable
private fun IosAcousticQualitySection(graph: IosSessionGraph, active: Boolean) {
    val queueState by graph.queue.state.collectAsState()
    val current = queueState.currentOccurrenceId
        ?.let { id -> queueState.entries.firstOrNull { it.occurrenceId == id }?.item }
    DisposableEffect(graph.acoustic, active) {
        graph.acoustic.setActive(active)
        onDispose { graph.acoustic.setActive(false) }
    }
    if (current == null) {
        Text(
            stringResource(Res.string.m6aq_code_no_current_source),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        return
    }
    // The snapshot collection keeps the audit key recomputing as the loaded request moves, so a
    // source fallback inside the same occurrence retires the older attempt's binding.
    val requestKey by produceState("") {
        graph.player.snapshot.collect { value = graph.acousticRequestKey() }
    }
    AcousticQualityPanel(
        target = AcousticAuditTarget(
            current.ref.id,
            current.title,
            current.artists.joinToString { it.name },
            requestKey = requestKey,
        ),
        owner = graph.acoustic,
        active = active,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}


/** Settings > Language packs over the host's Japanese pack manager; the SpatialFlow add-on is not offered on iOS. */
@Composable
private fun IosLanguagePackSettingsPage(host: IosHost, controller: SettingsController, onFailure: () -> Unit) {
    val pack by host.japaneseLanguagePack.collectAsState()
    val manager = pack
    if (manager == null) {
        IosUnboundSurface(Modifier.fillMaxSize())
        return
    }
    val packState by manager.state.collectAsState()
    val scope = rememberCoroutineScope()
    fun hostAction(block: suspend JapaneseLanguagePackManager.() -> Unit) {
        scope.launch {
            try {
                manager.block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                host.report("language pack change failed: ${failure::class.simpleName}")
                onFailure()
            }
        }
    }
    LanguagePackSettings(
        controller = controller,
        japanese = japanesePackUiState(packState),
        spatialFlowFont = AddonDownloadUiState.Unavailable,
        formatSize = ::iosByteCountSize,
        onInstallJapanese = { hostAction { install() } },
        onCancelJapanese = { hostAction { cancel() } },
        onRemoveJapanese = { hostAction { remove() } },
        onInstallSpatialFlowFont = { },
        onRemoveSpatialFlowFont = { },
        onFailure = onFailure,
    )
}

/** The platform's locale-aware byte count, so the pack sizes read the way the rest of the system shows them. */
private fun iosByteCountSize(bytes: Long): String = NSByteCountFormatter.stringFromByteCount(bytes, NSByteCountFormatterCountStyleFile)
