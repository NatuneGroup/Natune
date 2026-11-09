package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.painterResource
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/** Android LibraryChipOrderDialog's list and handles; persistence belongs to the enclosing draft. */
@Composable
internal fun SettingsOrderList(
    ids: List<String>,
    label: (String) -> String,
    enabled: Boolean,
    onMove: (Int, Int) -> Unit,
) {
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to -> if (enabled) onMove(from.index, to.index) }
    LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        itemsIndexed(ids, key = { _, id -> id }) { index, id ->
            ReorderableItem(reorderState, key = id) {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${index + 1}", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = .7f), modifier = Modifier.size(20.dp))
                    Text(label(id), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Icon(painterResource(Res.drawable.m4_drag_handle), null,
                        Modifier.size(20.dp).then(if (enabled) Modifier.draggableHandle() else Modifier),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
                }
            }
        }
    }
}
