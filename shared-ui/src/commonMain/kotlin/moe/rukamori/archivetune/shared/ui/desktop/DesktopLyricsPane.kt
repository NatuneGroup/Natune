package moe.rukamori.archivetune.shared.ui.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import moe.rukamori.archivetune.shared.ui.host.HostLyricsDocument
import moe.rukamori.archivetune.shared.ui.host.activeLyricsOccurrence
import moe.rukamori.archivetune.shared.ui.host.belongingTo

/**
 * The lyrics surface's input, resolved against the occurrence that is on screen.
 *
 * The player publishes its new occurrence before the lyrics owner has read it, and the owner keeps the previous track's
 * finished document until it does. Since the occurrence gate that used to hide the text is gone, an unresolved document
 * would draw track A's lines under track B's title for that window; the pane reads as loading instead and shows the
 * lines once the document for the occurrence on screen arrives.
 *
 * [queueOccurrenceId] keeps the playback-failed case readable: with no player snapshot at all, the queue's current
 * occurrence is the one on screen, so a document read for it still renders.
 */
@Composable
fun DesktopLyricsPane(
    document: HostLyricsDocument?,
    playbackOccurrenceId: String?,
    queueOccurrenceId: String?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    val activeOccurrenceId = activeLyricsOccurrence(playbackOccurrenceId, queueOccurrenceId)
    DesktopLyricsBody(
        document = document.belongingTo(activeOccurrenceId),
        onRetry = onRetry,
        modifier = modifier,
        content = content,
    )
}
