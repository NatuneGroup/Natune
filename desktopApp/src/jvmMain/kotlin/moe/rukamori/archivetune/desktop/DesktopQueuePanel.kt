package moe.rukamori.archivetune.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import moe.rukamori.archivetune.shared.ui.queue.PlaybackQueuePanel

/** Artwork remains host-owned; queue behavior and controls are shared with iOS. */
@Composable
internal fun DesktopQueuePanel(graph: DesktopSessionGraph, modifier: Modifier) {
    PlaybackQueuePanel(queue = graph.queue, edits = graph.queue, settings = graph.settings,
        library = graph.library, modifier = modifier) { entry, imageModifier ->
        DesktopRemoteArtwork(entry.item.artworkUri, graph.artworkLoader, imageModifier)
    }
}
