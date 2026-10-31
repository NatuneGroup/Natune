package moe.rukamori.archivetune.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.isSuccess
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.desktop.integration.DesktopStrings
import moe.rukamori.archivetune.desktop.telegram.TelegramNativeAdmission
import moe.rukamori.archivetune.shared.data.lyrics.PaxsenixDiagnostics
import moe.rukamori.archivetune.shared.data.provider.QobuzAccountVerifier
import moe.rukamori.archivetune.shared.data.provider.QobuzBundleScrape
import moe.rukamori.archivetune.shared.data.provider.QobuzInstanceProbe
import moe.rukamori.archivetune.shared.data.provider.TidalAccountDeviceLink
import moe.rukamori.archivetune.shared.data.provider.TidalRefreshResult
import moe.rukamori.archivetune.shared.data.provider.verifyDeezerArl
import moe.rukamori.archivetune.shared.domain.settings.DataSyncIdKey
import moe.rukamori.archivetune.shared.domain.settings.InnerTubeCookieKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.QobuzTokenEntry
import moe.rukamori.archivetune.shared.domain.settings.SourceHealth
import moe.rukamori.archivetune.shared.domain.settings.SourceHealthStatus
import moe.rukamori.archivetune.shared.domain.settings.TidalAuthFlowDevice
import moe.rukamori.archivetune.shared.domain.settings.TidalDeviceLink
import moe.rukamori.archivetune.shared.domain.settings.TidalInstanceHealth
import moe.rukamori.archivetune.shared.domain.settings.TidalInstanceStatus
import moe.rukamori.archivetune.shared.domain.settings.TidalSessionFacts
import moe.rukamori.archivetune.shared.domain.settings.tidalInstanceList
import moe.rukamori.archivetune.shared.domain.settings.youTubeLoginBlock
import moe.rukamori.archivetune.shared.ui.host.paxsenixSettingsOf
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveRoute
import moe.rukamori.archivetune.shared.ui.navigation.LocalShellContentPadding
import moe.rukamori.archivetune.shared.ui.navigation.ShellNavigation
import moe.rukamori.archivetune.shared.ui.resources.Res
import moe.rukamori.archivetune.shared.ui.resources.m6_additional_downloads_spatialflow_font_choose
import moe.rukamori.archivetune.shared.domain.settings.UpdateChannel
import moe.rukamori.archivetune.shared.ui.settings.AccountSettings
import moe.rukamori.archivetune.shared.ui.settings.ChangelogSettings
import moe.rukamori.archivetune.shared.ui.settings.ChangelogState
import moe.rukamori.archivetune.shared.ui.settings.DeezerArlAccount
import moe.rukamori.archivetune.shared.ui.settings.DeezerSettings
import moe.rukamori.archivetune.shared.ui.settings.IntegrationSettings
import moe.rukamori.archivetune.shared.ui.settings.LyricsProviderSettings
import moe.rukamori.archivetune.shared.ui.settings.ProviderAccountLifecycle
import moe.rukamori.archivetune.shared.ui.settings.QqMusicSettings
import moe.rukamori.archivetune.shared.ui.settings.SettingsController
import moe.rukamori.archivetune.shared.ui.settings.SettingsHomePage
import moe.rukamori.archivetune.shared.ui.settings.DeezerLoginRoute
import moe.rukamori.archivetune.shared.ui.settings.QobuzLoginRoute
import moe.rukamori.archivetune.shared.ui.settings.QobuzLoginScreen
import moe.rukamori.archivetune.shared.ui.settings.QobuzSettings
import moe.rukamori.archivetune.shared.ui.settings.SettingsNotice
import moe.rukamori.archivetune.shared.ui.settings.SettingsPage
import moe.rukamori.archivetune.shared.ui.settings.SpotifyStreamingSettings
import moe.rukamori.archivetune.shared.ui.settings.TelegramLoginRoute
import moe.rukamori.archivetune.shared.ui.settings.TelegramSettings
import moe.rukamori.archivetune.shared.ui.settings.TidalLoginRoute
import moe.rukamori.archivetune.shared.ui.settings.TidalSettings
import moe.rukamori.archivetune.shared.ui.settings.UpdateSettings
import moe.rukamori.archivetune.shared.ui.settings.privacyInlineSettingsRows
import moe.rukamori.archivetune.shared.ui.settings.settingsNoticeText
import androidx.compose.runtime.mutableStateOf
import moe.rukamori.archivetune.shared.domain.settings.CanvasConnectivity
import moe.rukamori.archivetune.shared.domain.settings.EnableTranslatorKey
import moe.rukamori.archivetune.shared.domain.lyrics.romanization.JapaneseLanguagePackState
import moe.rukamori.archivetune.shared.domain.settings.amazonInstanceList
import moe.rukamori.archivetune.shared.domain.settings.spotifyStreamingSignedIn
import moe.rukamori.archivetune.shared.ui.settings.AiIntegrationSettings
import moe.rukamori.archivetune.shared.ui.settings.AmazonSettings
import moe.rukamori.archivetune.shared.ui.settings.AppleMusicSettings
import moe.rukamori.archivetune.shared.ui.settings.CanvasSettings
import moe.rukamori.archivetune.shared.ui.settings.ContentSettings
import moe.rukamori.archivetune.shared.ui.settings.DiscordSettings
import moe.rukamori.archivetune.shared.ui.settings.DownloadsSettings
import moe.rukamori.archivetune.shared.ui.settings.JioSaavnSettings
import moe.rukamori.archivetune.shared.ui.settings.InternetSettings
import moe.rukamori.archivetune.shared.ui.settings.LanguagePackSettings
import moe.rukamori.archivetune.shared.ui.settings.PoTokenSettings
import moe.rukamori.archivetune.shared.ui.settings.SettingsCacheKind
import moe.rukamori.archivetune.shared.ui.settings.SourceSettings
import moe.rukamori.archivetune.shared.ui.settings.StorageSettings
import moe.rukamori.archivetune.shared.ui.settings.YtDlpRuntimeSettings
import moe.rukamori.archivetune.shared.ui.settings.romanizationSettingsRows
import moe.rukamori.archivetune.desktop.integration.DesktopLaunchIntake

