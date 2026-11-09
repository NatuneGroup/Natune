package moe.rukamori.archivetune.shared.ui.ios

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import moe.rukamori.archivetune.shared.ui.host.LocalAndroidPhonePresentation
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.ui.ArchiveTuneRoot
import moe.rukamori.archivetune.shared.ui.IosHost
import moe.rukamori.archivetune.shared.ui.IosSessionState
import moe.rukamori.archivetune.shared.ui.SafeAreaPadding
import moe.rukamori.archivetune.shared.ui.safeAreaPadding
import moe.rukamori.archivetune.shared.ui.resources.*
import moe.rukamori.archivetune.shared.ui.resources.ios_host_graph_unavailable
import moe.rukamori.archivetune.shared.ui.resources.ios_settings_unavailable
import moe.rukamori.archivetune.shared.ui.screens.diagnostics.CrashReportActions
import moe.rukamori.archivetune.shared.ui.screens.diagnostics.CrashReportDialog
import moe.rukamori.archivetune.shared.ui.theme.ArchiveTuneTheme
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * The root of the iOS controller. It shows progress while the Keychain settings and the session graph open, the bound
 * shared root once they are ready, and an explicit boundary when either cannot open, never an empty stand-in. Leaving
 * the foreground stops frames and blur; audio keeps playing.
 */
@Composable
internal fun IosMountedRoot(host: IosHost) {
    val session by host.session.collectAsState()
    val active by host.appActive.collectAsState()
    val measured by host.safeArea.padding.collectAsState()
    // UIKit is the only inset source: the ready root reserves the window's safe area inside its own theme, and the
    // boundary states reserve it the same physical way, so no screen sits under the status bar or home indicator.
    IosMountedState(host, session, active, measured)
    (session as? IosSessionState.Ready)?.let { IosDeviceConnectHost(it.graph) }
    IosCrashOverlay(host, measured)
}

@Composable
private fun IosMountedState(host: IosHost, session: IosSessionState, active: Boolean, safeArea: SafeAreaPadding) {
    when (val state = session) {
        IosSessionState.Opening -> IosBoundary(safeArea) {
            Column(horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Image(painterResource(Res.drawable.natune_logo), null, Modifier.size(80.dp))
                Text(stringResource(Res.string.app_name), style = MaterialTheme.typography.headlineMedium)
                Text(stringResource(Res.string.ios_opening_library), style = MaterialTheme.typography.bodyMedium)
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }
        IosSessionState.SettingsUnavailable -> IosBoundary(safeArea) { IosBoundaryText(stringResource(Res.string.ios_settings_unavailable)) }
        IosSessionState.GraphUnavailable -> IosBoundary(safeArea) { IosBoundaryText(stringResource(Res.string.ios_host_graph_unavailable)) }
        is IosSessionState.Ready -> {
            // A blocked restore runs on a throwaway database: the whole shell stays unmounted until the
            // recovery screen resolves it, so no ordinary write can reach the stand-in.
            val recovering by state.graph.backup.recoveryMode.collectAsState()
            if (recovering) {
                IosRestoreRecoveryScreen(state.graph, safeArea)
            } else {
                val uiScope = rememberCoroutineScope()
                val recognition = remember(state.graph) { IosRecognitionMount(state.graph, uiScope) }
                DisposableEffect(state.graph) { onDispose { recognition.close() } }
                val bindings = remember(state.graph) { iosHostBindings(host, state.graph, recognition) }
                CompositionLocalProvider(LocalAndroidPhonePresentation provides true) {
                ArchiveTuneRoot(
                    host = bindings,
                    visible = active,
                    resumed = active,
                    opticalCapabilities = IosOpticalCapabilities,
                    safeArea = safeArea,
                )
                }
            }
        }
    }
}

/**
 * The crash report an earlier launch stored, shown once at the next launch as the Android crash screen. Delete removes
 * it; Close keeps it, so it is offered again. Share Logs opens the iOS share sheet with the report text.
 */
@Composable
private fun IosCrashOverlay(host: IosHost, safeArea: SafeAreaPadding) {
    var report by remember { mutableStateOf(host.storedCrash()) }
    val current = report ?: return
    ArchiveTuneTheme {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeAreaPadding(safeArea)) {
            CrashReportDialog(
                report = current,
                appName = "Natune",
                actions = CrashReportActions(
                    copyToClipboard = ::copyToIosClipboard,
                    shareLogs = { iosShareText(host, it) },
                    report = null,
                    delete = { if (host.clearCrash()) report = null },
                    close = { report = null },
                ),
            )
        }
    }
}

@Composable
private fun IosBoundary(safeArea: SafeAreaPadding, content: @Composable () -> Unit) {
    ArchiveTuneTheme {
        // Same ownership as the ready root: the theme's background covers the screen and the safe area is reserved
        // inside it, so the strips keep the app's colour instead of the window's.
        Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeAreaPadding(safeArea),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

@Composable
private fun IosBoundaryText(text: String) {
    Text(text, Modifier.padding(24.dp), textAlign = TextAlign.Center)
}
