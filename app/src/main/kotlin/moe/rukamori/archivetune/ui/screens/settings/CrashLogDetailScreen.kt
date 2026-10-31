package moe.rukamori.archivetune.ui.screens.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.startup.CrashRecord
import moe.rukamori.archivetune.startup.InstallWindow
import moe.rukamori.archivetune.startup.StartupRecovery
import moe.rukamori.archivetune.startup.currentInstall
import moe.rukamori.archivetune.startup.formatCrashTime
import moe.rukamori.archivetune.startup.labelRes
import moe.rukamori.archivetune.startup.versionLabel
import moe.rukamori.archivetune.ui.component.PreferenceEntry
import moe.rukamori.archivetune.ui.component.SettingsTopAppBar
import moe.rukamori.archivetune.ui.component.preferenceGroup
import moe.rukamori.archivetune.ui.component.IconButton as ArchiveTuneIconButton
import moe.rukamori.archivetune.ui.utils.backToMain

/** One saved record with the install window that decides its version label. */
private class CrashLogDetailContent(
    val record: CrashRecord,
    val install: InstallWindow,
)

private fun loadCrashLogDetail(context: Context, recordId: String?): CrashLogDetailContent? {
    val store = StartupRecovery.store(context)
    val record = recordId?.let { store.readRecord(it) } ?: return null
    // Opening a record is the review of it; only failures newer than this one keep interrupting.
    runCatching { store.acknowledgeThrough(record.timestampMs) }
    return CrashLogDetailContent(record, currentInstall(context))
}

private fun detailRows(context: Context, content: CrashLogDetailContent): List<Pair<String, String>> {
    val record = content.record
    return buildList {
        add(context.getString(R.string.crash_logs_detail_time) to formatCrashTime(context, record.timestampMs))
        add(context.getString(R.string.crash_logs_detail_type) to context.getString(record.kind.labelRes()))
        add(context.getString(R.string.crash_logs_detail_version) to versionLabel(context, record, content.install))
        if (record.process.isNotBlank()) {
            add(context.getString(R.string.crash_logs_detail_process) to record.process)
        }
        if (record.thread.isNotBlank()) {
            add(context.getString(R.string.crash_logs_detail_thread) to record.thread)
        }
        if (record.pid > 0) {
            add(context.getString(R.string.crash_logs_detail_pid) to record.pid.toString())
        }
        if (record.status != 0) {
            add(context.getString(R.string.crash_logs_detail_status) to record.status.toString())
        }
    }
}

/** Plain text for copy and share; the same fields the screen shows, then the stored body. */
private fun crashLogText(context: Context, content: CrashLogDetailContent): String =
    buildString {
        detailRows(context, content).forEach { (label, value) ->
            appendLine(context.getString(R.string.crash_logs_field, label, value))
        }
        appendLine()
        append(content.record.body.ifBlank { context.getString(R.string.crash_report_no_stack_trace) })
    }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CrashLogDetailScreen(
    navController: NavController,
    recordId: String?,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var detail by remember { mutableStateOf<CrashLogDetailContent?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(recordId) {
        loading = true
        detail = withContext(Dispatchers.IO) { loadCrashLogDetail(context, recordId) }
        loading = false
    }

    val loaded = detail
    val rows = remember(loaded) { loaded?.let { detailRows(context, it) }.orEmpty() }
    val noTraceText = stringResource(R.string.crash_report_no_stack_trace)
    val reportBody = loaded?.record?.body?.ifBlank { noTraceText }.orEmpty()
    val truncated = loaded?.record?.truncated == true
    val titleText =
        loaded?.let { stringResource(it.record.kind.labelRes()) }
            ?: stringResource(R.string.crash_logs_title)
    val copiedText = stringResource(R.string.copied)
    val shareTitle = stringResource(R.string.share_logs)
    val notFoundText = stringResource(R.string.crash_logs_not_found)
    val detailsTitle = stringResource(R.string.crash_logs_details)
    val reportTitle = stringResource(R.string.crash_logs_report)
    val truncatedText = stringResource(R.string.crash_logs_truncated)

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            SettingsTopAppBar(
                title = {
                    Text(
                        text = titleText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    ArchiveTuneIconButton(
                        onClick = navController::navigateUp,
                        onLongClick = navController::backToMain,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.arrow_back),
                            contentDescription = stringResource(R.string.back_button_desc),
                        )
                    }
                },
                actions = {
                    if (loaded != null) {
                        IconButton(
                            onClick = {
                                val clipboard =
                                    context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText(null, crashLogText(context, loaded)))
                                scope.launch { snackbarHostState.showSnackbar(copiedText) }
                            },
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.content_copy),
                                contentDescription = stringResource(R.string.copy),
                            )
                        }
                        IconButton(
                            onClick = {
                                val shareIntent =
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, crashLogText(context, loaded))
                                    }
                                context.startActivity(Intent.createChooser(shareIntent, shareTitle))
                            },
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.share),
                                contentDescription = stringResource(R.string.share),
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        val playerAwareBottomPadding =
            LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Bottom).asPaddingValues().calculateBottomPadding()
        val horizontalInsets = LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal)

        if (loaded == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding).windowInsetsPadding(horizontalInsets),
                contentAlignment = Alignment.Center,
            ) {
                if (loading) {
                    LoadingIndicator()
                } else {
                    Text(
                        text = notFoundText,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().windowInsetsPadding(horizontalInsets),
                contentPadding =
                    PaddingValues(
                        top = innerPadding.calculateTopPadding(),
                        bottom = playerAwareBottomPadding + SettingsDimensions.ScreenBottomPadding,
                    ),
            ) {
                preferenceGroup(title = detailsTitle) {
                    rows.forEach { (label, value) ->
                        item {
                            PreferenceEntry(
                                title = { Text(label) },
                                description = value,
                            )
                        }
                    }
                }

                item {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        shape = RoundedCornerShape(18.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                text = reportTitle,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            )
                            SelectionContainer {
                                Text(
                                    text = reportBody,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (truncated) {
                                Text(
                                    text = truncatedText,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