/**
 * The settings owners of the desktop host: one controller over the graph's settings, and the graph's sign-in lifecycle
 * and TIDAL account endpoints. The TIDAL device and PKCE clients come from [DesktopCredentials]; without a device client
 * the device sign-in row reports itself unavailable.
 */
internal class DesktopSettingsOwners(private val graph: DesktopSessionGraph) {
    val controller = SettingsController(
        graph.settings,
        graph.settings.snapshots.value ?: PreferenceSnapshot.EMPTY,
        // The desktop engine has no bit-perfect output path, so its restart reasons are never offered.
        bitPerfectSupported = false,
    )
    val accounts: ProviderAccountLifecycle = graph.accountLifecycle
    private val tidalAuth = graph.tidalAuth
    val tidalDeviceLink: TidalDeviceLink? = if (tidalAuth.deviceSignInAvailable) TidalAccountDeviceLink(tidalAuth) else null
    val tidalInstanceHealth = MutableStateFlow<Map<String, TidalInstanceHealth>>(emptyMap())
    val youTube = DesktopYouTubeAccountOwner(graph, controller)
    val qqMusic = DesktopQqMusicLoginOwner(graph.scope, graph.accountLifecycle, graph::report)
    val spotify = DesktopSpotifyAccounts(graph, controller)
    val deezerWeb = DesktopDeezerWebSignIn(graph)
    val tidalWeb = DesktopTidalWebSignIn(graph)

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
                    val started = System.nanoTime()
                    val status = try {
                        qobuzVerifier.verify(entry)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        SourceHealthStatus.UNREACHABLE
                    }
                    entry.id to SourceHealth(status, latencyMs = (System.nanoTime() - started) / 1_000_000L)
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
                    val started = System.nanoTime()
                    val status = try {
                        qobuzProbe.verify(base, probeTrack.ifBlank { null }, formatId)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        SourceHealthStatus.UNREACHABLE
                    }
                    base to SourceHealth(status, latencyMs = (System.nanoTime() - started) / 1_000_000L)
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

