@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package moe.rukamori.archivetune.shared.ui.screens.diagnostics

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.shared.domain.diagnostics.DiagnosticLevel
import moe.rukamori.archivetune.shared.domain.diagnostics.DiagnosticLogStore
import moe.rukamori.archivetune.shared.domain.diagnostics.DiagnosticRecord
import moe.rukamori.archivetune.shared.domain.diagnostics.filterDiagnosticRecords
import moe.rukamori.archivetune.shared.domain.diagnostics.formatDiagnosticRecords
import moe.rukamori.archivetune.shared.domain.settings.LogcatPausedKey
import moe.rukamori.archivetune.shared.domain.settings.SettingsStore
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * What the platform does with the viewer's text. [share] hands the visible lines to the platform's share route, and
 * [export] writes the full diagnostic report somewhere the user chose; it answers false when nothing was written,
 * and null when the user cancelled.
 */
class DiagnosticLogActions(
    val back: () -> Unit,
    val copyToClipboard: (String) -> Unit,
    val share: (String) -> Unit,
    val export: suspend () -> Boolean?,
)

/**
 * The Debug Logs page of the Android app over a host's [DiagnosticLogStore]: search, level chips, pause (saved under the
 * same preference), Share / Export / Clear, tap to expand, long-press to copy, and auto-scroll that stops while the user
 * scrolls. The page re-reads the store only when it has changed and only while it is in composition and not paused.
 */
