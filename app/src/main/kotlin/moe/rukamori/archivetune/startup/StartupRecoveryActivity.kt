package moe.rukamori.archivetune.startup

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.IOException
import java.util.concurrent.Executors
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.MainActivity
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.backup.AndroidRestoreCoordinator
import moe.rukamori.archivetune.ui.theme.ArchiveTuneTheme

/**
 * Launcher gate and recovery UI. Nothing from the main process is initialised before the recovery decision:
 * no Hilt graph, preferences, providers or native runtime. The theme's default path reads bundled fonts and
 * the system palette only, and text follows the system font scale.
 */
class StartupRecoveryActivity : ComponentActivity() {
    private val reader = Executors.newSingleThreadExecutor()
    private var generation = 0
    private var state by mutableStateOf<RecoveryState>(RecoveryState.Loading)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra(EXTRA_RESTORE_BLOCKED, false)) {
            state = RecoveryState.RestoreBlocked
            setContent {
                ArchiveTuneTheme {
                    RestoreBlockedScreen(
                        onRetry = ::retryBlockedRestore,
                        onClose = ::finishAndRemoveTask,
                    )
                }
            }
            return
        }
        setContent {
            ArchiveTuneTheme {
                when (val current = state) {
                    RecoveryState.Loading -> RecoveryLoadingScreen()
                    is RecoveryState.Report -> RecoveryReportScreen(
                        report = current.report,
                        onShare = ::shareReport,
                        onContinue = ::continueNormally,
                        onClose = ::closeReport,
                        onActionFailed = ::showActionFailure,
                    )
                    RecoveryState.RestoreBlocked -> RestoreBlockedScreen(
                        onRetry = ::retryBlockedRestore,
                        onClose = ::finishAndRemoveTask,
                    )
                }
            }
        }
        checkPreviousLaunch()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_RESTORE_BLOCKED, false)) {
            state = RecoveryState.RestoreBlocked
            return
        }
        checkPreviousLaunch()
    }

    private fun checkPreviousLaunch() {
        val current = ++generation
        state = RecoveryState.Loading
        reader.execute {
            val restoreBlocked =
                runCatching { AndroidRestoreCoordinator.hasBlockedMarker(this) }
                    .onFailure { Log.w("StartupRecovery", "Blocked restore marker could not be read", it) }
                    .getOrDefault(false)
            if (restoreBlocked) {
                runOnUiThread {
                    if (current != generation || isFinishing || isDestroyed) return@runOnUiThread
                    state = RecoveryState.RestoreBlocked
                }
            } else {
                val result = runCatching { previousLaunchReport(this) }
                runOnUiThread {
                    if (current != generation || isFinishing || isDestroyed) return@runOnUiThread
                    result.fold(
                        onSuccess = { report -> if (report == null) openMain(restart = false) else showReport(report) },
                        onFailure = {
                            showReport(StartupReport(getString(R.string.startup_report_unavailable), true, 0L))
                        },
                    )
                }
            }
        }
    }

    private fun showReport(report: StartupReport) {
        state = RecoveryState.Report(report)
    }

    private fun shareReport(report: StartupReport) {
        // Text sharing avoids a FileProvider call, which would start the broken main process.
        runCatching {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, report.text)
            }, getString(R.string.share_logs)))
        }.onFailure { showActionFailure() }
    }

    private fun continueNormally(report: StartupReport) {
        runCatching {
            val store = StartupRecovery.store(this)
            if (report.safeMode) {
                liveMainProcessId(this)?.let { Process.killProcess(it) }
            }
            if (!store.clearPending()) throw IOException("Startup marker could not be cleared")
            runCatching { store.acknowledgeThrough(report.reviewedThroughMs) }
            openMain(restart = report.safeMode)
        }.onFailure { showActionFailure() }
    }

    // Dismissing also retires the attempt and acknowledges the report, or it would reappear next launch.
    private fun closeReport(report: StartupReport) {
        runCatching {
            val store = StartupRecovery.store(this)
            if (!store.clearPending()) throw IOException("Startup marker could not be cleared")
            runCatching { store.acknowledgeThrough(report.reviewedThroughMs) }
            finishAndRemoveTask()
        }.onFailure { showActionFailure() }
    }

    private fun retryBlockedRestore() {
        AndroidRestoreCoordinator.clearBlockedMarker(this)
        openMain(restart = false)
    }

    private fun openMain(restart: Boolean) {
        runCatching {
            startActivity(Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (restart) addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
            })
            finish()
        }.onFailure {
            showActionFailure()
        }
    }

    private fun showActionFailure() {
        Toast.makeText(this, R.string.startup_recovery_action_failed, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        generation++
        reader.shutdownNow()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_RESTORE_BLOCKED = "restore_blocked"
    }
}

private sealed interface RecoveryState {
    data object Loading : RecoveryState
    data class Report(val report: StartupReport) : RecoveryState
    data object RestoreBlocked : RecoveryState
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RecoveryLoadingScreen() {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LoadingIndicator(modifier = Modifier.size(48.dp))
            Text(
                text = stringResource(R.string.startup_recovery_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

@Composable
private fun RestoreBlockedScreen(onRetry: () -> Unit, onClose: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.restore_blocked_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.restore_blocked_body),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 12.dp),
            )
            OutlinedButton(onClick = onRetry, modifier = Modifier.padding(top = 24.dp)) {
                Text(stringResource(R.string.restore_blocked_retry))
            }
            Button(onClick = onClose, modifier = Modifier.padding(top = 8.dp)) {
                Text(stringResource(R.string.restore_blocked_close))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun RecoveryReportScreen(
    report: StartupReport,
    onShare: (StartupReport) -> Unit,
    onContinue: (StartupReport) -> Unit,
    onClose: (StartupReport) -> Unit,
    onActionFailed: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val title = stringResource(if (report.safeMode) R.string.startup_safe_mode_title else R.string.startup_report_title)
    val copiedMessage = stringResource(R.string.copied)

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    )
                },
                actions = {
                    IconButton(
                        onClick = {
                            runCatching {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                clipboard?.setPrimaryClip(ClipData.newPlainText(title, report.text))
                            }.onSuccess {
                                scope.launch { snackbarHostState.showSnackbar(copiedMessage) }
                            }.onFailure { onActionFailed() }
                        },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.content_copy),
                            contentDescription = stringResource(R.string.copy),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = { onContinue(report) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(stringResource(R.string.startup_continue_normally))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = { onShare(report) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.share))
                    }
                    TextButton(
                        onClick = { onClose(report) },
                        modifier = Modifier.weight(1f),
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Text(stringResource(R.string.close))
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                shape = RoundedCornerShape(18.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.error),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        text = stringResource(R.string.startup_recovery_privacy),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                shape = RoundedCornerShape(18.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                SelectionContainer(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        text = report.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
