package moe.rukamori.archivetune.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.desktop.integration.DesktopStrings
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence

/**
 * The player's queue panel on this host: the one queue in play order, the current song marked. A row plays its song,
 * and the current row pauses or resumes. Rows can be removed, and moved while shuffle is off; under shuffle the play
 * order is the shuffled one, which a move of the list order would not change, so the move buttons are hidden then.
 * Every command runs through the queue owner, and a refused one is logged.
 */
@Composable
internal fun DesktopQueuePanel(graph: DesktopSessionGraph, modifier: Modifier) {
    val queue by graph.queue.state.collectAsState()
    val rows = remember(queue.entries, queue.playOrder) {
        val byId = queue.entries.associateBy { it.occurrenceId }
        queue.playOrder.mapNotNull { byId[it] }
    }
    val listState = rememberLazyListState()
    val currentIndex = rows.indexOfFirst { it.occurrenceId == queue.currentOccurrenceId }
    LaunchedEffect(queue.currentOccurrenceId) {
        if (currentIndex >= 0) listState.animateScrollToItem(currentIndex)
    }
    if (rows.isEmpty()) {
        Box(modifier.padding(16.dp), contentAlignment = Alignment.Center) {
            Text(DesktopStrings["queue_empty"], style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    LazyColumn(modifier, state = listState) {
        itemsIndexed(rows, key = { _, entry -> entry.occurrenceId }) { index, entry ->
            QueueRow(
                graph = graph,
                entry = entry,
                current = entry.occurrenceId == queue.currentOccurrenceId,
                // Moving up places the row before its predecessor; moving down places it before the row two below,
                // or at the end when it is the second to last.
                moveUpAnchor = if (queue.shuffleEnabled || index == 0) null else rows[index - 1].occurrenceId,
                moveDown = !queue.shuffleEnabled && index < rows.lastIndex,
                moveDownAnchor = rows.getOrNull(index + 2)?.occurrenceId,
                showMoves = !queue.shuffleEnabled,
            )
        }
    }
}

@Composable
private fun QueueRow(
    graph: DesktopSessionGraph,
    entry: ContentOccurrence,
    current: Boolean,
    moveUpAnchor: String?,
    moveDown: Boolean,
    moveDownAnchor: String?,
    showMoves: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (current) colors.secondaryContainer else Color.Transparent)
            .clickable {
                if (current) {
                    if (graph.player.snapshot.value?.playWhenReady == true) graph.player.pause() else graph.player.play()
                } else {
                    queueCommand(graph, "the song was not played") { graph.queue.select(entry.occurrenceId, 0L) }
                }
            }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DesktopRemoteArtwork(entry.item.artworkUri, graph.artworkLoader, Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                entry.item.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                color = if (current) colors.onSecondaryContainer else colors.onSurface,
            )
            val artists = entry.item.artists.joinToString(", ") { it.name }.ifEmpty { entry.item.subtitle }
            if (artists.isNotEmpty()) {
                Text(
                    artists,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = colors.onSurfaceVariant,
                )
            }
        }
        if (showMoves) {
            GlyphButton("\u2191", DesktopStrings["queue_move_up"], enabled = moveUpAnchor != null) {
                val anchor = moveUpAnchor ?: return@GlyphButton
                queueCommand(graph, "the song was not moved") { graph.queue.move(entry.occurrenceId, anchor) }
            }
            GlyphButton("\u2193", DesktopStrings["queue_move_down"], enabled = moveDown) {
                queueCommand(graph, "the song was not moved") { graph.queue.move(entry.occurrenceId, moveDownAnchor) }
            }
        }
        GlyphButton("\u2715", DesktopStrings["queue_remove"], enabled = true) {
            queueCommand(graph, "the song was not removed") { graph.queue.remove(setOf(entry.occurrenceId)) }
        }
    }
}

/** The host has no icon set of its own, so the row actions draw plain glyphs and name themselves for screen readers. */
@Composable
private fun GlyphButton(glyph: String, label: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.semantics { contentDescription = label }) {
        Text(glyph, style = MaterialTheme.typography.titleMedium)
    }
}

private fun queueCommand(graph: DesktopSessionGraph, refusal: String, command: suspend () -> Unit) {
    graph.scope.launch {
        try {
            command()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            graph.report("queue panel: $refusal: ${failure.javaClass.simpleName}")
        }
    }
}
