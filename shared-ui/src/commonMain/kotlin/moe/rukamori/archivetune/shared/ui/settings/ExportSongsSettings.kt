package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

data class ExportSettingsSong(val id: String, val title: String, val artists: String)

/** Shared export selection and confirmation; the host supplies file export/delete operations. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSongsSettings(
    loading: Boolean,
    songs: List<ExportSettingsSong>,
    query: String,
    selected: Set<String>,
    exporting: Boolean,
    progress: Int,
    onQuery: (String) -> Unit,
    onSelection: (Set<String>) -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    var searching by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val visible = songs.filter { query.isBlank() || it.title.contains(query, true) || it.artists.contains(query, true) }
    val count = songs.count { it.id in selected }
    Scaffold(topBar = {
        TopAppBar(title = {
            if (searching) TextField(query, onQuery, singleLine = true,
                placeholder = { Text(stringResource(Res.string.ios_export_songs_search)) })
            else Text(stringResource(Res.string.ios_export_songs_title))
        }, navigationIcon = {
            LocalSettingsBack.current?.let { back -> IconButton(onClick = { if (searching) { searching = false; onQuery("") } else back() }) {
                Icon(painterResource(Res.drawable.m4_icon_arrow_back), stringResource(Res.string.m4_back))
            } }
        }, actions = {
            IconButton(onClick = { searching = !searching }) { Icon(painterResource(Res.drawable.m4_icon_search), stringResource(Res.string.ios_export_songs_search)) }
            IconButton(onClick = { onSelection(if (visible.all { it.id in selected }) selected - visible.map { it.id }.toSet() else selected + visible.map { it.id }) }) {
                Icon(painterResource(Res.drawable.m6yp_icon_select_all), stringResource(Res.string.ios_export_songs_select_all))
            }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            Text(stringResource(Res.string.ios_export_songs_selected, count), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { confirmDelete = true }, enabled = count > 0 && !exporting, modifier = Modifier.weight(1f)) {
                    Icon(painterResource(Res.drawable.m4_icon_delete), null); Text(stringResource(Res.string.ios_export_songs_delete))
                }
                Button(onClick = onExport, enabled = count > 0 && !exporting, modifier = Modifier.weight(1f)) {
                    Icon(painterResource(Res.drawable.m6yp_icon_share), null); Text(stringResource(Res.string.ios_export_songs_export))
                }
            }
            if (exporting) LinearProgressIndicator(progress = { if (count == 0) 0f else progress.toFloat() / count }, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))
            when {
                loading -> CircularProgressIndicator()
                songs.isEmpty() -> Text(stringResource(Res.string.ios_export_songs_empty), Modifier.padding(24.dp))
                else -> LazyColumn(Modifier.weight(1f)) {
                    items(visible, key = { it.id }) { song ->
                        fun toggle() = onSelection(if (song.id in selected) selected - song.id else selected + song.id)
                        ListItem(modifier = Modifier.clickable(enabled = !exporting) { toggle() },
                            leadingContent = { Checkbox(song.id in selected, { toggle() }, enabled = !exporting) },
                            headlineContent = { Text(song.title) }, supportingContent = { Text(song.artists) })
                    }
                }
            }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false },
        text = { Text(stringResource(Res.string.ios_export_songs_delete_confirm, count)) },
        confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text(stringResource(Res.string.ios_export_songs_delete)) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(Res.string.m6_cancel)) } })
}
