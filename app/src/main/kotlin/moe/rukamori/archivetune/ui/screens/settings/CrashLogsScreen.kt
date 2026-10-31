package moe.rukamori.archivetune.ui.screens.settings

import android.content.Context
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.startup.CrashRecord
import moe.rukamori.archivetune.startup.InstallWindow
import moe.rukamori.archivetune.startup.MAX_CRASH_RECORDS
import moe.rukamori.archivetune.startup.StartupRecovery
import moe.rukamori.archivetune.startup.currentInstall
import moe.rukamori.archivetune.startup.formatCrashTime
import moe.rukamori.archivetune.startup.labelRes
import moe.rukamori.archivetune.startup.versionLabel
import moe.rukamori.archivetune.ui.component.DefaultDialog
import moe.rukamori.archivetune.ui.component.IconButton
import moe.rukamori.archivetune.ui.component.PreferenceEntry
import moe.rukamori.archivetune.ui.component.SettingsTopAppBar
import moe.rukamori.archivetune.ui.component.preferenceGroup
import moe.rukamori.archivetune.ui.utils.backToMain

/** The list as read from the store, with the install window that decides the version labels. */
private class CrashLogsContent(
    val install: InstallWindow,
    val records: List<CrashRecord>,
)

private fun loadCrashLogs(context: Context): CrashLogsContent {
    val store = StartupRecovery.store(context)
    val records = store.listRecords()
    // Opening the list is the review: the newest record stops interrupting the next launch.
    records.firstOrNull()?.let { newest -> runCatching { store.acknowledgeThrough(newest.timestampMs) } }
    return CrashLogsContent(currentInstall(context), records)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CrashLogsScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var crashLogs by remember { mutableStateOf<CrashLogsContent?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        crashLogs = withContext(Dispatchers.IO) { loadCrashLogs(context) }
    }

    val loaded = crashLogs
    val records = loaded?.records.orEmpty()
    val limitNote = stringResource(R.string.crash_logs_limit_note, MAX_CRASH_RECORDS)
    val emptyText = stringResource(R.string.crash_logs_empty)
    val clearedText = stringResource(R.string.crash_logs_cleared)
    val errorText = stringResource(R.string.error_unknown)

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            SettingsTopAppBar(
                title = { Text(stringResource(R.string.crash_logs_title)) },
                navigationIcon = {
                    IconButton(
                        onClick = navController::navigateUp,
                        onLongClick = navController::backToMain,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.arrow_back),
                            contentDescription = stringResource(R.string.back_button_desc),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        val playerAwareBottomPadding =
            LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Bottom).asPaddingValues().calculateBottomPadding()
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal)),
            contentPadding =
                PaddingValues(
                    top = innerPadding.calculateTopPadding(),
                    bottom = playerAwareBottomPadding + SettingsDimensions.ScreenBottomPadding,
                ),
        ) {
            item {
                Text(
                    text = limitNote,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            if (loaded == null) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        LoadingIndicator()
                    }
                }
            } else if (records.isEmpty()) {
                item {
                    Text(
                        text = emptyText,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp),
                    )
                }
            } else {
                preferenceGroup {
                    records.forEach { record ->
                        item {
                            CrashRecordRow(
                                record = record,
                                install = loaded.install,
                                onOpen = { navController.navigate("settings/crash_logs/${record.id}") },
                            )
                        }
                    }
                }

                preferenceGroup(modifier = Modifier.padding(top = 12.dp)) {
                    item {
                        PreferenceEntry(
                            title = {
                                Text(
                                    text = stringResource(R.string.crash_logs_clear),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            },
                            icon = {
                                Icon(
                                    painter = painterResource(R.drawable.delete),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            },
                            onClick = { confirmClear = true },
                        )
                    }
                }
            }
        }
    }

    if (confirmClear) {
        DefaultDialog(
            onDismiss = { confirmClear = false },
            title = { Text(stringResource(R.string.crash_logs_clear_title)) },
            buttons = {
                TextButton(
                    onClick = { confirmClear = false },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(stringResource(R.string.cancel))
                }
                TextButton(
                    onClick = {
                        confirmClear = false
                        scope.launch {
                            val cleared =
                                withContext(Dispatchers.IO) {
                                    StartupRecovery.store(context).clearRecords()
                                }
                            crashLogs = withContext(Dispatchers.IO) { loadCrashLogs(context) }
                            snackbarHostState.showSnackbar(if (cleared) clearedText else errorText)
                        }
                    },
                    shapes = ButtonDefaults.shapes(),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(stringResource(R.string.clear))
                }
            },
        ) {
            Text(
                text = stringResource(R.string.crash_logs_clear_message),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun CrashRecordRow(
    record: CrashRecord,
    install: InstallWindow,
    onOpen: () -> Unit,
) {
    val context = LocalContext.current
    val kind = stringResource(record.kind.labelRes())
    val time = formatCrashTime(context, record.timestampMs)
    val version = versionLabel(context, record, install)
    PreferenceEntry(
        title = { Text(stringResource(R.string.crash_logs_row_title, kind, time)) },
        description =
            if (record.process.isBlank()) {
                version
            } else {
                stringResource(R.string.crash_logs_row_description, version, record.process)
            },
        icon = {
            Icon(
                painter = painterResource(R.drawable.bug_report),
                contentDescription = null,
            )
        },
        trailingContent = {
            Icon(
                painter = painterResource(R.drawable.navigate_next),
                contentDescription = null,
            )
        },
        onClick = onOpen,
    )
}
