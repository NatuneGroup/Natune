package moe.rukamori.archivetune.shared.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.ui.resources.*
import moe.rukamori.archivetune.shared.ui.navigation.LocalShellContentPadding
import org.jetbrains.compose.resources.stringResource
import androidx.compose.runtime.setValue

data class PlaylistTagEditorUi(val id: String?, val name: String, val color: Color, val palette: List<Color>, val canSave: Boolean)
data class PlaylistTagsUi(
    val tags: List<PlaylistTagUi>, val editor: PlaylistTagEditorUi? = null,
    val loading: Boolean = false, val busy: Boolean = false, val failed: Boolean = false,
)
class PlaylistTagsActions(
    val back: () -> Unit, val retry: () -> Unit, val create: () -> Unit, val edit: (String) -> Unit,
    val name: (String) -> Unit, val color: (Color) -> Unit, val save: () -> Unit, val cancelEdit: () -> Unit,
    val delete: (String) -> Unit, val bulkAssign: () -> Unit,
)

@Composable
fun PlaylistTagsScreen(
    state: PlaylistTagsUi, actions: PlaylistTagsActions, modifier: Modifier = Modifier,
    contentPadding: PaddingValues = LocalShellContentPadding.current,
) {
    var removal by remember { mutableStateOf<PlaylistTagUi?>(null) }
    val busy = state.busy || state.loading
    LazyColumn(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentPadding = contentPadding) {
        item("header") {
            ManagementHeader(stringResource(Res.string.m4_manage_tags), actions.back)
            Row {
                TextButton(actions.create, enabled = !busy) { Text(stringResource(Res.string.m4_new_tag)) }
                TextButton(actions.bulkAssign, enabled = !busy) { Text(stringResource(Res.string.m4_assign_tags)) }
            }
            LoadFeedback(state.loading, state.failed, state.tags.isEmpty(), actions.retry)
        }
        items(state.tags, key = { it.id }) { tag ->
            ListItem(
                headlineContent = { Text(tag.name) },
                leadingContent = { Box(Modifier.size(24.dp).background(tag.color, CircleShape)) },
                trailingContent = { TextButton({ removal = tag }, enabled = !busy, modifier = Modifier.testTag("tag-delete-${tag.id}")) {
                    Text(stringResource(Res.string.m4_remove))
                } },
                modifier = Modifier.clickable(enabled = !busy) { actions.edit(tag.id) },
            )
        }
    }
    state.editor?.let { editor ->
        AlertDialog(onDismissRequest = { if (!busy) actions.cancelEdit() }, modifier = Modifier.testTag("playlist-tag-editor"),
            title = { Text(stringResource(if (editor.id == null) Res.string.m4_new_tag else Res.string.m4_edit_tag)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(editor.name, actions.name, enabled = !busy,
                        label = { Text(stringResource(Res.string.m4_name)) }, singleLine = true)
                    editor.palette.chunked(4).forEach { colors ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            colors.forEach { color ->
                                val description = stringResource(Res.string.m4_tag_color, color.toArgb().toUInt().toString(16).padStart(8, '0'))
                                RadioButton(editor.color == color, { actions.color(color) }, enabled = !busy,
                                    modifier = Modifier.semantics { contentDescription = description },
                                    colors = RadioButtonDefaults.colors(selectedColor = color, unselectedColor = color))
                            }
                        }
                    }
                    if (state.failed) Text(stringResource(Res.string.m4_playlist_write_failed), color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = { TextButton(actions.save, enabled = !busy && editor.canSave && editor.name.isNotBlank(),
                modifier = Modifier.testTag("tag-editor-save")) {
                Text(stringResource(Res.string.m4_save))
            } },
            dismissButton = { TextButton(actions.cancelEdit, enabled = !busy) { Text(stringResource(Res.string.m4_cancel)) } })
    }
    removal?.let { tag ->
        AlertDialog(onDismissRequest = { if (!busy) removal = null },
            title = { Text(stringResource(Res.string.m4_delete_tag)) }, text = { Text(tag.name) },
            confirmButton = { TextButton({ actions.delete(tag.id); removal = null }, enabled = !busy,
                modifier = Modifier.testTag("tag-confirm-delete")) {
                Text(stringResource(Res.string.m4_remove))
            } },
            dismissButton = { TextButton({ removal = null }, enabled = !busy) { Text(stringResource(Res.string.m4_cancel)) } })
    }
}
