@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.diagnostics.CrashLogKind
import moe.rukamori.archivetune.shared.domain.diagnostics.CrashLogRecord
import moe.rukamori.archivetune.shared.domain.diagnostics.CrashLogStore
import moe.rukamori.archivetune.shared.domain.diagnostics.MAX_CRASH_LOG_RECORDS
import moe.rukamori.archivetune.shared.domain.diagnostics.isValidCrashLogRecordId
import moe.rukamori.archivetune.shared.ui.resources.Res
import moe.rukamori.archivetune.shared.ui.resources.m6_crash_copy
import moe.rukamori.archivetune.shared.ui.resources.m6_crash_kind_anr
import moe.rukamori.archivetune.shared.ui.resources.m6_crash_kind_java
import moe.rukamori.archivetune.shared.ui.resources.m6_crash_kind_kotlin
import moe.rukamori.archivetune.shared.ui.resources.m6_crash_kind_native
import moe.rukamori.archivetune.shared.ui.resources.m6_crash_kind_ns
import moe.rukamori.archivetune.shared.ui.resources.m6_crash_kind_other
import moe.rukamori.archivetune.shared.ui.resources.m6_crash_log_details
import moe.rukamori.archivetune.shared.ui.resources.m6_crash_log_report
import moe.rukamori.archivetune.shared.ui.resources.m6_crash_log_truncated
import moe.rukamori.archivetune.shared.ui.resources.m6_crash_logs_cancel
import moe.rukamori.archivetune.shared.ui.resources.m6_crash_logs_clear
import moe.rukamori.archivetune.shared.ui.resources.m6_crash_logs_clear_message
import moe.rukamori.archivetune.shared.ui.resources.m6_crash_logs_clear_title
import moe.rukamori.archivetune.shared.ui.resources.m6_crash_logs_cleared
import moe.rukamori.archivetune.shared.ui.resources.m6_crash_logs_confirm_clear
import moe.rukamori.archivetune.shared.ui.resources.m6_crash_logs_desc
import moe.rukamori.archivetune.shared.ui.resources.m6_crash_logs_empty
import moe.rukamori.archivetune.shared.ui.resources.m6_crash_logs_limit_note
import moe.rukamori.archivetune.shared.ui.resources.m6_crash_logs_not_found
import moe.rukamori.archivetune.shared.ui.resources.m6_crash_logs_title
import moe.rukamori.archivetune.shared.ui.resources.m6_crash_pid
import moe.rukamori.archivetune.shared.ui.resources.m6_crash_process
import moe.rukamori.archivetune.shared.ui.resources.m6_crash_row_description
import moe.rukamori.archivetune.shared.ui.resources.m6_crash_row_title
import moe.rukamori.archivetune.shared.ui.resources.m6_crash_share
import moe.rukamori.archivetune.shared.ui.resources.m6_crash_status
import moe.rukamori.archivetune.shared.ui.resources.m6_crash_thread
import moe.rukamori.archivetune.shared.ui.resources.m6_crash_time
import moe.rukamori.archivetune.shared.ui.resources.m6_crash_type
import moe.rukamori.archivetune.shared.ui.resources.m6_crash_version
import moe.rukamori.archivetune.shared.ui.resources.m6_crash_version_unknown
import moe.rukamori.archivetune.shared.ui.resources.m11_copied_to_clipboard
import moe.rukamori.archivetune.shared.ui.resources.m11_error_unknown
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

const val CrashLogsRoute = "settings/crash_logs"

/** The mounted path for one record; the record id travels as the `id` query argument. */
const val CrashLogRecordRoute = "settings/crash_logs/record"

fun crashLogRecordRoute(id: String): String = "$CrashLogRecordRoute?id=$id"

/** What the platform does with a record's text and the page's back. */
class CrashLogActions(
    val back: () -> Unit,
    val copyToClipboard: (String) -> Unit,
    val share: (String) -> Unit,
)

