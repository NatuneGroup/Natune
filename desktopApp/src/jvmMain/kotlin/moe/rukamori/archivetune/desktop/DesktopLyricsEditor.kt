package moe.rukamori.archivetune.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.desktop.integration.DesktopStrings
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.ui.host.HostLyricsDocument
import moe.rukamori.archivetune.shared.ui.host.activeLyricsOccurrence
import moe.rukamori.archivetune.shared.ui.menu.lyricsOverflowActions
import org.jetbrains.compose.resources.stringResource

/** Desktop controls use the shared translation, eligibility, durable edit and undo owners. */
@Composable
internal fun DesktopLyricsEditor(graph: DesktopSessionGraph, item: ContentItem?, document: HostLyricsDocument?) {
    if (item == null || document == null || document.raw.isBlank()) return
    val before by produceState<moe.rukamori.archivetune.shared.ui.menu.LyricsEditSnapshot?>(null, item.ref, document.raw) {
        value = graph.lyricsStore.snapshot(item, document.occurrenceId)
    }
    val captured = before ?: return
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var editing by remember(document.occurrenceId) { mutableStateOf(false) }
    var text by remember(document.occurrenceId, document.raw) { mutableStateOf(document.raw) }
    var failed by remember { mutableStateOf(false) }
    fun current(): Boolean = graph.lyrics.current.value?.let {
        it.occurrenceId == captured.mediaId && it.raw == captured.lyrics &&
            activeLyricsOccurrence(graph.player.snapshot.value?.occurrenceId, graph.queue.state.value.currentOccurrenceId) == captured.mediaId
    } == true
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = { editing = true }) { Text(DesktopStrings["desktop_lyrics_edit"]) }
        Box {
            TextButton(onClick = { expanded = true }) { Text(DesktopStrings["desktop_lyrics_actions"]) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                val actions = lyricsOverflowActions(captured, graph.lyricsSurface.sessions, graph.lyricsEdits, ::current,
                    translateOrdinary = { error("No ordinary desktop translation service") }).filter { it.id != "translate" }
                actions.forEach { action ->
                    DropdownMenuItem(text = { Text(stringResource(action.label)) }, enabled = action.enabled,
                        onClick = {
                            expanded = false
                            scope.launch {
                                try { action.run() }
                                catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { failed = true }
                            }
                        })
                }
            }
        }
    }
    if (editing) AlertDialog(onDismissRequest = { editing = false },
        title = { Text(DesktopStrings["desktop_lyrics_edit"]) },
        text = { OutlinedTextField(text, { if (it.length <= 512 * 1024) text = it },
            modifier = Modifier.width(620.dp).heightIn(min = 180.dp, max = 400.dp)) },
        confirmButton = { TextButton(onClick = {
            scope.launch {
                try { if (graph.lyricsEdits.apply(captured, text, ::current)) editing = false else failed = true }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { failed = true }
            }
        }, enabled = text.isNotBlank() && text != captured.lyrics) { Text(DesktopStrings["ok"]) } },
        dismissButton = { TextButton(onClick = { editing = false }) { Text(DesktopStrings["cancel"]) } })
    if (failed) AlertDialog(onDismissRequest = { failed = false }, text = { Text(DesktopStrings["desktop_lyrics_edit_failed"]) },
        confirmButton = { TextButton(onClick = { failed = false }) { Text(DesktopStrings["ok"]) } })
}
