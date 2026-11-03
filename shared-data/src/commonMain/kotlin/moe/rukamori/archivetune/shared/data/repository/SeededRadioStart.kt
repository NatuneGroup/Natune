package moe.rukamori.archivetune.shared.data.repository

import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.TypedQueueOwnerPort

/**
 * "Start radio" on [seed] as one queue command. The seed is compared with the playing song and the radio is committed
 * under the same queue lock, so an automatic advance between the two can neither keep a song that is no longer the
 * seed nor restart one that still is. When [seed] is playing only the watch-next tail is replaced; otherwise [solo]
 * plays alone and the tail is seeded behind it. False when no tail could be seeded.
 */
suspend fun TypedQueueOwnerPort.startRadioOnSeed(
    seed: ContentRef,
    solo: suspend () -> ContentOccurrence,
): Boolean =
    withQueueAccess {
        val queue = this.state.value
        val playing = queue.entries.firstOrNull { it.occurrenceId == queue.currentOccurrenceId }
        if (playing?.item?.ref != seed) {
            val entry = solo()
            replace(listOf(entry), entry.occurrenceId, playWhenReady = true)
        }
        startRadioSeamlessly()
    }