@Composable
fun DiagnosticLogScreen(
    store: DiagnosticLogStore,
    settings: SettingsStore,
    formatTime: (Long) -> String,
    actions: DiagnosticLogActions,
    modifier: Modifier = Modifier,
) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val preferences by settings.snapshots.collectAsState()
    val paused = preferences?.get(LogcatPausedKey) ?: false
    var records by remember { mutableStateOf<List<DiagnosticRecord>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var levels by remember { mutableStateOf(DiagnosticLevel.entries.toSet()) }
    var expanded by remember { mutableStateOf(emptySet<String>()) }
    var menuOpen by remember { mutableStateOf(false) }
    var autoScrollPaused by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    val copiedText = stringResource(Res.string.m11_copied_to_clipboard)
    val exportFailedText = stringResource(Res.string.m11_export_logs_failed)
    val errorText = stringResource(Res.string.m11_error_unknown)

    LaunchedEffect(store, paused, attempt) {
        suspend fun load() {
            try {
                records = withContext(Dispatchers.Default) { store.readForViewer() }
                failed = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failed = true
            }
        }
        // Paused keeps the lines on screen; a retry or a clear while paused reads once more.
        if (paused) {
            load()
            return@LaunchedEffect
        }
        // A StateFlow collector skips the revisions written while it reads, so a busy log is read at most this often.
        store.revision.collect {
            load()
            delay(VIEWER_REFRESH_MS)
        }
    }

    val loaded = records
    val visible = remember(loaded, query, levels) { filterDiagnosticRecords(loaded.orEmpty(), query, levels) }
    val listState = rememberLazyListState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val userScroll = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && (consumed.y != 0f || available.y != 0f)) autoScrollPaused = true
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(visible.size, autoScrollPaused) {
        if (visible.isNotEmpty() && !autoScrollPaused) listState.animateScrollToItem(visible.lastIndex)
    }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text(stringResource(Res.string.m11_debug_logs), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                subtitle = { Text(stringResource(Res.string.m11_filter_all_logs), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = actions.back) { Icon(painterResource(Res.drawable.m4_icon_arrow_back), null) }
                },
                actions = {
                    IconButton(
                        onClick = {
                            scope.launch {
                                try {
                                    settings.edit { this[LogcatPausedKey] = !paused }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    snackbar.showSnackbar(errorText)
                                }
                            }
                        },
                        enabled = loaded != null,
                    ) {
                        Icon(
                            painterResource(if (paused) Res.drawable.m4lf_icon_play else Res.drawable.m11_icon_pause),
                            stringResource(if (paused) Res.string.m11_resume_logs else Res.string.m11_pause),
                        )
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }, enabled = loaded != null) {
                            Icon(painterResource(Res.drawable.m4hv_icon_more_vert), stringResource(Res.string.m11_options))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.m11_share)) },
                                onClick = {
                                    menuOpen = false
                                    actions.share(formatDiagnosticRecords(visible, formatTime))
                                },
                                enabled = visible.isNotEmpty(),
                                leadingIcon = { Icon(painterResource(Res.drawable.m6yp_icon_share), null) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.m11_export_logs)) },
                                onClick = {
                                    menuOpen = false
                                    exporting = true
                                    scope.launch {
                                        val written = try {
                                            actions.export()
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (_: Exception) {
                                            false
                                        } finally {
                                            exporting = false
                                        }
                                        if (written == false) snackbar.showSnackbar(exportFailedText)
                                    }
                                },
                                enabled = visible.isNotEmpty() && !exporting,
                                leadingIcon = { Icon(painterResource(Res.drawable.m6yp_icon_download), null) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.m11_clear)) },
                                onClick = {
                                    menuOpen = false
                                    expanded = emptySet()
                                    store.clearViewer()
                                    // A paused viewer still shows the clear at once, as the Android viewer does.
                                    if (paused) attempt++
                                },
                                enabled = !loaded.isNullOrEmpty(),
                                leadingIcon = { Icon(painterResource(Res.drawable.m11_icon_clear_all), null) },
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            AnimatedVisibility(visible = autoScrollPaused && visible.isNotEmpty(), enter = scaleIn(), exit = scaleOut()) {
                FloatingActionButton(onClick = { autoScrollPaused = false }) {
                    Icon(painterResource(Res.drawable.m6_icon_arrow_downward), stringResource(Res.string.m11_jump_to_latest_log))
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.TopCenter) {
            when {
                failed && loaded == null -> Column(
                    Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(errorText, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                    Button(onClick = { attempt++ }) { Text(stringResource(Res.string.m11_retry)) }
                }
                loaded == null -> LoadingIndicator(Modifier.align(Alignment.Center).size(48.dp))
                else -> Column(Modifier.widthIn(max = 840.dp).fillMaxSize().padding(horizontal = 16.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(Res.string.m11_search)) },
                        leadingIcon = { Icon(painterResource(Res.drawable.m4_icon_search), null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }) {
                                    Icon(painterResource(Res.drawable.m4_icon_close), stringResource(Res.string.m11_clear))
                                }
                            }
                        },
                    )
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DiagnosticLevel.entries.forEach { level ->
                            FilterChip(
                                selected = level in levels,
                                onClick = { levels = if (level in levels) levels - level else levels + level },
                                label = { Text(stringResource(level.labelResource)) },
                                leadingIcon = { LevelBadge(level) },
                            )
                        }
                    }
                    if (visible.isEmpty()) {
                        EmptyLogs(Modifier.fillMaxSize().weight(1f))
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxWidth().weight(1f).nestedScroll(userScroll),
                            contentPadding = PaddingValues(vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            itemsIndexed(visible, key = { _, record -> record.id }, contentType = { _, record -> record.level }) { index, record ->
                                LogRecordItem(
                                    record = record,
                                    timestamp = formatTime(record.timeMillis),
                                    expanded = record.id in expanded,
                                    index = index,
                                    count = visible.size,
                                    onClick = { expanded = if (record.id in expanded) expanded - record.id else expanded + record.id },
                                    onLongClick = {
                                        actions.copyToClipboard(formatDiagnosticRecords(listOf(record), formatTime))
                                        scope.launch { snackbar.showSnackbar(copiedText) }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LogRecordItem(
    record: DiagnosticRecord,
    timestamp: String,
    expanded: Boolean,
    index: Int,
    count: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val color = record.level.color
    SegmentedListItem(
        onClick = onClick,
        onLongClick = onLongClick,
        onLongClickLabel = stringResource(Res.string.m11_copy_log_entry),
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
        overlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LevelBadge(record.level)
                Text(
                    timestamp,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                record.tag?.let { tag ->
                    Text(tag, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        },
    ) {
        Text(
            record.message,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = color,
            maxLines = if (expanded) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LevelBadge(level: DiagnosticLevel) {
    Surface(shape = MaterialTheme.shapes.extraSmall, color = level.color, modifier = Modifier.size(24.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                level.label.toString(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.surface,
            )
        }
    }
}

@Composable
private fun EmptyLogs(modifier: Modifier) {
    Column(modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(
            painterResource(Res.drawable.m11_icon_manage_search),
            null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Text(stringResource(Res.string.m11_no_logs), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(Res.string.m11_logs_empty_message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val DiagnosticLevel.labelResource: StringResource
    get() = when (this) {
        DiagnosticLevel.VERBOSE -> Res.string.m11_log_level_verbose
        DiagnosticLevel.DEBUG -> Res.string.m11_log_level_debug
        DiagnosticLevel.INFO -> Res.string.m11_log_level_info
        DiagnosticLevel.WARNING -> Res.string.m11_log_level_warning
        DiagnosticLevel.ERROR -> Res.string.m11_log_level_error
    }

private val DiagnosticLevel.color: Color
    @Composable
    get() = when (this) {
        DiagnosticLevel.VERBOSE -> MaterialTheme.colorScheme.outline
        DiagnosticLevel.DEBUG -> MaterialTheme.colorScheme.secondary
        DiagnosticLevel.INFO -> MaterialTheme.colorScheme.primary
        DiagnosticLevel.WARNING -> MaterialTheme.colorScheme.tertiary
        DiagnosticLevel.ERROR -> MaterialTheme.colorScheme.error
    }

private const val VIEWER_REFRESH_MS = 1_000L
