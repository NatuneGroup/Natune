package moe.rukamori.archivetune.ui.menu

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.ui.component.DefaultDialog

/**
 * Edits the title and artist names a song is shown with. Both fields start from what the user sees
 * now, so saving without typing changes nothing. Artists are one per line because a name can contain
 * a comma, which rules out a comma-separated field.
 */
@Composable
fun EditSongInfoDialog(
    initialTitle: String,
    initialArtistNames: List<String>,
    hasOverride: Boolean,
    onSave: (title: String, artistNames: List<String>) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    var title by rememberSaveable { mutableStateOf(initialTitle) }
    var artistsText by rememberSaveable { mutableStateOf(initialArtistNames.joinToString("\n")) }
    val artistNames = artistsText.lines().map { it.trim() }.filter { it.isNotEmpty() }
    val canSave = title.isNotBlank() && artistNames.isNotEmpty()

    DefaultDialog(
        onDismiss = onDismiss,
        icon = {
            Icon(
                painter = painterResource(R.drawable.edit),
                contentDescription = null,
            )
        },
        title = { Text(text = stringResource(R.string.edit_song_info)) },
        contentScrollable = true,
        buttons = {
            TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) {
                Text(text = stringResource(android.R.string.cancel))
            }
            TextButton(
                enabled = canSave,
                onClick = { onSave(title.trim(), artistNames) },
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(text = stringResource(android.R.string.ok))
            }
        },
    ) {
        Column {
            TextField(
                value = title,
                onValueChange = { title = it },
                placeholder = { Text(text = stringResource(R.string.song_title)) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(),
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            )
            TextField(
                value = artistsText,
                onValueChange = { artistsText = it },
                placeholder = { Text(text = stringResource(R.string.song_artists_label)) },
                minLines = 1,
                maxLines = 5,
                colors = OutlinedTextFieldDefaults.colors(),
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            )
            TextButton(
                onClick = onReset,
                enabled = hasOverride,
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(text = stringResource(R.string.reset_to_original))
            }
        }
    }
}
