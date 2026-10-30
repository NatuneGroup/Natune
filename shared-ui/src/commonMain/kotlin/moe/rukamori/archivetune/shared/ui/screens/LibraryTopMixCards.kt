package moe.rukamori.archivetune.shared.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.ui.resources.Res
import moe.rukamori.archivetune.shared.ui.resources.m9_library_top_mixes
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun LibraryTopMixCards(
    state: LibraryTopMixesUi.Cards,
    actions: LibraryMixHeaderActions,
    artwork: ContentArtwork,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("library-top-mixes")) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(Res.string.m9_library_top_mixes), style = MaterialTheme.typography.titleMedium)
            val refresh = actions.refreshMixes
            val label = state.actionLabel
            if (refresh != null && label != null) {
                TextButton(onClick = refresh, enabled = !state.loading) { Text(label) }
            }
            if (state.loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        state.message?.let {
            Text(
                it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(state.items, key = { it.occurrenceId }) { row ->
                Column(Modifier.width(140.dp).clickable { actions.openMix(row) }.testTag("top-mix-${row.occurrenceId}")) {
                    artwork(row.item, Modifier.size(140.dp))
                    Text(row.item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        row.item.subtitle, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
