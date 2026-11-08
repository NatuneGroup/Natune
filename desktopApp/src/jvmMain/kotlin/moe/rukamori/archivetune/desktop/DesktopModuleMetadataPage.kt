package moe.rukamori.archivetune.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.desktop.integration.DesktopStrings
import moe.rukamori.archivetune.modules.providesAudio
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence

internal val DESKTOP_MODULE_METADATA_FUNCTIONS = mapOf("searchTracks" to "desktop_modules_search", "getHome" to "desktop_modules_home",
    "getLibrary" to "desktop_modules_library", "getAlbum" to "desktop_modules_album", "getArtist" to "desktop_modules_artist",
    "getPlaylist" to "desktop_modules_playlist")

@Composable
internal fun DesktopModuleMetadataPage(graph: DesktopSessionGraph, entry: DesktopModules.Entry, back: () -> Unit) {
    val scope = rememberCoroutineScope()
    val functions = DESKTOP_MODULE_METADATA_FUNCTIONS.filterKeys { it in entry.manifest.capabilities }
    var function by remember(entry.id, entry.source) { mutableStateOf(functions.keys.first()) }
    var query by remember { mutableStateOf("") }
    var rows by remember { mutableStateOf<List<DesktopModuleMetadataItem>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var operation by remember { mutableStateOf<Job?>(null) }
    var expanded by remember { mutableStateOf(false) }
    fun run(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        failed = false
        operation = scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { failed = true; graph.report("Module catalog declined: ${failure.javaClass.simpleName}") }
            finally { busy = false }
        }
    }
    fun load(method: String, id: String) = run {
        rows = emptyList(); loaded = false
        rows = if (method == "searchTracks") graph.modules.searchMetadata(entry, id)
            else graph.modules.metadata(entry, method, id.takeIf(String::isNotBlank))
        loaded = true
    }
    LaunchedEffect(entry.id, entry.source, entry.enabled) { operation?.cancel(); rows = emptyList(); loaded = false }
    Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = back) { Text(DesktopStrings["desktop_modules_back_details"]) }
        Text(entry.manifest.displayName.ifBlank { entry.manifest.name }, style = MaterialTheme.typography.headlineSmall)
        Text(DesktopStrings["desktop_modules_metadata_scope"])
        if (!entry.enabled) Text(DesktopStrings["desktop_modules_disabled"])
        Box {
            OutlinedButton(enabled = !busy, onClick = { expanded = true }) { Text(DesktopStrings[functions.getValue(function)]) }
            DropdownMenu(expanded, { expanded = false }) { functions.forEach { (method, label) ->
                DropdownMenuItem(text = { Text(DesktopStrings[label]) }, onClick = {
                    function = method; query = ""; rows = emptyList(); loaded = false; expanded = false
                })
            } }
        }
        val needsId = function !in setOf("getHome", "getLibrary")
        if (needsId) OutlinedTextField(query, { query = it }, enabled = !busy,
            label = { Text(DesktopStrings[if (function == "searchTracks") "desktop_modules_query" else "desktop_modules_entity_id"]) },
            modifier = Modifier.fillMaxWidth())
        Button(enabled = !busy && entry.enabled && (!needsId || query.isNotBlank()), onClick = { load(function, query) }) {
            Text(DesktopStrings["desktop_modules_load_metadata"])
        }
        if (busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(onClick = { operation?.cancel() }) { Text(DesktopStrings["cancel"]) }
        }
        if (failed) Text(DesktopStrings["desktop_modules_metadata_failed"], color = MaterialTheme.colorScheme.error)
        if (loaded && rows.isEmpty()) Text(DesktopStrings["desktop_modules_metadata_empty"])
        rows.forEach { row ->
            val target = when (row.kind) { ContentKind.ALBUM -> "getAlbum"; ContentKind.ARTIST -> "getArtist"; ContentKind.PLAYLIST -> "getPlaylist"; else -> null }
            ListItem(headlineContent = { Text(row.title) }, supportingContent = { Text(listOfNotNull(row.artists.joinToString(", ").takeIf(String::isNotBlank), row.album).joinToString(" · ")) },
                trailingContent = {
                    if (row.kind == ContentKind.SONG) TextButton(enabled = !busy && entry.enabled, onClick = { run {
                        val item = row.queueItem(entry.id, entry.manifest.providesAudio)
                        val occurrence = ContentOccurrence("module-page:${UUID.randomUUID()}", item)
                        graph.queue.replace(listOf(occurrence), occurrence.occurrenceId, playWhenReady = true, staysFinite = true)
                    } }) { Text(DesktopStrings["desktop_modules_play"]) }
                    else if (target != null && target in functions) TextButton(enabled = !busy && entry.enabled, onClick = {
                        val method = requireNotNull(target); function = method; query = row.id; load(method, row.id)
                    }) { Text(DesktopStrings["desktop_modules_open"]) }
                })
        }
    }
}
