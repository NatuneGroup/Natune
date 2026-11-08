package moe.rukamori.archivetune.shared.ui.ios

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.lyrics.MAX_LYRICS_SYNC_OFFSET_MS
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.ui.menu.LyricsEditSnapshot
import moe.rukamori.archivetune.shared.ui.menu.LyricsMenuEdits
import moe.rukamori.archivetune.shared.ui.session.LyricsTranslationPersistence
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.stringResource

internal class IosLyricsTools(private val graph: IosSessionGraph) {
    var editOpen by mutableStateOf(false)
    var timingTarget by mutableStateOf<ContentRef?>(null)
    val edits = LyricsMenuEdits { before, after, guard ->
        if (graph.lyrics.replaceRaw(before.mediaId, before.lyrics, before.revision, after.lyrics, guard))
            after.copy(revision = graph.lyrics.revision) else null
    }
    private val translation = LyricsTranslationPersistence(graph.scope, graph.lyricsSurface.sessions, edits,
        read = { snapshot(it) }, onFailure = { graph.report("AI lyric edit could not be persisted") })
    fun snapshot(id: String? = graph.lyrics.current.value?.occurrenceId): LyricsEditSnapshot? =
        graph.lyrics.current.value?.takeIf { it.occurrenceId == id && it.hasLines }?.let {
            LyricsEditSnapshot(it.occurrenceId, it.raw, "HOST", "shared", graph.lyrics.revision)
        }
}

@Composable
internal fun IosLyricsToolsDialogs(graph: IosSessionGraph) {
    val tools = graph.lyricsTools
    if (tools.editOpen) {
        val before = remember { tools.snapshot() }
        var text by remember { mutableStateOf(before?.lyrics.orEmpty()) }
        val scope = rememberCoroutineScope()
        AlertDialog(onDismissRequest = { tools.editOpen = false },
            title = { Text(stringResource(Res.string.ios_lyrics_edit)) },
            text = { OutlinedTextField(text, { text = it }, minLines = 6, maxLines = 12) },
            confirmButton = { TextButton(onClick = {
                scope.launch {
                    if (before != null) tools.edits.apply(before, text) { tools.snapshot() == before }
                    tools.editOpen = false
                }
            }, enabled = before != null && text.isNotBlank()) { Text(stringResource(Res.string.ios_lyrics_save)) } },
            dismissButton = { TextButton(onClick = { tools.editOpen = false }) { Text(stringResource(Res.string.cancel)) } })
    }
    tools.timingTarget?.let { ref ->
        var offset by remember(ref) { mutableStateOf("") }
        val scope = rememberCoroutineScope()
        LaunchedEffect(ref) { offset = (graph.songLyricsOffsets.offset(ref) ?: 0L).toString() }
        val value = offset.toLongOrNull()?.takeIf { it in -MAX_LYRICS_SYNC_OFFSET_MS..MAX_LYRICS_SYNC_OFFSET_MS }
        AlertDialog(onDismissRequest = { tools.timingTarget = null },
            title = { Text(stringResource(Res.string.ios_lyrics_timing)) },
            text = { Column { OutlinedTextField(offset, { offset = it }, singleLine = true,
                label = { Text(stringResource(Res.string.ios_lyrics_timing_ms)) }) } },
            confirmButton = { TextButton(onClick = { scope.launch {
                graph.songLyricsOffsets.setOffset(ref, value); tools.timingTarget = null
            } }, enabled = value != null) { Text(stringResource(Res.string.ios_lyrics_save)) } },
            dismissButton = { TextButton(onClick = { scope.launch {
                graph.songLyricsOffsets.setOffset(ref, null); tools.timingTarget = null
            } }) { Text(stringResource(Res.string.ios_lyrics_reset)) } })
    }
}
