package moe.rukamori.archivetune.shared.ui.player

import moe.rukamori.archivetune.shared.domain.playback.PlaybackSnapshot
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.QueueState

/** The engine's occurrence wins during handoff; a missing engine occurrence never borrows the queue cursor's item. */
fun playerOccurrence(queue: QueueState, playback: PlaybackSnapshot?): ContentOccurrence? {
    val id = playback?.occurrenceId ?: queue.currentOccurrenceId
    return queue.entries.firstOrNull { it.occurrenceId == id }
}