/**
 * Settings > Developer > Crash logs on every shared host: the Android list over the host's bounded
 * record store — newest first, the kept-count note, and a clear-all that asks once. A record row
 * opens [crashLogRecordRoute].
 */
@Composable
fun CrashLogsSettings(
    store: CrashLogStore,
    formatTime: (Long) -> String,
    openRecord: (String) -> Unit,
    actions: CrashLogActions,
    onFailure: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var confirmClear by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }
    val loaded by produceState<List<CrashLogRecord>?>(null, store, attempt) {
        value = try {
            store.list()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }
    val records = loaded.orEmpty()
    val clearedText = stringResource(Res.string.m6_crash_logs_cleared)
    val errorText = stringResource(Res.string.m11_error_unknown)

    CrashLogScaffold(
        title = stringResource(Res.string.m6_crash_logs_title),
        subtitle = stringResource(Res.string.m6_crash_logs_desc),
        actions = actions,
        snackbar = snackbar,
        modifier = modifier,
    ) { innerPadding ->
        LazyColumn(Modifier.fillMaxSize().padding(innerPadding)) {
            item(key = "limit_note") {
                Text(
                    text = stringResource(Res.string.m6_crash_logs_limit_note, MAX_CRASH_LOG_RECORDS),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (loaded == null) {
                item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        LoadingIndicator()
                    }
                }
            } else if (records.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = stringResource(Res.string.m6_crash_logs_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp),
                    )
                }
            } else {
                items(records, key = { it.id }) { record ->
                    CrashLogRow(record, formatTime) { openRecord(crashLogRecordRoute(record.id)) }
                }
                item(key = "clear") {
                    ListItem(
                        headlineContent = {
                            Text(
                                stringResource(Res.string.m6_crash_logs_clear),
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        modifier = Modifier.clickable { confirmClear = true },
                    )
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(Res.string.m6_crash_logs_clear_title)) },
            text = { Text(stringResource(Res.string.m6_crash_logs_clear_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        scope.launch {
                            val cleared = try {
                                store.clear()
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                false
                            }
                            attempt += 1
                            snackbar.showSnackbar(if (cleared) clearedText else errorText)
                        }
                    },
                ) {
                    Text(
                        stringResource(Res.string.m6_crash_logs_confirm_clear),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(stringResource(Res.string.m6_crash_logs_cancel))
                }
            },
        )
    }
}

@Composable
private fun CrashLogRow(record: CrashLogRecord, formatTime: (Long) -> String, onOpen: () -> Unit) {
    val kind = stringResource(record.kind.labelResource())
    val version = record.versionName.ifBlank { stringResource(Res.string.m6_crash_version_unknown) }
    ListItem(
        headlineContent = {
            Text(
                stringResource(Res.string.m6_crash_row_title, kind, formatTime(record.timestampMs)),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Text(
                if (record.process.isBlank()) {
                    version
                } else {
                    stringResource(Res.string.m6_crash_row_description, version, record.process)
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        modifier = Modifier.clickable(onClick = onOpen),
    )
}

/**
 * One stored record: its labelled facts, then the redacted body in a selectable monospace card, as
 * on Android. Copy and share hand the same assembled text to the host's clipboard and share route.
 */
@Composable
fun CrashLogRecordSettings(
    store: CrashLogStore,
    recordId: String?,
    formatTime: (Long) -> String,
    actions: CrashLogActions,
    onFailure: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var record by remember { mutableStateOf<CrashLogRecord?>(null) }
    val copiedText = stringResource(Res.string.m11_copied_to_clipboard)

    LaunchedEffect(store, recordId) {
        loading = true
        record = try {
            recordId?.takeIf { isValidCrashLogRecordId(it) }?.let { store.read(it) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            onFailure()
            null
        }
        loading = false
    }

    val loaded = record
    val title = loaded?.let { stringResource(it.kind.labelResource()) }
        ?: stringResource(Res.string.m6_crash_logs_title)
    val detailRows = loaded?.let { record ->
        buildList {
            add(stringResource(Res.string.m6_crash_time) to formatTime(record.timestampMs))
            add(stringResource(Res.string.m6_crash_type) to stringResource(record.kind.labelResource()))
            add(
                stringResource(Res.string.m6_crash_version) to
                    record.versionName.ifBlank { stringResource(Res.string.m6_crash_version_unknown) }
            )
            if (record.process.isNotBlank()) add(stringResource(Res.string.m6_crash_process) to record.process)
            if (record.thread.isNotBlank()) add(stringResource(Res.string.m6_crash_thread) to record.thread)
            if (record.pid > 0) add(stringResource(Res.string.m6_crash_pid) to record.pid.toString())
            if (record.status != 0) add(stringResource(Res.string.m6_crash_status) to record.status.toString())
        }
    }.orEmpty()
    val shareText = loaded?.let { crashLogRecordText(detailRows, it) }.orEmpty()

    CrashLogScaffold(
        title = title,
        subtitle = null,
        actions = actions,
        snackbar = snackbar,
        modifier = modifier,
        trailing = {
            if (loaded != null) {
                IconButton(onClick = {
                    actions.copyToClipboard(shareText)
                    scope.launch { snackbar.showSnackbar(copiedText) }
                }) {
                    Icon(
                        painterResource(Res.drawable.m4lf_icon_copy),
                        contentDescription = stringResource(Res.string.m6_crash_copy),
                    )
                }
                IconButton(onClick = { actions.share(shareText) }) {
                    Icon(
                        painterResource(Res.drawable.m6yp_icon_share),
                        contentDescription = stringResource(Res.string.m6_crash_share),
                    )
                }
            }
        },
    ) { innerPadding ->
        if (loaded == null) {
            Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                if (loading) {
                    LoadingIndicator()
                } else {
                    Text(
                        text = stringResource(Res.string.m6_crash_logs_not_found),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                item(key = "details_header") {
                    Text(
                        text = stringResource(Res.string.m6_crash_log_details),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 4.dp),
                    )
                }
                items(detailRows.size) { index ->
                    val (label, value) = detailRows[index]
                    ListItem(
                        headlineContent = { Text(label) },
                        supportingContent = { Text(value) },
                    )
                }
                item(key = "report") {
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
                                text = stringResource(Res.string.m6_crash_log_report),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            )
                            SelectionContainer {
                                Text(
                                    text = loaded.body,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (loaded.truncated) {
                                Text(
                                    text = stringResource(Res.string.m6_crash_log_truncated),
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

/** The labelled rows, then the stored body — the same text Copy and Share carry on Android. */
private fun crashLogRecordText(rows: List<Pair<String, String>>, record: CrashLogRecord): String =
    buildString {
        rows.forEach { (label, value) -> appendLine("$label: $value") }
        appendLine()
        append(record.body)
    }

private fun CrashLogKind.labelResource(): StringResource = when (this) {
    CrashLogKind.JAVA -> Res.string.m6_crash_kind_java
    CrashLogKind.KOTLIN -> Res.string.m6_crash_kind_kotlin
    CrashLogKind.NS -> Res.string.m6_crash_kind_ns
    CrashLogKind.NATIVE -> Res.string.m6_crash_kind_native
    CrashLogKind.ANR -> Res.string.m6_crash_kind_anr
    CrashLogKind.OTHER -> Res.string.m6_crash_kind_other
}

/** The one frame both crash pages draw: title, back and optional actions over host content. */
@Composable
private fun CrashLogScaffold(
    title: String,
    subtitle: String?,
    actions: CrashLogActions,
    snackbar: SnackbarHostState,
    modifier: Modifier,
    trailing: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                subtitle = subtitle?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                navigationIcon = {
                    IconButton(onClick = actions.back) {
                        Icon(painterResource(Res.drawable.m4_icon_arrow_back), contentDescription = null)
                    }
                },
                actions = { trailing() },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innerPadding ->
        content(innerPadding)
    }
}
