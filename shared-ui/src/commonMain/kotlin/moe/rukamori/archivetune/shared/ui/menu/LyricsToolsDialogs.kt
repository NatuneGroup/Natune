package moe.rukamori.archivetune.shared.ui.menu

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.lyrics.MAX_LYRICS_SYNC_OFFSET_MS
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.stringResource

interface LyricsToolsBinding {
    val editOpen: Boolean
    val timingTarget: ContentRef?
    fun currentSnapshot(): LyricsEditSnapshot?
    suspend fun applyEdit(before: LyricsEditSnapshot, text: String): Boolean
    suspend fun readOffset(ref: ContentRef): Long?
    suspend fun saveOffset(ref: ContentRef, value: Long?)
    fun closeEdit()
    fun closeTiming()
}

/** Revision-checked edit and timing dialogs; storage and current-track admission stay with the host. */
@Composable
fun LyricsToolsDialogs(binding: LyricsToolsBinding, onFailure: () -> Unit) {
    if (binding.editOpen) {
        val before = remember(binding) { binding.currentSnapshot() }
        var text by remember(before) { mutableStateOf(before?.lyrics.orEmpty()) }
        var busy by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        AlertDialog(onDismissRequest = { if (!busy) binding.closeEdit() },
            title = { Text(stringResource(Res.string.ios_lyrics_edit)) },
            text = { OutlinedTextField(text, { text = it }, minLines = 6, maxLines = 12, enabled = !busy) },
            confirmButton = { TextButton(enabled = !busy && before != null && text.isNotBlank(), onClick = {
                busy = true
                scope.launch {
                    try {
                        if (before != null && (text == before.lyrics || binding.applyEdit(before, text))) binding.closeEdit() else onFailure()
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { onFailure() }
                    finally { busy = false }
                }
            }) { Text(stringResource(Res.string.m6_save)) } },
            dismissButton = { TextButton(enabled = !busy, onClick = binding::closeEdit) { Text(stringResource(Res.string.m6_cancel)) } })
    }
    binding.timingTarget?.let { ref ->
        var offset by remember(ref) { mutableStateOf("") }
        val scope = rememberCoroutineScope()
        LaunchedEffect(ref) { offset = (binding.readOffset(ref) ?: 0L).toString() }
        val value = offset.toLongOrNull()?.takeIf { it in -MAX_LYRICS_SYNC_OFFSET_MS..MAX_LYRICS_SYNC_OFFSET_MS }
        AlertDialog(onDismissRequest = binding::closeTiming,
            title = { Text(stringResource(Res.string.phone_lyrics_lyrics_sync_offset)) },
            text = { Column { OutlinedTextField(offset, { offset = it }, singleLine = true, label = { Text(stringResource(Res.string.ios_lyrics_timing_ms)) }) } },
            confirmButton = { TextButton(enabled = value != null, onClick = { scope.launch {
                try { binding.saveOffset(ref, value); binding.closeTiming() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { onFailure() }
            } }) { Text(stringResource(Res.string.m6_save)) } },
            dismissButton = { TextButton(onClick = { scope.launch {
                try { binding.saveOffset(ref, null); binding.closeTiming() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { onFailure() }
            } }) { Text(stringResource(Res.string.ios_lyrics_reset)) } })
    }
}
