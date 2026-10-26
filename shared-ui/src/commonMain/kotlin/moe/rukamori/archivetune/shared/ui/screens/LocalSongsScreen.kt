package moe.rukamori.archivetune.shared.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.domain.library.LibrarySortState
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.ui.component.LibrarySortHeader
import moe.rukamori.archivetune.shared.ui.resources.*
import moe.rukamori.archivetune.shared.ui.navigation.LocalShellContentPadding
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt
import androidx.compose.runtime.setValue

/** UI projections only; no replacement for native scan config, MIME policy or persisted sort keys. */
enum class LocalSongSort { MODIFIED, NAME, ARTIST, ALBUM }
data class LocalSongUi(val row: ContentOccurrence, val album: String = "", val modifiedEpochMs: Long = Long.MIN_VALUE,
    val supported: Boolean = true)
data class LocalScanUi(
    val permissionGranted: Boolean = false, val scanning: Boolean = false, val failed: Boolean = false,
    val scanned: Int? = null, val removed: Int = 0, val minimumDurationSeconds: Int = 0,
    val includedFolders: List<String> = emptyList(), val excludedFolders: List<String> = emptyList(),
)
data class LocalSongsUi(
    val rows: List<LocalSongUi> = emptyList(), val query: String = "", val sort: LocalSongSort = LocalSongSort.MODIFIED,
    val descending: Boolean = true, val loading: Boolean = false, val failed: Boolean = false,
    val scan: LocalScanUi = LocalScanUi(),
)
class LocalSongsActions(
    val back: () -> Unit, val query: (String) -> Unit, val sort: (LocalSongSort, Boolean) -> Unit,
    val requestPermission: () -> Unit, val scan: () -> Unit, val minimumDuration: (Int) -> Unit,
    val pickFolder: (included: Boolean, current: List<String>) -> Unit,
    val removeFolder: (included: Boolean, folder: String) -> Unit,
    val play: (selected: ContentOccurrence, visibleQueue: List<ContentOccurrence>) -> Unit,
    val retry: () -> Unit, val more: ((ContentOccurrence) -> Unit)?,
)

fun visibleLocalSongs(
    rows: List<LocalSongUi>, query: String, sort: LocalSongSort, descending: Boolean,
    textOrder: Comparator<String> = defaultCollectionTextOrder,
): List<LocalSongUi> {
    val needle = query.trim()
    val filtered = rows.filter { it.supported && (needle.isEmpty() ||
        listOf(it.row.item.title, it.row.item.subtitle, it.album).any { value -> value.contains(needle, true) }) }
    val ordered = when (sort) {
        LocalSongSort.MODIFIED -> filtered.sortedBy { it.modifiedEpochMs }
        LocalSongSort.NAME -> filtered.sortedWith(compareBy(textOrder) { it.row.item.title })
        LocalSongSort.ARTIST -> filtered.sortedWith(compareBy(textOrder) { it.row.item.subtitle })
        LocalSongSort.ALBUM -> filtered.sortedWith(compareBy(textOrder) { it.album })
    }
    return if (descending) ordered.asReversed() else ordered
}

@Composable
fun LocalSongsScreen(
    state: LocalSongsUi, actions: LocalSongsActions, artwork: ContentArtwork,
    modifier: Modifier = Modifier, contentPadding: PaddingValues = LocalShellContentPadding.current,
    textOrder: Comparator<String> = defaultCollectionTextOrder,
) {
    val visible = remember(state.rows, state.query, state.sort, state.descending, textOrder) {
        visibleLocalSongs(state.rows, state.query, state.sort, state.descending, textOrder)
    }
    val queue = remember(visible) { visible.map { it.row } }
    LazyColumn(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentPadding = contentPadding) {
        item("header") {
            ManagementHeader(stringResource(Res.string.m4_local_songs), actions.back)
            OutlinedTextField(state.query, actions.query, Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                label = { Text(stringResource(Res.string.m4_search_query)) }, singleLine = true)
            LibrarySortHeader(
                sortState = LibrarySortState(state.sort, state.descending),
                sortTypes = LocalSongSort.entries,
                sortTypeLabel = { option -> when (option) {
                    LocalSongSort.MODIFIED -> Res.string.m4_modified
                    LocalSongSort.NAME -> Res.string.m4_name
                    LocalSongSort.ARTIST -> Res.string.m4_artists
                    LocalSongSort.ALBUM -> Res.string.m4_albums
                } },
                // The header reports the whole next state; the host stores it with LibrarySortState.writeTo.
                onSortStateChange = { next -> actions.sort(next.type, next.descending) },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        item("scan") { LocalScanPanel(state.scan, actions) }
        item("load") { LoadFeedback(state.loading, state.failed, visible.isEmpty(), actions.retry) }
        items(visible, key = { "local-${it.row.occurrenceId}" }) { song ->
            ListItem(headlineContent = { Text(song.row.item.title) },
                supportingContent = { Text(listOf(song.row.item.subtitle, song.album).filter { it.isNotBlank() }.joinToString(" · ")) },
                leadingContent = { artwork(song.row.item, Modifier.size(48.dp)) },
                trailingContent = actions.more?.let { more -> {
                    TextButton({ more(song.row) }) { Text(stringResource(Res.string.m4_more)) }
                } },
                modifier = Modifier.testTag("local-song-${song.row.occurrenceId}")
                    .clickable { actions.play(song.row, queue) })
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun LocalScanPanel(scan: LocalScanUi, actions: LocalSongsActions) {
    var expanded by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(Res.string.m4_scan_device), style = MaterialTheme.typography.titleMedium)
            when {
                scan.scanning -> Text(stringResource(Res.string.m4_scanning))
                scan.failed -> Text(stringResource(Res.string.m4_scan_failed), color = MaterialTheme.colorScheme.error)
                !scan.permissionGranted -> Text(stringResource(Res.string.m4_scan_permission))
                scan.scanned != null -> Text(stringResource(Res.string.m4_scan_summary, scan.scanned, scan.removed))
            }
            FlowRow {
                Button(if (scan.permissionGranted) actions.scan else actions.requestPermission,
                    enabled = !scan.scanning, modifier = Modifier.testTag("local-scan-primary")) {
                    Text(stringResource(if (scan.permissionGranted) Res.string.m4_scan_device else Res.string.m4_allow))
                }
                TextButton({ expanded = !expanded }) { Text(stringResource(Res.string.m4_scan_filters)) }
            }
            if (expanded) {
                Text(stringResource(Res.string.m4_minimum_seconds, scan.minimumDurationSeconds))
                // Imported values above the baseline picker range remain representable, never silently clamped.
                Slider(scan.minimumDurationSeconds.toFloat(), { actions.minimumDuration(it.roundToInt()) },
                    valueRange = 0f..maxOf(180, scan.minimumDurationSeconds).toFloat(),
                    steps = if (scan.minimumDurationSeconds <= 180) 11 else 0, enabled = !scan.scanning)
                listOf(true, false).forEach { included ->
                    val folders = if (included) scan.includedFolders else scan.excludedFolders
                    Text(stringResource(if (included) Res.string.m4_include_folders else Res.string.m4_exclude_folders))
                    TextButton({ actions.pickFolder(included, folders) }, enabled = !scan.scanning) {
                        Text(stringResource(Res.string.m4_add_folder))
                    }
                    folders.distinct().forEach { folder ->
                        Row {
                            Text(folder, Modifier.weight(1f))
                            TextButton({ actions.removeFolder(included, folder) }, enabled = !scan.scanning) {
                                Text(stringResource(Res.string.m4_remove))
                            }
                        }
                    }
                }
            }
        }
    }
}