    val services = DesktopSettingsServices(graph, controller, DesktopSettingsServices.currentInstanceDirectory())
    // The Turnstile challenge window exchanges its token against the primary instance and commits the JWT here.
    val amazon = DesktopAmazonAuthorize(graph, controller)
    val discord = DesktopDiscordPresence(graph, DesktopLaunchIntake.links, controller, graph::report)

    suspend fun qobuzSecretCandidates(): List<String> = qobuzScrape.candidates().orEmpty()

    suspend fun verifyQobuzToken(entry: QobuzTokenEntry): SourceHealthStatus =
        try {
            qobuzVerifier.verify(entry)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            SourceHealthStatus.UNREACHABLE
        }

    // Stages under the locked instance directory, like every other owner.
    val update = DesktopUpdateOwner(graph, graph.instanceDirectory)

    /** A pasted refresh token counts only once TIDAL exchanged it and its session names the account. */
    suspend fun verifyTidalRefreshToken(refreshToken: String): TidalSessionFacts? =
        when (val result = tidalAuth.refresh(refreshToken, TidalAuthFlowDevice)) {
            is TidalRefreshResult.Refreshed ->
                tidalAuth.verifiedSession(result.grant, TidalAuthFlowDevice, refreshFallback = refreshToken)
            else -> null
        }

    suspend fun verifyDeezer(arl: String): DeezerArlAccount? =
        verifyDeezerArl(graph.http, arl)?.let { DeezerArlAccount(it.name, it.lossless, it.userId) }

    /**
     * Probes every configured instance at once with one bounded search. A search proves reachability only, so a
     * preview-only instance is not told apart here; the resolver still skips the previews it receives.
     */
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
        val started = System.nanoTime()
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
        return TidalInstanceHealth(TidalInstanceStatus.HEALTHY, latencyMs = (System.nanoTime() - started) / 1_000_000L)
    }

    private companion object {
        const val PROBE_TIMEOUT_MS = 8_000L
        const val PROBE_QUERY = "love"
    }
}

/** The settings routes this host mounts. Every other settings route keeps the unbound boundary. */
internal fun desktopMountsSettings(route: ArchiveRoute): Boolean =
    route.value.substringBefore('?').let {
        it in DESKTOP_SETTINGS_ROUTES || it in DESKTOP_GENERAL_SETTINGS_ROUTES || it in DESKTOP_EXTRA_SETTINGS_ROUTES
    }

private val DESKTOP_SETTINGS_ROUTES = setOf(
    "settings", "settings/account", "login", "settings/integration", "settings/tidal", "settings/deezer", "settings/qqmusic",
    "settings/qobuz", QobuzLoginRoute, DeezerLoginRoute, TidalLoginRoute,
    "settings/spotify_streaming", "settings/telegram", TelegramLoginRoute, "settings/lastfm", DesktopLastFmDashboardRoute, "settings/privacy", "settings/about",
    DesktopListenTogetherSettingsRoute, "settings/misc", "settings/logcat", "settings/backup_restore",
    "settings/lyrics/providers",
    "settings/ai_integration", "settings/amazon", "settings/applemusic", "settings/canvas", "settings/changelog",
    "settings/content", "settings/discord", "settings/downloads", "settings/internet", "settings/jiosaavn",
    "settings/language_packs", "settings/lyrics/romanisation", "settings/player/ytdlp", "settings/po_token",
    "settings/sources", "settings/storage", "settings/update",
)

/**
 * One mounted settings page. Notices and host messages show in this page's snackbar; a failed save is logged and
 * announced.
 */
