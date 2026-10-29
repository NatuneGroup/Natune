package moe.rukamori.archivetune.shared.data.repository

import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.QueueStart

/** The occurrence that takes over when [current] leaves [playOrder]: the next kept one, else the last kept before it. */
internal fun successorAfterRemoval(playOrder: List<String>, current: String, removed: Set<String>): String? {
    val position = playOrder.indexOf(current)
    if (position < 0) return null
    return playOrder.drop(position + 1).firstOrNull { it !in removed }
        ?: playOrder.take(position).lastOrNull { it !in removed }
}

/** The list position a [QueueStart] names: by occurrence id when set, else the matching song or set video. */
internal fun startIndexOf(entries: List<ContentOccurrence>, start: QueueStart?): Int {
    if (start == null) return 0
    val occurrenceId = start.occurrenceId
    if (occurrenceId != null) {
        return entries.indexOfFirst { it.occurrenceId == occurrenceId }
            .takeIf { it >= 0 }
            ?: throw IllegalArgumentException("Start occurrence is not in the collection")
    }
    val sameSong = entries.withIndex().filter { it.value.item.ref == start.item }
    if (sameSong.isEmpty()) throw IllegalArgumentException("Start item is not in the collection")
    val setVideoId = start.setVideoId
    if (setVideoId != null) {
        sameSong.firstOrNull { it.value.setVideoId == setVideoId }?.let { return it.index }
    }
    return sameSong.first().index
}
