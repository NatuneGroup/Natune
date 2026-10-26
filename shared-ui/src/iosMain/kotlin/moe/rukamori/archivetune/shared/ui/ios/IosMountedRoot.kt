package moe.rukamori.archivetune.shared.ui.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import moe.rukamori.archivetune.shared.ui.resources.*
import moe.rukamori.archivetune.shared.ui.resources.ios_host_graph_unavailable
import moe.rukamori.archivetune.shared.ui.resources.ios_settings_unavailable
import moe.rukamori.archivetune.shared.ui.screens.diagnostics.CrashReportActions
import moe.rukamori.archivetune.shared.ui.screens.diagnostics.CrashReportDialog
import moe.rukamori.archivetune.shared.ui.theme.ArchiveTuneTheme
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
    when (val state = session) {
        IosSessionState.Opening -> IosBoundary { CircularProgressIndicator() }
        IosSessionState.SettingsUnavailable -> IosBoundary { IosBoundaryText(stringResource(Res.string.ios_settings_unavailable)) }
        IosSessionState.GraphUnavailable -> IosBoundary { IosBoundaryText(stringResource(Res.string.ios_host_graph_unavailable)) }
        is IosSessionState.Ready -> {
            // Recognition runs on the composition's UI scope and gives the microphone back when it leaves.
            val uiScope = rememberCoroutineScope()
            val recognition = remember(state.graph, uiScope) { IosRecognitionMount(state.graph, uiScope) }
            DisposableEffect(recognition) { onDispose { recognition.close() } }
            val bindings = remember(state.graph, recognition) { iosHostBindings(host, state.graph, recognition) }
            ArchiveTuneRoot(
                host = bindings,
                visible = active,
                resumed = active,
                opticalCapabilities = IosOpticalCapabilities,
            )
        }
    }
    IosCrashOverlay(host)
}

/**
 * The crash report an earlier launch stored, shown once at the next launch as the Android crash screen. Delete removes
 * it; Close keeps it, so it is offered again. iOS has no share sheet bound here, so Share Logs copies the report.
 */
@Composable
private fun IosCrashOverlay(host: IosHost) {
    var report by remember { mutableStateOf(host.storedCrash()) }
    val current = report ?: return
    ArchiveTuneTheme {
        CrashReportDialog(
            report = current,
            appName = "Natune",
            actions = CrashReportActions(
                copyToClipboard = ::copyToIosClipboard,
                shareLogs = ::copyToIosClipboard,
                report = null,
                delete = { if (host.clearCrash()) report = null },
                close = { report = null },
            ),
        )
    }
}

@Composable
private fun IosBoundary(content: @Composable () -> Unit) {
    ArchiveTuneTheme {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
            content()
        }
    }
}

@Composable
private fun IosBoundaryText(text: String) {
    Text(text, Modifier.padding(24.dp), textAlign = TextAlign.Center)
}
