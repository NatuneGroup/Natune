package moe.rukamori.archivetune.shared.ui.menu

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.time.Clock
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.songmetadata.SongMetadataRepository
import moe.rukamori.archivetune.shared.domain.songmetadata.buildSongMetadataOverride
import moe.rukamori.archivetune.shared.domain.songmetadata.songArtistNames
import moe.rukamori.archivetune.shared.domain.songmetadata.withSongMetadataOverride
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.stringResource

/** Shared by the desktop overflow and the iOS row dialog. Failed writes leave the editor open for retry. */
@Composable
fun BoundEditSongInfoDialog(
    song: ContentItem,
    repository: SongMetadataRepository,
    onDismiss: () -> Unit,
    onFailure: () -> Unit,
) {
    val observed by remember(repository) { repository.observeOverrides() }.collectAsState(initial = null)
    val edits = observed ?: return
    val initial = song.withSongMetadataOverride(edits[song.ref])
    var title by rememberSaveable(song.ref) { mutableStateOf(initial.title) }
    var artists by rememberSaveable(song.ref) { mutableStateOf(initial.songArtistNames().joinToString("\n")) }
    var busy by remember(song.ref) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val names = artists.lines().map(String::trim).filter(String::isNotEmpty)
    fun commit(reset: Boolean) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                repository.setOverride(
                    song.ref,
                    if (reset) null else buildSongMetadataOverride(song, title, names, Clock.System.now().toEpochMilliseconds()),
                )
                onDismiss()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                onFailure()
            } finally {
                busy = false
            }
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(Res.string.song_metadata_edit)) },
        text = {
            Column {
                OutlinedTextField(
                    value = title, onValueChange = { title = it }, enabled = !busy, singleLine = true,
                    label = { Text(stringResource(Res.string.song_metadata_title)) },
                    modifier = Modifier.fillMaxWidth().testTag("song-metadata-title"),
                )
                OutlinedTextField(
                    value = artists, onValueChange = { artists = it }, enabled = !busy, maxLines = 5,
                    label = { Text(stringResource(Res.string.song_metadata_artists)) },
                    modifier = Modifier.fillMaxWidth().testTag("song-metadata-artists"),
                )
                TextButton(
                    onClick = { commit(reset = true) }, enabled = !busy && edits[song.ref] != null,
                    modifier = Modifier.testTag("song-metadata-reset"),
                ) { Text(stringResource(Res.string.song_metadata_reset)) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { commit(reset = false) }, enabled = !busy && title.isNotBlank() && names.isNotEmpty(),
                modifier = Modifier.testTag("song-metadata-save"),
            ) { Text(stringResource(Res.string.m6_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(Res.string.m6_cancel)) }
        },
    )
}
