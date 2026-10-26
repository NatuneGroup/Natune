package moe.rukamori.archivetune.shared.ui.ios

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.isSuccess
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
import moe.rukamori.archivetune.shared.data.platform.ios.IosTelegramNativeAdmission
import moe.rukamori.archivetune.shared.data.platform.ios.iosDiagnosticTime
import moe.rukamori.archivetune.shared.data.provider.QobuzAccountVerifier
import moe.rukamori.archivetune.shared.data.provider.QobuzBundleScrape
import moe.rukamori.archivetune.shared.data.provider.QobuzInstanceProbe
import moe.rukamori.archivetune.shared.data.provider.TidalRefreshResult
import moe.rukamori.archivetune.shared.data.provider.verifyDeezerArl
import moe.rukamori.archivetune.shared.domain.presence.ListenerPresenceState
import moe.rukamori.archivetune.shared.domain.presence.ListenerPresenceStore
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.QobuzTokenEntry
import moe.rukamori.archivetune.shared.domain.settings.SourceHealth
import moe.rukamori.archivetune.shared.domain.settings.SourceHealthStatus
import moe.rukamori.archivetune.shared.domain.settings.TidalAuthFlowDevice
import moe.rukamori.archivetune.shared.domain.settings.TidalInstanceHealth
import moe.rukamori.archivetune.shared.domain.settings.TidalInstanceStatus
import moe.rukamori.archivetune.shared.domain.settings.TidalSessionFacts
import moe.rukamori.archivetune.shared.domain.settings.tidalInstanceList
import moe.rukamori.archivetune.shared.ui.IosHost
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveRoute
import moe.rukamori.archivetune.shared.ui.navigation.LocalShellContentPadding
import moe.rukamori.archivetune.shared.ui.navigation.ShellNavigation
import moe.rukamori.archivetune.shared.ui.presence.ListenerPresenceSection
import moe.rukamori.archivetune.shared.ui.resources.Res
import moe.rukamori.archivetune.shared.ui.resources.ios_logs_copied
import moe.rukamori.archivetune.shared.ui.resources.ios_settings_change_failed
import moe.rukamori.archivetune.shared.ui.resources.ios_settings_feature_unavailable
import moe.rukamori.archivetune.shared.ui.screens.diagnostics.DiagnosticLogActions
import moe.rukamori.archivetune.shared.ui.screens.diagnostics.DiagnosticLogScreen
import moe.rukamori.archivetune.shared.ui.settings.DeezerArlAccount
import moe.rukamori.archivetune.shared.ui.settings.DeezerSettings
import moe.rukamori.archivetune.shared.ui.settings.QobuzLoginRoute
import moe.rukamori.archivetune.shared.ui.settings.QobuzLoginScreen
import moe.rukamori.archivetune.shared.ui.settings.QobuzSettings
import moe.rukamori.archivetune.shared.ui.settings.SettingsController
import moe.rukamori.archivetune.shared.ui.settings.SettingsHomePage
import moe.rukamori.archivetune.shared.ui.settings.SettingsNotice
import moe.rukamori.archivetune.shared.ui.settings.SettingsPage
import moe.rukamori.archivetune.shared.ui.settings.TelegramSettings
import moe.rukamori.archivetune.shared.ui.settings.TidalSettings
import moe.rukamori.archivetune.shared.ui.settings.privacyInlineSettingsRows
import moe.rukamori.archivetune.shared.ui.settings.settingsNoticeText
import org.jetbrains.compose.resources.stringResource

/**
 * The settings owners of the iOS host: one controller over the graph's settings, and the graph's sign-in lifecycle and
 * TIDAL account endpoints. This build carries no TIDAL device client, so the device sign-in row reports itself
 * unavailable, and a pasted refresh token is verified with TIDAL instead.
 */
internal class IosSettingsOwners(private val graph: IosSessionGraph) {
    val controller = SettingsController(
        graph.settings,
        graph.settings.snapshots.value ?: PreferenceSnapshot.EMPTY,
        // The iOS engine has no bit-perfect output path, so its restart reasons are never offered.
        bitPerfectSupported = false,
    )
    val accounts = graph.accountLifecycle
    val tidalInstanceHealth = MutableStateFlow<Map<String, TidalInstanceHealth>>(emptyMap())

    /** A pasted refresh token counts only once TIDAL exchanged it and its session names the account. */
    suspend fun verifyTidalRefreshToken(refreshToken: String): TidalSessionFacts? =
        when (val result = graph.tidalAuth.refresh(refreshToken, TidalAuthFlowDevice)) {
            is TidalRefreshResult.Refreshed ->
                graph.tidalAuth.verifiedSession(result.grant, TidalAuthFlowDevice, refreshFallback = refreshToken)
            else -> null
        }

    suspend fun verifyDeezer(arl: String): DeezerArlAccount? =
        verifyDeezerArl(graph.http, arl)?.let { DeezerArlAccount(it.name, it.lossless, it.userId) }

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

/** The settings routes this host mounts. Every other settings route keeps the unbound boundary. */
internal fun iosMountsSettings(route: ArchiveRoute): Boolean = route.value.substringBefore('?') in IOS_SETTINGS_ROUTES

private val IOS_SETTINGS_ROUTES = setOf(
    "settings", "settings/tidal", "settings/deezer", "settings/telegram", "settings/privacy", "settings/logcat",
    "settings/qobuz", QobuzLoginRoute,
)

/** One mounted settings page. Notices and host messages show in this page's snackbar; a failed save is logged and announced. */
@Composable
internal fun IosSettingsScreen(host: IosHost, owners: IosSettingsOwners, route: ArchiveRoute, navigation: ShellNavigation) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val scrollTo = route.arguments["scrollTo"]
    val navigate: (String) -> Unit = { target -> navigation.open(target) }
    val changeFailed = stringResource(Res.string.ios_settings_change_failed)
    val featureUnavailable = stringResource(Res.string.ios_settings_feature_unavailable)
    val logsCopied = stringResource(Res.string.ios_logs_copied)
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
                    deviceLink = null,
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
            "settings/deezer" -> DeezerSettings(
                controller = owners.controller,
                accounts = owners.accounts,
                // The Playback Pool is not connected on this host.
                poolConfigured = false,
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
                            // No share sheet is connected yet, so Share copies the visible lines and says so.
                            share = { text ->
                                copyToIosClipboard(text)
                                show(logsCopied)
                            },
                            // A file export needs the document picker, which is not connected. The page is told nothing
                            // was written, and the user is told why.
                            export = {
                                show(featureUnavailable)
                                null
                            },
                        ),
                    )
                }
            }
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
