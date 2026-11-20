package app.natune.shared.ui.player

import app.natune.shared.domain.playback.PlaybackSnapshot
import app.natune.shared.domain.repository.ContentOccurrence
import app.natune.shared.domain.repository.QueueState

/** The engine's occurrence wins during handoff; a missing engine occurrence never borrows the queue cursor's item. */
fun playerOccurrence(queue: QueueState, playback: PlaybackSnapshot?): ContentOccurrence? {
    val id = playback?.occurrenceId ?: queue.currentOccurrenceId
    return queue.entries.firstOrNull { it.occurrenceId == id }
}