@Composable
internal fun DesktopSettingsScreen(
    owners: DesktopSettingsOwners,
    graph: DesktopSessionGraph,
    route: ArchiveRoute,
    navigation: ShellNavigation,
) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val scrollTo = route.arguments["scrollTo"]
    val navigate: (String) -> Unit = { target -> navigation.open(target) }
    fun show(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }
    val onNotice: (SettingsNotice) -> Unit = { notice -> scope.launch { snackbar.showSnackbar(settingsNoticeText(notice)) } }
    val onFailure: () -> Unit = {
        graph.report("a settings change could not be completed")
        show(DesktopStrings["settings_change_failed"])
    }
    val unavailable: () -> Unit = { show(DesktopStrings["settings_feature_unavailable"]) }
    Box(Modifier.fillMaxSize().padding(LocalShellContentPadding.current)) {
        when (val path = route.value.substringBefore('?')) {
            in DESKTOP_GENERAL_SETTINGS_ROUTES -> DesktopGeneralSettingsPage(
                path = path,
                controller = owners.controller,
                navigate = navigate,
                onFailure = onFailure,
                scrollTo = scrollTo,
                playlistTags = graph.playlistTags,
            )
            in DESKTOP_EXTRA_SETTINGS_ROUTES ->
                DesktopExtraSettingsPage(path, graph, owners.controller, navigation, ::show, onFailure)
            "settings" -> SettingsHomePage(
                controller = owners.controller,
                navigate = navigate,
                onFailure = onFailure,
                hideToTrayKey = DesktopHideToTrayKey,
                scrollTo = scrollTo,
            )
            "settings/account" -> {
                val snapshot by graph.settings.snapshots.collectAsState()
                val channels by owners.youTube.channels.collectAsState()
                val sessionKey = snapshot?.let { "${it[InnerTubeCookieKey, ""].hashCode()}|${it[DataSyncIdKey, ""]}" }
                LaunchedEffect(sessionKey) { owners.youTube.refreshChannels() }
                AccountSettings(
                    controller = owners.controller,
                    channels = channels,
                    verifyAccount = owners.youTube::verify,
                    logOut = owners.youTube::logOut,
                    revokeCodeSession = owners.youTube::revokeCodeSession,
                    onAuthCommitted = { scope.launch { owners.youTube.refreshChannels() } },
                    onForceSyncAfterSwitch = {
                        graph.scope.launch { graph.youtubeLibrarySync.performFullSync(authoritative = true) }
                    },
                    navigate = navigate,
                    onNotice = onNotice,
                    onFailure = onFailure,
                    scrollTo = scrollTo,
                    avatar = { url, _, modifier -> DesktopRemoteArtwork(url, graph.artworkLoader, modifier) },
                    revealCredentialBlock = { graph.settings.snapshots.value?.youTubeLoginBlock() },
                    copyToClipboard = ::copyToDesktopClipboard,
                )
            }
            "login" -> DesktopYouTubeLoginPage(owners.youTube, navigation)
            "settings/integration" -> {
                val spotifyAccount by owners.spotify.catalog.collectAsState()
                val spotifyError by owners.spotify.catalogError.collectAsState()
                var showImport by remember { mutableStateOf(false) }
                val importer = remember(graph, owners.spotify) { DesktopCrossServiceImport(graph, owners.spotify) }
                LaunchedEffect(Unit) { owners.spotify.restore() }
                IntegrationSettings(
                    controller = owners.controller,
                    poolConfigured = graph.playbackPool.configured,
                    spotifyAccount = spotifyAccount,
                    spotifyError = spotifyError,
                    navigate = navigate,
                    onConnectSpotify = {
                        owners.spotify.connectCatalog()
                        show(DesktopStrings["spotify_login_window_hint"])
                    },
                    onReloadSpotifyPlaylists = owners.spotify::reloadPlaylists,
                    onLogoutSpotify = owners.spotify::logOutCatalog,
                    onDismissSpotifyError = owners.spotify::dismissCatalogError,
                    onPoolAccountsChanged = graph.playbackPool::onEnabledChanged,
                    onOpenCrossServiceImport = { showImport = true },
                    onFailure = onFailure,
                    scrollTo = scrollTo,
                )
                if (showImport) {
                    DesktopCrossServiceImportDialog(
                        importer = importer,
                        openPlaylist = { id -> navigate("local_playlist/$id") },
                        onDismiss = { showImport = false },
                    )
                }
            }
            "settings/spotify_streaming" -> {
                val signingIn by owners.spotify.streamingSignInInProgress.collectAsState()
                val refusal by owners.spotify.streamingRefusal.collectAsState()
                SpotifyStreamingSettings(
                    controller = owners.controller,
                    signInInProgress = signingIn,
                    signInRefusal = refusal,
                    onSignIn = { owners.spotify.signInStreaming(::show) },
                    onDismissSignInRefusal = owners.spotify::dismissStreamingRefusal,
                    onSignOut = owners.spotify::signOutStreaming,
                    onFailure = onFailure,
                    scrollTo = scrollTo,
                    onCancelSignIn = owners.spotify::cancelStreamingSignIn,
                    hostNote = DesktopStrings["spotify_streaming_desktop_note"],
                )
            }
            "settings/tidal" -> {
                val health by owners.tidalInstanceHealth.collectAsState()
                TidalSettings(
                    controller = owners.controller,
                    accounts = owners.accounts,
                    deviceLink = owners.tidalDeviceLink,
                    openExternal = { uri -> openDesktopUri(uri, graph::report) },
                    instanceHealth = health,
                    checkInstances = owners::checkTidalInstances,
                    forgetInstanceHealth = owners::forgetTidalInstanceHealth,
                    verifyRefreshToken = owners::verifyTidalRefreshToken,
                    // TIDAL repositories are built per resolve from the account lease, so no token is held in memory.
                    onSignOut = { },
                    copyToClipboard = ::copyToDesktopClipboard,
                    navigate = navigate,
                    onNotice = onNotice,
                    onFailure = onFailure,
                    scrollTo = scrollTo,
                )
            }
            "settings/deezer" -> DeezerSettings(
                controller = owners.controller,
                accounts = owners.accounts,
                poolConfigured = graph.playbackPool.configured,
                verifyArl = owners::verifyDeezer,
                // The Deezer source keys its session to the lease generation, which a sign-out retires.
                onSignOut = { },
                navigate = navigate,
                onFailure = onFailure,
                onNotice = onNotice,
                scrollTo = scrollTo,
            )
            TidalLoginRoute -> DesktopTidalLoginPage(owners.tidalWeb, navigation)
            DeezerLoginRoute -> DesktopDeezerLoginPage(owners.deezerWeb, navigation)
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
                    copyToClipboard = ::copyToDesktopClipboard,
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
                openWebPlayer = { openDesktopUri("https://play.qobuz.com/", graph::report) },
                navigateUp = { navigation.back() },
                onFailure = onFailure,
            )
            "settings/qqmusic" -> {
                val loginState by owners.qqMusic.loginState.collectAsState()
                QqMusicSettings(
                    controller = owners.controller,
                    accounts = owners.accounts,
                    loginState = loginState,
                    onStartSignIn = owners.qqMusic::start,
                    onCancelSignIn = owners.qqMusic::cancel,
                    // The host holds no QQ session beyond the attempt, so only the attempt stops; the lifecycle signs out.
                    onSignOut = { owners.qqMusic.cancel() },
                    onFailure = onFailure,
                    scrollTo = scrollTo,
                )
            }
            "settings/telegram" -> {
                val admission by graph.telegram.admission.collectAsState()
                TelegramSettings(
                    controller = owners.controller,
                    // Signed in means a verified account, so the row never names an account that GetMe has not confirmed.
                    ready = admission is TelegramNativeAdmission.Admitted,
                    ensureStarted = { graph.telegram.start() },
                    loadAccount = graph.telegram::accountFacts,
                    logOut = graph.telegram::logOut,
                    navigate = navigate,
                    onFailure = onFailure,
                    scrollTo = scrollTo,
                )
            }
            TelegramLoginRoute -> DesktopTelegramLoginPage(graph.telegram, navigation)
            "settings/lastfm" -> DesktopLastFmSettingsPage(
                lastFm = graph.lastFm,
                controller = owners.controller,
                onFailure = onFailure,
                scrollTo = scrollTo,
                report = graph::report,
                openDashboard = { navigation.open(DesktopLastFmDashboardRoute) },
            )
            DesktopLastFmDashboardRoute -> DesktopLastFmDashboardPage(graph, navigation, unavailable)
            "settings/privacy" -> DesktopPrivacySettingsPage(graph, owners.controller, ::show, onFailure, scrollTo)
            "settings/about" -> DesktopAboutPage(graph, owners.controller, navigation)
            DesktopListenTogetherSettingsRoute ->
                DesktopListenTogetherSettingsPage(graph, owners.controller, onNotice, onFailure, unavailable, scrollTo)
            "settings/misc" -> DesktopDeveloperSettingsPage(owners.controller, navigate, onFailure, scrollTo)
            "settings/logcat" -> DesktopDiagnosticLogPage(graph.settings, navigation, ::show)
            "settings/backup_restore" -> DesktopBackupRestorePage(graph, owners.controller, onNotice, onFailure, scrollTo, ::show)
            "settings/lyrics/providers" -> LyricsProviderSettings(
                controller = owners.controller,
                readStats = { preferences -> PaxsenixDiagnostics(graph.http, paxsenixSettingsOf(preferences)).getStats().getOrThrow() },
                checkEndpoints = { preferences -> PaxsenixDiagnostics(graph.http, paxsenixSettingsOf(preferences)).checkProviderPaths() },
                onFailure = onFailure,
                scrollTo = scrollTo,
            )
            "settings/ai_integration" -> AiIntegrationSettings(
                controller = owners.controller,
                actions = owners.services.aiActions,
                onFailure = onFailure,
                scrollTo = scrollTo,
                onOpenApiKeyPortal = { url -> openDesktopUri(url, graph::report) },
            )
            "settings/amazon" -> {
                val snapshot by graph.settings.snapshots.collectAsState()
                AmazonSettings(
                    controller = owners.controller,
                    poolInstanceCount = snapshot?.amazonInstanceList()?.size ?: 0,
                    currentTimeMillis = System::currentTimeMillis,
                    formatExpiry = owners.services::formatExpiry,
                    onAuthorize = {
                        scope.launch {
                            show(DesktopStrings["amazon_challenge_opening"])
                            show(
                                when (val outcome = owners.amazon.authorize()) {
                                    is AmazonAuthorizeOutcome.Authorized -> DesktopStrings["amazon_authorize_done"]
                                    AmazonAuthorizeOutcome.Cancelled -> DesktopStrings["amazon_authorize_cancelled"]
                                    AmazonAuthorizeOutcome.NotConfigured -> DesktopStrings["amazon_authorize_not_configured"]
                                    AmazonAuthorizeOutcome.NoInstance -> DesktopStrings["amazon_authorize_no_instance"]
                                    AmazonAuthorizeOutcome.Busy -> DesktopStrings["amazon_authorize_busy"]
                                    is AmazonAuthorizeOutcome.Failed -> {
                                        graph.report("amazon authorization failed: ${outcome.reason}")
                                        DesktopStrings["amazon_authorize_failed"]
                                    }
                                },
                            )
                        }
                    },
                    onFailure = onFailure,
                    onNotice = onNotice,
                    scrollTo = scrollTo,
                )
            }
            "settings/applemusic" -> AppleMusicSettings(owners.controller, onFailure, scrollTo)
            "settings/canvas" -> {
                val connectivity by produceState<CanvasConnectivity?>(null) {
                    value = owners.services.canvasConnectivity()
                }
                val probes by owners.services.canvasProbes.collectAsState()
                val canvasBytes by produceState<Long?>(null) { value = owners.services.canvasCacheBytes() }
                CanvasSettings(
                    controller = owners.controller,
                    connectivity = connectivity,
                    providerProbes = probes,
                    refreshHealth = owners.services::refreshCanvasHealth,
                    cacheBytes = canvasBytes,
                    applyCacheLimit = owners.services::applyCanvasCacheLimit,
                    clearCache = owners.services::clearCanvasCache,
                    onFailure = onFailure,
                )
            }
            "settings/changelog" -> {
                val channel = UpdateChannel.entries.firstOrNull { it.name == route.arguments["channel"] }
                    ?: owners.update.checker.selectedChannel()
                var retryTick by remember { mutableIntStateOf(0) }
                val state by produceState<ChangelogState>(ChangelogState.Loading, channel, retryTick) {
                    value = ChangelogState.Loading
                    value = owners.update.changelog(channel, forceRefresh = retryTick > 0)
                        .fold(
                            onSuccess = { ChangelogState.Loaded(it) },
                            onFailure = { ChangelogState.Failed },
                        )
                }
                ChangelogSettings(
                    channel = channel,
                    state = state,
                    onRetry = { retryTick += 1 },
                    onOpenUrl = { url -> openDesktopUri(url, graph::report) },
                )
            }
            "settings/content" -> ContentSettings(owners.controller, onFailure, scrollTo)
            "settings/discord" -> {
                LaunchedEffect(Unit) { owners.discord.startPresenceDriver() }
                DiscordSettings(
                    controller = owners.controller,
                    presenceSupported = true,
                    currentTimeMillis = System::currentTimeMillis,
                    authorize = owners.discord::authorize,
                    verifyAccount = owners.discord::verifyAccount,
                    refreshPresence = owners.discord::refreshPresence,
                    signOut = owners.discord::signOut,
                    navigate = navigate,
                    onNotice = onNotice,
                    onFailure = onFailure,
                    scrollTo = scrollTo,
                    avatar = { url, _, modifier -> DesktopRemoteArtwork(url, graph.artworkLoader, modifier) },
                )
            }
            "settings/downloads" -> DownloadsSettings(
                controller = owners.controller,
                poolConfigured = graph.playbackPool.configured,
                clearDownloads = owners.services::clearDownloads,
                navigate = navigate,
                onFailure = onFailure,
                scrollTo = scrollTo,
            )
            "settings/internet" -> InternetSettings(
                controller = owners.controller,
                activeRotationCount = owners.services.activeRotationCount(),
                applyNetworkSettings = owners.services::applyNetworkSettings,
                applyIpRotation = owners.services::applyIpRotation,
                testProxy = owners.services::testProxy,
                onFailure = onFailure,
                scrollTo = scrollTo,
            )
            "settings/jiosaavn" -> JioSaavnSettings(owners.controller, onFailure, scrollTo)
            "settings/language_packs" -> {
                val japaneseState by owners.services.japanesePackManager.state.collectAsState()
                val spatialFlow by owners.services.spatialFlowFont.collectAsState()
                LanguagePackSettings(
                    controller = owners.controller,
                    japanese = owners.services.mapJapaneseState(japaneseState),
                    spatialFlowFont = spatialFlow,
                    formatSize = { bytes -> "%.1f MB".format(bytes / 1_000_000.0) },
                    onInstallJapanese = { scope.launch { owners.services.installJapanese() } },
                    onCancelJapanese = { scope.launch { owners.services.cancelJapanese() } },
                    onRemoveJapanese = { scope.launch { owners.services.removeJapanese() } },
                    onInstallSpatialFlowFont = {
                        if (owners.services.installSpatialFlowFont()) show(DesktopStrings["spatial_flow_font_installed"])
                    },
                    onRemoveSpatialFlowFont = {
                        if (owners.services.removeSpatialFlowFont()) show(DesktopStrings["spatial_flow_font_removed"])
                    },
                    // The desktop has no pinned download: the user supplies the same typeface file Android installs.
                    spatialFlowFontInstallLabel = Res.string.m6_additional_downloads_spatialflow_font_choose,
                    onFailure = onFailure,
                )
            }
            "settings/lyrics/romanisation" -> {
                val snapshot by graph.settings.snapshots.collectAsState()
                val preferences = snapshot
                if (preferences == null) {
                    CircularProgressIndicator()
                } else {
                    val japaneseState by owners.services.japanesePackManager.state.collectAsState()
                    SettingsPage(
                        rows = romanizationSettingsRows(
                            aiHandled = preferences[EnableTranslatorKey, false],
                            japanesePackReady = japaneseState is JapaneseLanguagePackState.Installed,
                            preferences = preferences,
                        ),
                        preferences = preferences,
                        controller = owners.controller,
                        scrollTo = scrollTo,
                        onFailure = onFailure,
                    )
                }
            }
            "settings/player/ytdlp" -> YtDlpRuntimeSettings(readRuntime = owners.services::readYtDlpRuntime)
            "settings/po_token" -> PoTokenSettings(
                controller = owners.controller,
                generateTokens = owners.services::generatePoTokens,
                copyToClipboard = ::copyToDesktopClipboard,
                onFailure = onFailure,
            )
            "settings/sources" -> {
                val snapshot by graph.settings.snapshots.collectAsState()
                val preferences = snapshot
                if (preferences == null) {
                    CircularProgressIndicator()
                } else {
                    SourceSettings(
                        controller = owners.controller,
                        spotifyStreamingReady = preferences.spotifyStreamingSignedIn(),
                        poolConfigured = graph.playbackPool.configured,
                        refreshPool = owners.services::refreshPool,
                        checkSource = { type, prefs -> owners.services.checkSource(type, prefs) },
                        navigate = navigate,
                        onFailure = onFailure,
                        scrollTo = scrollTo,
                    )
                }
            }
            "settings/storage" -> {
                var busy by remember { mutableStateOf(false) }
                val usedBytes by produceState<Map<SettingsCacheKind, Long>>(emptyMap(), busy) {
                    value = owners.services.usedCacheBytes()
                }
                StorageSettings(
                    controller = owners.controller,
                    usedBytes = usedBytes,
                    currentStorageLabel = owners.services.instanceDirectory.toString(),
                    storageBusy = busy,
                    pickAndCommitStorage = {
                        busy = true
                        try { owners.services.pickAndCommitStorage() } finally { busy = false }
                    },
                    clearCache = { kind -> busy = true; try { owners.services.clearCache(kind) } finally { busy = false } },
                    applyCacheLimits = { prefs -> busy = true; try { owners.services.applyCacheLimits(prefs) } finally { busy = false } },
                    onFailure = onFailure,
                    scrollTo = scrollTo,
                )
            }
            "settings/update" -> {
                val update = owners.update
                val downloadState = update.installer.state
                UpdateSettings(
                    controller = owners.controller,
                    isNightlyBuild = update.isNightlyBuild,
                    updaterAvailable = update.updaterAvailable,
                    installMode = update.installMode,
                    checkFeed = { channel, force -> update.checkFeed(channel, force) },
                    onChannelChanged = { channel -> update.checker.markSelectionChanged() },
                    onNotificationChanged = { enabled -> update.onNotificationPreferenceChanged(enabled) },
                    installOffer = update::install,
                    downloadState = downloadState,
                    cancelDownload = { update.installer.cancelDownload() },
                    consumeDownload = { state -> scope.launch { update.consumeDownload(state) } },
                    loadCommits = { channel -> update.loadCommits(channel) },
                    commitAvatar = { url, modifier ->
                        if (url != null) DesktopRemoteArtwork(url, graph.artworkLoader, modifier)
                    },
                    onOpenCommit = { url -> openDesktopUri(url, graph::report) },
                    navigate = navigate,
                    onFailure = onFailure,
                    scrollTo = scrollTo,
                )
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

/** Settings > Privacy on this host: the inline privacy rows, the history controls and the listener-presence opt-in. */
@Composable
private fun DesktopPrivacySettingsPage(
    graph: DesktopSessionGraph,
    controller: SettingsController,
    show: (String) -> Unit,
    onFailure: () -> Unit,
    scrollTo: String?,
) {
    val snapshot by controller.store.snapshots.collectAsState()
    val preferences = snapshot
    if (preferences == null) {
        CircularProgressIndicator()
        return
    }
    SettingsPage(
        rows = privacyInlineSettingsRows(),
        preferences = preferences,
        controller = controller,
        scrollTo = scrollTo,
        onFailure = onFailure,
        extraItems = {
            item(key = "history_controls") {
                DesktopHistoryControls(
                    preferences = preferences,
                    controller = controller,
                    clearListenHistory = graph.history::reset,
                    show = show,
                    onFailure = onFailure,
                )
            }
            item(key = "listener_presence") {
                DesktopListenerPresenceSection(
                    store = graph.listenerPresence.store,
                    report = graph::report,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        },
    )
}

internal fun copyToDesktopClipboard(text: String) {
    try {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    } catch (failure: Exception) {
        desktopDiagnostic("clipboard unavailable: ${failure.javaClass.simpleName}")
    }
}
